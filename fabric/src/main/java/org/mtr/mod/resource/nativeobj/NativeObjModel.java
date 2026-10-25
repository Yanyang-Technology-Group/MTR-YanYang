package org.mtr.mod.resource.nativeobj;

import org.mtr.mapping.holder.Identifier;
import org.mtr.mapping.mapper.OptimizedModel;

import javax.annotation.Nullable;

/**
 * The geometry of one OBJ model, built by the native pipeline instead of the Java one.
 *
 * <p>This is the counterpart of {@code OptimizedModel.ObjModel}, exposing exactly the
 * operations MTR performs on it:</p>
 *
 * <ul>
 *   <li>{@link #copy()} - {@code ObjModel.copy()}, used by {@code ModelPropertiesPart}'s
 *       per-part isolation fix so one part's placements cannot leak into another part's
 *       condition bucket.</li>
 *   <li>{@link #addTransformation} - {@code ObjModel.addTransformation}, called once per
 *       vehicle part position and therefore the hottest entry point in the model
 *       pipeline.</li>
 *   <li>{@link #getMinX()} and friends - the bounding box, which MTR turns into the part's
 *       collision and interaction box, so it has to track the {@code ObjectResource}-level
 *       transforms exactly as the Java path does.</li>
 * </ul>
 *
 * <p><b>Ownership.</b> A model owns one native builder handle and every copy owns its own.
 * Handles hold native memory, so they must be released. {@link #close()} does that, and every
 * placement builder releases its own handle once it has uploaded its geometry, which is the
 * bulk of the memory.</p>
 *
 * <p>What is deliberately <em>not</em> released is the source-mesh handle itself: it lives as
 * long as the {@code CachedResource} that produced this model, matching the Java path, where
 * {@code OptimizedModel.ObjModel} keeps its {@code rawMeshes} alive for the same span. The mod
 * has no model-unload hook to attach a release to, so this is a known boundary rather than an
 * oversight - see the "Known divergences" section of {@code native/README.md}.</p>
 */
public final class NativeObjModel implements AutoCloseable {

	/** Material group keys in native key order. Also the order of {@link #textures}. */
	private final NativeObjKey[] keys;

	/**
	 * {@code textureString + '\0' + shaderType} per key, in key order.
	 *
	 * <p>Used to answer the native side's texture lookup during a build: the native parser
	 * reports the string and shader type it derived, and the matching entry's position is the
	 * handle for that key.</p>
	 */
	private final String[] textureLookupKeys;

	/**
	 * The resolved textures in key order, or null at a position whose key has no usable
	 * texture.
	 *
	 * <p>The index into this array is the handle handed to the native code. Using the key
	 * index rather than a value derived from the {@link Identifier} keeps the handle stable
	 * and cheap to compare, which is all the native side needs it for.</p>
	 */
	private final Identifier[] textures;

	private final boolean materialGroupTextures;
	private final boolean flipTextureV;

	/** The raw OBJ and MTL bytes, kept so a copy can rebuild the source meshes. */
	private final byte[] objContent;

	@Nullable
	private final byte[] mtlContent;

	/** The native builder handle. Non-zero while the model is usable. */
	private volatile long handle;

	/** Set once the model has been released, so a copy from a closed model is detectable. */
	private volatile boolean closed;

	private float minX;
	private float minY;
	private float minZ;
	private float maxX;
	private float maxY;
	private float maxZ;

	/** Guards the lazy builder allocation, since a model can be copied from any thread. */
	private final Object lock = new Object();

	NativeObjModel(NativeObjKey[] keys, String[] textureLookupKeys, Identifier[] textures,
			boolean materialGroupTextures, boolean flipTextureV, byte[] objContent,
			@Nullable byte[] mtlContent) {
		this.keys = keys;
		this.textureLookupKeys = textureLookupKeys;
		this.textures = textures;
		this.materialGroupTextures = materialGroupTextures;
		this.flipTextureV = flipTextureV;
		this.objContent = objContent;
		this.mtlContent = mtlContent;
		refreshBounds();
	}

