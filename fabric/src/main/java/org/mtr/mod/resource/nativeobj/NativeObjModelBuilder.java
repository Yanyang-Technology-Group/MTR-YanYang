package org.mtr.mod.resource.nativeobj;

import org.mtr.mapping.holder.Identifier;
import org.mtr.mapping.mapper.OptimizedModel;
import org.mtr.mapping.render.batch.MaterialProperties;
import org.mtr.mapping.render.model.Mesh;
import org.mtr.mapping.render.object.IndexBuffer;
import org.mtr.mapping.render.object.VertexArray;
import org.mtr.mapping.render.object.VertexBuffer;
import org.mtr.mapping.render.vertex.VertexAttributeMapping;
import org.mtr.mapping.render.vertex.VertexAttributeSource;
import org.mtr.mapping.render.vertex.VertexAttributeType;
import org.mtr.mod.Init;
import org.mtr.mod.resource.OptimizedModelWrapper;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns a finished native build into the GPU objects MTR's optimized renderer consumes.
 *
 * <p>This is the Java half of {@code OptimizedModel.fromObjModels}. The reference
 * implementation calls {@code RawMesh.upload} per material, which allocates an interleaved
 * buffer on the Java heap and hands it to {@code glBufferData}; here the buffer already
 * exists in native memory and is uploaded by address, so a finished model never enters the
 * heap.</p>
 *
 * <h2>Why the vertex layout is duplicated here</h2>
 *
 * <p>{@code OptimizedModel.DEFAULT_MAPPING} is private, so this class rebuilds the same
 * {@link VertexAttributeMapping} from the same declaration. That is safe rather than
 * fragile, because a {@code VertexAttributeMapping} is fully determined by its
 * {@link VertexAttributeSource} choices: the builder derives stride and offsets from
 * {@link VertexAttributeType}'s declarations, so as long as the choices match, the layout
 * matches. {@code OptimizedBackendCompatibilityTest} pins the resulting stride against the
 * native constant so a mismatch fails a test rather than a render.</p>
 *
 * <h2>Upload staging</h2>
 *
 * <p>The GL calls are queued through {@code OptimizedRendererWrapper}, which goes through
 * MTR's GL protection accessor. Model loading happens on the render thread but during
 * resource reload, and the protection mechanism is what keeps MTR's own uploads from
 * tripping over a shader pack's state.</p>
 */
public final class NativeObjModelBuilder {

	/** Byte size of one interleaved vertex. Must match {@code WROBJ_VERTEX_STRIDE}. */
	public static final int VERTEX_STRIDE = 24;

	/**
	 * The vertex attribute layout the OBJ path uses.
	 *
	 * <p>Declared as position, UV and normal in the vertex buffer with colour, overlay,
	 * lightmap and model matrix coming from the global state - the same choices
	 * {@code OptimizedModel}'s static mapping makes.</p>
	 */
	private static final VertexAttributeMapping DEFAULT_MAPPING = new VertexAttributeMapping.Builder()
			.set(VertexAttributeType.POSITION, VertexAttributeSource.VERTEX_BUFFER)
			.set(VertexAttributeType.COLOR, VertexAttributeSource.GLOBAL)
			.set(VertexAttributeType.UV_TEXTURE, VertexAttributeSource.VERTEX_BUFFER)
			.set(VertexAttributeType.UV_OVERLAY, VertexAttributeSource.GLOBAL)
			.set(VertexAttributeType.UV_LIGHTMAP, VertexAttributeSource.GLOBAL)
			.set(VertexAttributeType.NORMAL, VertexAttributeSource.VERTEX_BUFFER)
			.set(VertexAttributeType.MATRIX_MODEL, VertexAttributeSource.GLOBAL)
			.build();

	/**
	 * OpenGL's {@code GL_UNSIGNED_INT}, written out rather than imported.
	 *
	 * <p>{@code IndexBuffer}'s second argument is the index type the draw call passes to
	 * {@code glDrawElements}. The native side always emits 32-bit indices, so this is a
	 * constant rather than a decision. It is spelled out because pulling in the LWJGL OpenGL
	 * bindings from this class would widen the mod's dependency surface for one integer.</p>
	 */
	private static final int GL_UNSIGNED_INT = 0x1405;

