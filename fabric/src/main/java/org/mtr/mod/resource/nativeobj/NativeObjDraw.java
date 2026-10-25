package org.mtr.mod.resource.nativeobj;

/**
 * One material bucket of a native model build.
 *
 * <p>Mirrors {@code WrObjResultDraw} in native/include/wrobj/wrobj.hpp. A bucket becomes
 * one {@code VertexArray} on the Java side, and the whole set of buckets is what
 * {@code OptimizedModel.fromObjModels} would have produced from the Java pipeline - in the
 * same order, because both derive it from the same ordered merge.</p>
 */
public final class NativeObjDraw {

	/** Baseline offset into the interleaved vertex buffer, in vertices. */
	public final int vertexOffset;

	/** Number of vertices in this bucket. */
	public final int vertexCount;

	/** Baseline offset into the index buffer, in indices. */
	public final int indexOffset;

	/** Number of indices in this bucket, always three per triangle. */
	public final int indexCount;

	/** Ordinal matching {@code OptimizedModel.ShaderType}. */
	public final int shaderType;

	/**
	 * Packed colour in MTR's layout, i.e. red in the most significant byte and alpha in
	 * the least significant one. Only meaningful when {@link #hasColor} is true.
	 */
	public final int color;

	/** True when {@link #color} is authoritative rather than the global shader colour. */
	public final boolean hasColor;

	/** Opaque texture handle the host returned for this bucket's material key. */
	public final int textureHandle;

	/**
	 * Index into the key list returned by {@link NativeObjPipeline#loadKeys}, so the caller
	 * can recover the material group name and texture string for this bucket. {@code -1}
	 * when the bucket could not be attributed, which only happens for a malformed model.
	 */
	public final int keyIndex;

	public NativeObjDraw(int shaderType, int color, int textureHandle, boolean hasColor, int keyIndex,
			int vertexOffset, int vertexCount, int indexOffset, int indexCount) {
		this.shaderType = shaderType;
		this.color = color;
		this.textureHandle = textureHandle;
		this.hasColor = hasColor;
		this.keyIndex = keyIndex;
		this.vertexOffset = vertexOffset;
		this.vertexCount = vertexCount;
		this.indexOffset = indexOffset;
		this.indexCount = indexCount;
	}

	@Override
	public String toString() {
		return String.format(
				"NativeObjDraw[shader=%d color=0x%08X hasColor=%s texture=%d key=%d "
						+ "vertices=%d@%d indices=%d@%d]",
				shaderType, color, hasColor, textureHandle, keyIndex,
				vertexCount, vertexOffset, indexCount, indexOffset);
	}
}