	// -----------------------------------------------------------------------
	// Data the builder needs
	// -----------------------------------------------------------------------

	/**
	 * The resolved textures in key order. {@link NativeObjModelBuilder} indexes this by the
	 * texture handle a draw record carries, which is the key index.
	 */
	Identifier[] getTextures() {
		return textures;
	}

	/** Material group keys, for diagnostics and for tests. */
	public NativeObjKey[] getKeys() {
		return keys;
	}

	/**
	 * Number of material group keys, i.e. the size of the texture table.
	 */
	public int getKeyCount() {
		return keys.length;
	}

	/**
	 * Builds a fresh staging builder over this model's source meshes.
	 *
	 * <p>One builder per model instance, because each instance accumulates its own
	 * placements. {@code ModelPropertiesPart} calls {@code copy()} per vehicle part, and every
	 * copy produces its own builder here.</p>
	 */
	public NativeObjModelBuilder createBuilder() {
		return new NativeObjModelBuilder(this);
	}

	/**
	 * The key index that owns a given {@code (textureString, shaderType)} pair, or
	 * {@link NativeTextureResolver#NO_TEXTURE}.
	 *
	 * <p>Exposed for tests and diagnostics; the native side reaches the same answer through
	 * {@link TableResolver}.</p>
	 */
	public int indexOfKey(String textureString, int shaderType) {
		final String wanted = (textureString == null ? "" : textureString) + '\u0000' + shaderType;
		for (int i = 0; i < textureLookupKeys.length; i++) {
			if (textureLookupKeys[i].equals(wanted)) {
				return i;
			}
		}
		return NativeTextureResolver.NO_TEXTURE;
	}

	boolean isClosed() {
		return closed;
	}

	/**
	 * The native builder handle, created if needed. Exposed to the package so
	 * {@link NativeObjModelBuilder} can build from it.
	 */
	long ensureHandleForBuild() {
		return ensureHandle();
	}

	// -----------------------------------------------------------------------
	// ObjModel surface
	// -----------------------------------------------------------------------

	/**
	 * {@code ObjModel.copy()}: an independent instance over the same source meshes with the
	 * same bounds. Placements added to one copy never appear in another.
	 */
	public NativeObjModel copy() {
		return new NativeObjModel(keys, textureLookupKeys, textures,
				materialGroupTextures, flipTextureV, objContent, mtlContent);
	}

	/**
	 * {@code ObjModel.addTransformation(shaderType, x, y, z, flipped)}.
	 *
	 * <p>Kept so this class is a drop-in for the Java path; the native side folds the
	 * translation, the optional Y-180 rotation and the shader override into one call
	 * internally.</p>
	 */
	public void addTransformation(OptimizedModel.ShaderType shaderType, double x, double y, double z, boolean flipped) {
		addPlacement(shaderType.ordinal(), x, y, z, flipped);
	}

	/** Appends a placed copy of every source mesh at the given position. */
	public void addPlacement(int shaderTypeOrdinal, double x, double y, double z, boolean flipped) {
		final long current = ensureHandle();
		if (current == 0L) {
			return;
		}
		NativeObjPipeline.addTransform(current, shaderTypeOrdinal, x, y, z, flipped);
	}

	public void applyTranslation(double x, double y, double z) {
		apply(0, x, y, z);
	}

	public void applyRotation(double x, double y, double z) {
		apply(1, x, y, z);
	}

	public void applyScale(double x, double y, double z) {
		apply(2, x, y, z);
	}

	public void applyMirror(boolean x, boolean y, boolean z) {
		apply(3, x ? 1 : 0, y ? 1 : 0, z ? 1 : 0);
	}