	/** The model this builder stages geometry for. */
	private final NativeObjModel model;

	/** One entry per placement added so far, kept only to report the count. */
	private int placementCount;

	private boolean built;

	@Nullable
	private NativeObjResult pendingResult;

	NativeObjModelBuilder(NativeObjModel model) {
		this.model = model;
	}

	/** The model whose source meshes this builder stages. */
	public NativeObjModel getModel() {
		return model;
	}

	/** How many placements have been staged. */
	public int getPlacementCount() {
		return placementCount;
	}

	/**
	 * Stages one placed copy of the model.
	 *
	 * <p>Mirrors {@code ModelPropertiesPart.addObjModelPosition}, which calls
	 * {@code ObjModel.addTransformation(renderStage.shaderType, x, y, z, flipped)}. Placements may
	 * also be added after a build: the per-position door models are built while more placements are
	 * still being staged, and every later build picks the new placements up, exactly as another
	 * {@code OptimizedModel.fromObjModels} call would on the Java path.</p>
	 */
	public void addPlacement(int shaderTypeOrdinal, double x, double y, double z, boolean flipped) {
		model.addPlacement(shaderTypeOrdinal, x, y, z, flipped);
		placementCount++;
	}

	/**
	 * Runs normal generation, de-duplication and packing, then uploads every bucket.
	 *
	 * <p>Repeatable, like the Java path's {@code OptimizedModel.fromObjModels}: MTR builds the same
	 * wrapper list several times - the doors-open model, the doors-closed model, and the
	 * per-position door models - and each call must upload a fresh set of GPU buffers from the
	 * state accumulated so far without consuming it. The native side now matches that by
	 * snapshotting the accumulated model inside {@code wr_obj_builder_build}, so this method may
	 * be called any number of times, and a placement added between two builds appears only in the
	 * later one.</p>
	 *
	 * <p>Returns null when the model produced no geometry, which is the same outcome the Java path
	 * has for a model whose every face was dropped.</p>
	 */
	@Nullable
	public OptimizedModelWrapper build() {
		built = true;

		final long handle = model.ensureHandleForBuild();
		if (handle == 0L) {
			return null;
		}

		pendingResult = NativeObjPipeline.build(handle);
		if (pendingResult == null) {
			return null;
		}

		try {
			final List<VertexArray> vertexArrays = new ArrayList<>(pendingResult.draws.length);
			final Identifier[] textures = model.getTextures();

			for (final NativeObjDraw draw : pendingResult.draws) {
				if (draw.vertexCount <= 0 || draw.indexCount <= 0) {
					continue;
				}
				final VertexArray vertexArray = uploadBucket(draw, textures);
				if (vertexArray != null) {
					vertexArrays.add(vertexArray);
				}
			}

			NativeObjPipeline.recordBuild(placementCount, pendingResult.totalVertexCount(),
					pendingResult.estimatedJavaHeapBytes);

			if (vertexArrays.isEmpty()) {
				return null;
			}
			return OptimizedModelWrapper.fromNativeModel(new OptimizedModel(vertexArrays));
		} finally {
			// The GL upload copied everything it needed, so the native allocation can go. The
			// GL buffer objects own the data from here.
			pendingResult.release();
			pendingResult = null;
		}
	}

