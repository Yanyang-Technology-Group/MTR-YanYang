package org.mtr.mod.resource.nativeobj;

import javax.annotation.Nullable;
import java.util.Locale;
import java.util.function.Function;

/**
 * Resolves a material key produced by the native OBJ parser into a texture identity.
 *
 * <p>The split of responsibility is deliberate. The native library owns OBJ and MTL parsing
 * and all geometry, because that is where the allocation traffic is. Texture identity stays
 * on the Java side because in a mod it is a Minecraft {@code Identifier} that only Java can
 * construct. The native code therefore reports the exact texture string the reference loader
 * would have used, and this interface turns it into an identity.</p>
 *
 * <p>The returned handle is opaque to the native code. It is carried through the merge key
 * and handed back unchanged in the draw records, so a mesh whose material resolved
 * differently can never be merged with one that did not. That reproduces the guarantee
 * {@code MaterialProperties.equals} provides, which is why the shader type is part of the
 * lookup: two keys can share a texture string and still be different materials.</p>
 */
@FunctionalInterface
public interface NativeTextureResolver {

	/** The handle used when a material group has no usable texture. */
	int NO_TEXTURE = -1;

	/**
	 * Maps a resolved material key to a texture identity.
	 *
	 * @param textureString the texture string the reference loader would resolve, already
	 *                      lower-cased with forward slashes; empty when the material group
	 *                      declared no {@code map_Kd} and is not itself a texture name
	 * @param shaderType    the {@code OptimizedModel.ShaderType} ordinal for this key
	 * @return an opaque handle, or {@link #NO_TEXTURE} when the caller should fall back
	 */
	int resolve(String textureString, int shaderType);

	/**
	 * Builds a resolver backed by a fixed lookup table.
	 *
	 * <p>Used by the equivalence harness, where the "identity" is the resolved string itself
	 * rather than a real texture.</p>
	 */
	static NativeTextureResolver forTable(Function<String, Integer> tableLookup) {
		return (textureString, shaderType) -> {
			final Integer handle = tableLookup.apply(textureString == null
					? "" : textureString.toLowerCase(Locale.ROOT));
			return handle == null ? NO_TEXTURE : handle;
		};
	}

	/** A resolver that reports every texture as unresolved. */
	static NativeTextureResolver unresolved() {
		return (textureString, shaderType) -> NO_TEXTURE;
	}

	/** Null-safe accessor, so callers do not repeat the check. */
	static int resolveOrFallback(@Nullable NativeTextureResolver resolver, String textureString, int shaderType) {
		return resolver == null ? NO_TEXTURE : resolver.resolve(textureString, shaderType);
	}
}
