package org.mtr.mod.resource.nativeobj;

/**
 * One material group key reported by the native OBJ parser.
 *
 * <p>Mirrors {@code WrObjKeyInfo} in native/include/wrobj/wrobj.hpp and exists so the Java
 * side can resolve texture identity before any geometry is built. The field values are the
 * reference loader's own decisions - shader type from {@code legacyMapping}, the texture
 * string from the three-way {@code materials.isEmpty()} / material-missing / {@code map_Kd}
 * rule - so nothing here is recomputed on the Java side.</p>
 */
public final class NativeObjKey {

	/** Ordinal matching {@code OptimizedModel.ShaderType}. */
	public final int shaderType;

	/** The material group name exactly as it appears in the OBJ. */
	public final String key;

	/** The texture string to resolve; empty means the caller should use its fallback. */
	public final String textureString;

	/**
	 * Pre-truncated packed colour from the MTL's {@code Kd} and {@code d}, or
	 * {@code 0xFFFFFFFF} when the material carried no {@code Kd}.
	 *
	 * <p>Note the channel order, which is MTR's rather than the usual ARGB one: red occupies
	 * the most significant byte and alpha the least significant. See
	 * {@code native/tools/ColorReference.java} for the reference values.</p>
	 */
	public final int color;

	/** True when a material was found for this key <em>and</em> it carried a {@code Kd}. */
	public final boolean hasColor;

	/** True when the key's options requested a V flip. */
	public final boolean flipTextureV;

	public NativeObjKey(int shaderType, String key, String textureString, int color, boolean hasColor, boolean flipTextureV) {
		this.shaderType = shaderType;
		this.key = key;
		this.textureString = textureString;
		this.color = color;
		this.hasColor = hasColor;
		this.flipTextureV = flipTextureV;
	}

	@Override
	public String toString() {
		return String.format("NativeObjKey[%s shader=%d texture=\"%s\" color=0x%08X hasColor=%s]",
				key, shaderType, textureString, color, hasColor);
	}
}