	private void apply(int operation, double a, double b, double c) {
		final long current = ensureHandle();
		if (current == 0L) {
			return;
		}
		NativeObjPipeline.applyTransform(current, operation, a, b, c);
		// The ObjectResource-level transforms move the source meshes, so the box MTR reads
		// for its part geometry has to follow. The native side reports the box over the
		// current source meshes, which is what the Java path measures.
		refreshBounds();
	}

	// -----------------------------------------------------------------------
	// Bounds
	// -----------------------------------------------------------------------

	public float getMinX() {
		return minX;
	}

	public float getMinY() {
		return minY;
	}

	public float getMinZ() {
		return minZ;
	}

	public float getMaxX() {
		return maxX;
	}

	public float getMaxY() {
		return maxY;
	}

	public float getMaxZ() {
		return maxZ;
	}

	/** Recomputes the bounding box from the current source meshes. */
	public void refreshBounds() {
		final long current = ensureHandle();
		if (current == 0L) {
			return;
		}
		final float[] bounds = NativeObjPipeline.getBounds(current);
		if (bounds == null || bounds.length < 6) {
			return;
		}
		minX = bounds[0];
		minY = bounds[1];
		minZ = bounds[2];
		maxX = bounds[3];
		maxY = bounds[4];
		maxZ = bounds[5];
	}

	// -----------------------------------------------------------------------
	// Lifetime
	// -----------------------------------------------------------------------

	/**
	 * The object's own builder handle, created on first use.
	 *
	 * <p>Creation is lazy because {@code ObjModel.copy()} is called once per vehicle part and
	 * many of those copies exist only to be measured and then dropped. Deferring the source
	 * mesh construction until something genuinely needs it keeps the cost proportional to
	 * real use.</p>
	 */
	private long ensureHandle() {
		long current = handle;
		if (current == 0L && !closed) {
			synchronized (lock) {
				if (handle == 0L && !closed) {
					handle = createHandle();
				}
				current = handle;
			}
		}
		return current;
	}

	private long createHandle() {
		return NativeObjPipeline.createBuilder(objContent, mtlContent, materialGroupTextures,
				flipTextureV, new TableResolver(textureLookupKeys));
	}

	/** Releases the native builder. Idempotent. */
	@Override
	public void close() {
		synchronized (lock) {
			closed = true;
			if (handle != 0L) {
				NativeObjPipeline.freeBuilder(handle);
				handle = 0L;
			}
		}
	}

	// -----------------------------------------------------------------------
	// Support types
	// -----------------------------------------------------------------------

	/**
	 * Answers the native side's texture lookup from the table built when the model was
	 * created.
	 *
	 * <p>The native parser reports the texture string and shader type it derived, in the same
	 * order the table was built, so the pair is enough to recover the key index. The pair
	 * rather than the string alone, because two material groups can share a texture and still
	 * be distinct materials - {@code MaterialProperties} compares the shader type too.</p>
	 *
	 * <p>A key is resolved once per build, over a table of at most a few dozen entries, so a
	 * linear scan costs less than maintaining a map.</p>
	 */
	private static final class TableResolver implements NativeTextureResolver {

		private final String[] lookupKeys;

		TableResolver(String[] lookupKeys) {
			this.lookupKeys = lookupKeys;
		}

		@Override
		public int resolve(String textureString, int shaderType) {
			final String wanted = (textureString == null ? "" : textureString) + '\u0000' + shaderType;
			for (int i = 0; i < lookupKeys.length; i++) {
				if (lookupKeys[i].equals(wanted)) {
					// The key index is the handle. It is stable, cheap to compare, and lets the
					// draw records name their texture without another lookup.
					return i;
				}
			}
			// A key outside the table still has real geometry, so it keeps a handle of its own
			// rather than collapsing onto the no-texture fallback.
			return NO_TEXTURE;
		}

		@Override
		public String toString() {
			return "TableResolver[" + lookupKeys.length + " key(s)]";
		}
	}
}