	/**
	 * Uploads one bucket: an interleaved vertex buffer, an index buffer, and a vertex array
	 * that binds them together under {@link #DEFAULT_MAPPING}.
	 */
	@Nullable
	private VertexArray uploadBucket(NativeObjDraw draw, Identifier[] textures) {
		final NativeObjResult result = pendingResult;
		if (result == null || result.vertexBuffer == null || result.indexBuffer == null) {
			return null;
		}

		final int vertexByteOffset = draw.vertexOffset * VERTEX_STRIDE;
		final int vertexByteLength = draw.vertexCount * VERTEX_STRIDE;
		final int indexByteOffset = draw.indexOffset * Integer.BYTES;
		final int indexByteLength = draw.indexCount * Integer.BYTES;

		if (vertexByteOffset + vertexByteLength > result.vertexBuffer.capacity()
				|| indexByteOffset + indexByteLength > result.indexBuffer.capacity()) {
			Init.LOGGER.error("[wrobj] a draw record points outside the buffers it came with: {}",
					draw);
			return null;
		}

		// Duplicate rather than slice: a slice of a direct buffer is still direct, and the
		// upload path indexes it from zero so the bucket offset has to be baked in. Both
		// duplicates are views over the same native memory, so this does not copy anything.
		final ByteBuffer vertexSlice = result.vertexBuffer.duplicate()
				.position(vertexByteOffset)
				.limit(vertexByteOffset + vertexByteLength)
				.slice()
				.order(ByteOrder.LITTLE_ENDIAN);
		final ByteBuffer indexSlice = result.indexBuffer.duplicate()
				.position(indexByteOffset)
				.limit(indexByteOffset + indexByteLength)
				.slice()
				.order(ByteOrder.LITTLE_ENDIAN);

		final MaterialProperties materialProperties = new MaterialProperties(
				shaderTypeOf(draw.shaderType),
				textureOf(draw, textures),
				draw.hasColor ? draw.color : null);

		final VertexBuffer vertexBuffer = new VertexBuffer();
		// The overload that takes an explicit size uploads from the buffer's address rather
		// than its remaining() length, so the size is passed in bytes.
		vertexBuffer.upload(vertexSlice, vertexByteLength, VertexBuffer.USAGE_STATIC_DRAW);

		final IndexBuffer indexBuffer = new IndexBuffer(draw.indexCount / 3, GL_UNSIGNED_INT);
		indexBuffer.upload(indexSlice, indexByteLength, VertexBuffer.USAGE_STATIC_DRAW);

		final Mesh mesh = new Mesh(vertexBuffer, indexBuffer, materialProperties);
		return new VertexArray(mesh, DEFAULT_MAPPING);
	}

	/**
	 * Maps a shader type ordinal back to the enum.
	 *
	 * <p>The native ABI carries ordinals because {@code ShaderType} lives in a prebuilt jar
	 * and its numbering is what both sides already agree on. An out-of-range value would mean
	 * a corrupt build, so it falls back to CUTOUT and reports.</p>
	 */
	private static OptimizedModel.ShaderType shaderTypeOf(int ordinal) {
		final OptimizedModel.ShaderType[] values = OptimizedModel.ShaderType.values();
		if (ordinal >= 0 && ordinal < values.length) {
			return values[ordinal];
		}
		Init.LOGGER.error("[wrobj] unknown shader type {} in a draw record, using CUTOUT", ordinal);
		return OptimizedModel.ShaderType.CUTOUT;
	}

	/**
	 * The texture for a bucket.
	 *
	 * <p>The handle is the key index, so this is a direct lookup. An out-of-range handle would
	 * mean the native result and the key table disagreed, which is a bug rather than a fallback
	 * case: reporting it and using the fallback texture keeps the vehicle renderable while
	 * making the disagreement visible.</p>
	 */
	private static Identifier textureOf(NativeObjDraw draw, Identifier[] textures) {
		final int handle = draw.textureHandle;
		if (handle >= 0 && handle < textures.length && textures[handle] != null) {
			return textures[handle];
		}
		if (handle != NativeTextureResolver.NO_TEXTURE) {
			Init.LOGGER.error("[wrobj] draw record names texture handle {} but the key table holds {} "
					+ "entries; using the fallback texture", handle, textures.length);
		}
		return OptimizedModelWrapper.WHITE_TEXTURE;
	}

	/** Discards any staged native state. Called when a part is dropped before rendering. */
	public void discard() {
		pendingResult = null;
	}

	/** True once at least one {@link #build()} has run. Informational only; builds are repeatable. */
	public boolean isBuilt() {
		return built;
	}
}
