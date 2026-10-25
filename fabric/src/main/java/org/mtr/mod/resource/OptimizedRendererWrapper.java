package org.mtr.mod.resource;

import org.mtr.mapping.mapper.GraphicsHolder;
import org.mtr.mapping.mapper.OptimizedModel;
import org.mtr.mapping.mapper.OptimizedRenderer;
import org.mtr.mapping.render.batch.MaterialProperties;
import org.mtr.mapping.render.object.VertexArray;
import org.mtr.mapping.render.shader.ShaderManager;
import org.mtr.mod.Init;
import org.mtr.mod.data.IGui;
import org.mtr.mod.resource.nativeobj.NativeDrawList;
import org.mtr.mod.resource.nativeobj.NativeModelAccess;
import org.mtr.mod.resource.nativeobj.NativeObjPipeline;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * The optimized renderer, driven by the native draw list when the native library can
 * provide one.
 *
 * <h2>What the native path replaces</h2>
 *
 * <p>The reference draw path, per vertex array per frame, allocates a
 * {@code VertexAttributeState} and a {@code RenderCall}, looks the material up in a
 * {@code HashMap} keyed on boxed properties, and applies attribute state twice: once for the
 * material and once for the pose. Together that is a {@code glVertexAttrib4f} per model
 * matrix row, one for the colour, one {@code glVertexAttribI2i} each for the overlay and the
 * lightmap - nine GL calls per array, performed twice over for the colour and lightmap, for
 * values that are uniform across the whole pass.</p>
 *
 * <p>Here the arrays are registered once when a model is built, so per frame a draw costs one
 * small integer written into a flat native array. The native side applies the globals once
 * per flush, the pose once per distinct matrix, and the vertex array binding once per run of
 * the same array. It never applies the material's attribute state, because on this path that
 * state is always overwritten by the pose state a moment later: the material supplies
 * {@code ARGB_WHITE} and the null-colour materials supply nothing that the pose state does
 * not also supply.</p>
 *
 * <p>Shader, texture and blend state stay on the Java side, applied once per material bucket.
 * Every array in a bucket shares its material by construction - that is the bucket key - so
 * the reference's per-array application is provably redundant.</p>
 *
 * <h2>Fallback</h2>
 *
 * <p>If the native library is missing, if its ABI does not match, or if the private backend
 * state cannot be reached, this class never engages and the original {@code OptimizedRenderer}
 * handles everything exactly as before. The choice is made once, at construction, and is
 * reported by {@link #getImplementationName()}.</p>
 */
public final class OptimizedRendererWrapper implements IGui {

	private static long shaderGeneration;

	public static long getShaderGeneration() {
		return shaderGeneration;
	}

	public void invalidateShaders() {
		shaderGeneration++;
	}

	@Nullable
	private final OptimizedRenderer optimizedRenderer;

	/** The native draw list, or null when the original path is in use. */
	@Nullable
	private final NativeDrawList nativeDrawList;

	/** Registered batches per model instance, computed once per model. */
	private final Map<OptimizedModel, ModelBatches> batchesByModel = new IdentityHashMap<>();

	/** The models queued this frame, in order. Reused every frame. */
	private final List<QueuedModel> queue = new ArrayList<>();

	/** Flattened per-batch work for the current flush, reused every frame. */
	private final List<BatchDraw> flattened = new ArrayList<>();

	/** Distinct materials in the current flush, with the range of batches each covers. */
	private final List<MaterialRun> materialRuns = new ArrayList<>();

	/** The pose currently being queued, reused so a frame allocates nothing. */
	private final float[] poseScratch = new float[16];

	/** The globals captured from the queue calls. */
	private int lastLight;

	/** Counts flushes so the statistics line is logged occasionally rather than every frame. */
	private int flushesSinceReport;

	private static final int[] EMPTY_BATCHES = new int[0];

	public OptimizedRendererWrapper() {
		final OptimizedRenderer renderer = OptimizedRenderer.hasOptimizedRendering() ? new OptimizedRenderer() : null;
		this.optimizedRenderer = renderer;

		NativeDrawList created = null;
		if (renderer != null && NativeObjPipeline.isAvailable() && NativeModelAccess.isAvailable()) {
			// gl4ES does not implement integer vertex attributes, and MTR already detects it
			// through GlStateTracker for its own path, so the same answer drives the native
			// emission. A translation layer is what an Android build tends to use.
			created = NativeDrawList.create(org.mtr.mapping.render.tool.GlStateTracker.isGl4ES(), 4096);
			if (created == null) {
				Init.LOGGER.warn("[wrgl] the native draw list could not be created; "
						+ "the Java batch manager will be used");
			} else {
				Init.LOGGER.info("[wrgl] the native OBJ draw path is active");
			}
		} else if (renderer != null) {
			Init.LOGGER.info("[wrgl] the native OBJ draw path is not active: {}",
					NativeModelAccess.getFailureReason());
		}
		this.nativeDrawList = created;
	}

	/** The name of the implementation actually in use, for diagnostics. */
	public String getImplementationName() {
		if (nativeDrawList != null) {
			return "native";
		}
		return optimizedRenderer != null ? "java" : "disabled";
	}

	/** A one-line summary of the draw path's work, or a note that it is not active. */
	public String describeDrawStatistics() {
		return nativeDrawList == null
				? "native draw list: not active"
				: nativeDrawList.describeStatistics();
	}

	public void beginReload() {
		if (optimizedRenderer != null) {
			optimizedRenderer.beginReload();
		}
	}

	public void finishReload() {
		if (optimizedRenderer != null) {
			optimizedRenderer.finishReload();
		}
		// The registrations belong to the previous generation of models, whose GL objects have
		// been deleted. Dropping the cache and resetting the list here is what stops the draw
		// list from holding vertex array names that no longer exist.
		batchesByModel.clear();
		queue.clear();
		if (nativeDrawList != null) {
			nativeDrawList.beginFrame();
		}
	}

	/**
	 * Queues a model for this frame's draw.
	 *
	 * <p>On the native path this records small integers and copies the pose into one shared
	 * direct buffer, so a frame allocates nothing. On the original path it hands the model to
	 * {@code OptimizedRenderer.queue}, which allocates a {@code VertexAttributeState} and a
	 * {@code Matrix4f} copy per call.</p>
	 */
	public void queue(OptimizedModelWrapper optimizedModel, GraphicsHolder graphicsHolder, int light) {
		if (optimizedRenderer == null || optimizedModel.optimizedModel == null) {
			return;
		}

		if (nativeDrawList == null) {
			optimizedRenderer.queue(optimizedModel.optimizedModel, graphicsHolder, ARGB_WHITE, light);
			return;
		}

		if (graphicsHolder == null) {
			return;
		}

		final ModelBatches batches = batchesFor(optimizedModel.optimizedModel);
		if (batches.count == 0) {
			return;
		}

		// The pose comes from the graphics holder's matrix stack, exactly where
		// OptimizedRenderer.queue reads it. The holder's field is package-private in the
		// mapping's package, so the read goes through the same access helper as the rest of
		// the backend's private state. A holder with no matrix stack queues nothing, which is
		// what the reference does too.
		if (!NativeModelAccess.readPoseMatrix(graphicsHolder, poseScratch)) {
			return;
		}

		queue.add(new QueuedModel(batches, poseScratch.clone(), light, ARGB_WHITE));
	}

	/**
	 * Draws everything queued and empties the queue.
	 *
	 * <p>The work is flattened per batch, then grouped into runs of the same material so the
	 * shader and texture state is set up once per run rather than once per array.</p>
	 */
	public void render(boolean renderTranslucent) {
		if (optimizedRenderer == null) {
			return;
		}

		if (nativeDrawList == null) {
			optimizedRenderer.render(renderTranslucent);
			return;
		}

		if (queue.isEmpty()) {
			return;
		}

		try {
			drawQueued();
		} finally {
			queue.clear();
			flattened.clear();
			materialRuns.clear();
		}
	}

	private void drawQueued() {
		final NativeDrawList drawList = nativeDrawList;
		if (drawList == null) {
			return;
		}

		// Flatten to one entry per batch, keeping the model the batch came from so its
		// material and pose can be recovered.
		for (final QueuedModel queued : queue) {
			for (int i = 0; i < queued.batches.count; i++) {
				flattened.add(new BatchDraw(queued.batches.handles[i],
						queued.batches.materials[i], queued.pose, queued.light, queued.color));
			}
		}

		if (flattened.isEmpty()) {
			return;
		}

		// Group into runs of the same material. The native side already orders by shader type,
		// so this only has to notice where the material changes.
		buildMaterialRuns();

		final ShaderManager shaderManager = NativeModelAccess.getShaderManager(optimizedRenderer);

		for (final MaterialRun run : materialRuns) {
			// A material's own lightmap is what the reference would have applied first and the
			// pose state overwrote; the pose state wins here too, and inside one run the light
			// is uniform because a run shares a material and MTR queues one render stage at a
			// time.
			final BatchDraw first = flattened.get(run.start);
			NativeModelAccess.setupShaderBatchState(shaderManager, run.material);
			try {
				for (int i = run.start; i <= run.end; i++) {
					final BatchDraw draw = flattened.get(i);
					drawList.queue(draw.batch, draw.pose);
				}
				// One flush per material. The native side invalidates its attribute cache at
				// the start of every flush, which is what makes this correct: the shader bind
				// just above reset every generic attribute, so the previous material's values
				// must not be reused.
				drawList.flush(first.color, first.light, OVERLAY_DEFAULT, true, true, true);
			} finally {
				NativeModelAccess.cleanupShaderBatchState(shaderManager);
			}
		}

		flushesSinceReport++;
		if (flushesSinceReport >= 1200) {
			flushesSinceReport = 0;
			Init.LOGGER.info("[wrgl] {}", drawList.describeStatistics());
		}
	}

	/**
	 * Finds the runs of consecutive batches that share a material.
	 *
	 * <p>Runs rather than a map: the queue preserves the caller's order, and MTR queues a
	 * whole model per call, so equal materials are already adjacent in practice. A run-based
	 * grouping therefore costs one comparison per batch and no allocation beyond the list
	 * itself, while a map would cost a boxed lookup per batch - which is the cost being
	 * removed.</p>
	 */
	private void buildMaterialRuns() {
		materialRuns.clear();
		int start = 0;
		for (int i = 1; i <= flattened.size(); i++) {
			final boolean boundary = i == flattened.size()
					|| flattened.get(i).material != flattened.get(i - 1).material;
			if (boundary) {
				materialRuns.add(new MaterialRun(start, i - 1, flattened.get(start).material));
				start = i;
			}
		}
	}

	/** {@code OverlayTexture.getDefaultUvMapped()}, which is what the reference supplies. */
	private static final int OVERLAY_DEFAULT = (10 << 16) | 10;

	// -----------------------------------------------------------------------
	// Batch registration
	// -----------------------------------------------------------------------

	/**
	 * The batch handles for a model, registering them on first use.
	 *
	 * <p>Registration walks the model's {@code uploadedParts} and records a handle, the
	 * material and the pose-independent index count for each. The materials are read once
	 * here rather than per frame, which is what removes the per-batch property lookup from
	 * the draw path.</p>
	 */
	private ModelBatches batchesFor(OptimizedModel model) {
		final ModelBatches cached = batchesByModel.get(model);
		if (cached != null) {
			return cached;
		}

		final NativeDrawList drawList = nativeDrawList;
		if (drawList == null) {
			return ModelBatches.EMPTY;
		}

		final List<VertexArray> parts = NativeModelAccess.getUploadedParts(model);
		if (parts == null || parts.isEmpty()) {
			batchesByModel.put(model, ModelBatches.EMPTY);
			return ModelBatches.EMPTY;
		}

		final int[] handles = new int[parts.size()];
		final MaterialProperties[] materials = new MaterialProperties[parts.size()];
		int registered = 0;
		for (final VertexArray part : parts) {
			final int vao = NativeModelAccess.getVertexArrayId(part);
			final int indexCount = NativeModelAccess.getIndexCount(part);
			final MaterialProperties material = NativeModelAccess.getMaterialProperties(part);
			final int shaderType = material == null ? 0 : material.shaderType.ordinal();
			final int handle = drawList.registerBatch(vao, indexCount, shaderType);
			if (handle >= 0) {
				handles[registered] = handle;
				materials[registered] = material;
				registered++;
			}
		}

		final ModelBatches result;
		if (registered == parts.size()) {
			result = new ModelBatches(handles, materials, registered);
		} else {
			// A short registration means a vertex array could not be registered; keeping the
			// model would leave holes in it, so it is dropped and reported.
			Init.LOGGER.warn("[wrgl] only {} of {} vertex arrays registered for a model; "
					+ "the model will not be drawn natively", registered, parts.size());
			result = ModelBatches.EMPTY;
		}

		batchesByModel.put(model, result);
		return result;
	}

	/** Releases the native draw list. Called when the client shuts down. */
	public void close() {
		if (nativeDrawList != null) {
			nativeDrawList.close();
		}
		batchesByModel.clear();
		queue.clear();
		flattened.clear();
		materialRuns.clear();
	}

	// -----------------------------------------------------------------------
	// Per-frame records. Plain classes rather than records, because this branch targets a
	// Java 17 toolchain for 1.20.1 and records would be fine there, but the surrounding code
	// is written in the older style and mixing the two reads badly.
	// -----------------------------------------------------------------------

	/** The registered batches of one model, with the material of each. */
	private static final class ModelBatches {

		static final ModelBatches EMPTY = new ModelBatches(new int[0], new MaterialProperties[0], 0);

		final int[] handles;
		final MaterialProperties[] materials;
		final int count;

		ModelBatches(int[] handles, MaterialProperties[] materials, int count) {
			this.handles = handles;
			this.materials = materials;
			this.count = count;
		}
	}

	/** One model queued this frame. */
	private static final class QueuedModel {

		final ModelBatches batches;
		final float[] pose;
		final int light;
		final int color;

		QueuedModel(ModelBatches batches, float[] pose, int light, int color) {
			this.batches = batches;
			this.pose = pose;
			this.light = light;
			this.color = color;
		}
	}

	/** One batch to draw this frame. */
	private static final class BatchDraw {

		final int batch;
		@Nullable
		final MaterialProperties material;
		final float[] pose;
		final int light;
		final int color;

		BatchDraw(int batch, @Nullable MaterialProperties material, float[] pose, int light, int color) {
			this.batch = batch;
			this.material = material;
			this.pose = pose;
			this.light = light;
			this.color = color;
		}
	}

	/** A run of consecutive batches sharing one material. */
	private static final class MaterialRun {

		final int start;
		final int end;
		@Nullable
		final MaterialProperties material;

		MaterialRun(int start, int end, @Nullable MaterialProperties material) {
			this.start = start;
			this.end = end;
			this.material = material;
		}
	}
}
