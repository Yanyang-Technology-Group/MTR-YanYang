/**
 * Byte-exact reference for the texture-string normalisation MTR performs before
 * resolving a texture.
 *
 * ModelResourceLoader passes this lambda to OptimizedModel.ObjModel.loadModel, and
 * ObjModelLoader calls it with the raw texture string:
 *
 *     textureString -> ... getResourceFromSamePath(modelResource, textureString, "png")
 *     ... and inside ObjModelLoader:
 *     textureResolver.apply(texture.replace("\\\\", "/").replace("\\", "/"))
 *
 * The two replace() calls look like they collapse a doubled backslash to one slash.
 * That matters because this string becomes the resolved path, and the resolved path is
 * part of MaterialProperties' identity. If the native side folded a doubled backslash
 * into two slashes instead of one, two materials that the Java pipeline merges would
 * land in separate buckets and render as duplicated geometry.
 *
 * This program prints what the JVM actually does, so the native implementation can be
 * matched to it rather than to a reading of it.
 */
public final class TexturePathReference {

	/** Verbatim copy of the expression ObjModelLoader uses. */
	private static String normaliseLikeMtr(String texture) {
		return texture.replace("\\\\", "/").replace("\\", "/");
	}

	/** Mirrors CustomResourceTools.formatIdentifierString. */
	private static String formatIdentifierString(String text) {
		final StringBuilder builder = new StringBuilder();
		boolean first = true;
		for (final String part : text.toLowerCase(java.util.Locale.ENGLISH).split(":")) {
			if (!first) {
				builder.append(':');
			}
			first = false;
			builder.append(part.replaceAll("[^a-z0-9/._-]", "_"));
		}
		return builder.toString();
	}

	/** Mirrors CustomResourceTools.getResourceFromSamePath for a non-namespaced resource. */
	private static String resolve(String basePath, String resource) {
		if (resource.contains(":")) {
			final String formatted = formatIdentifierString(resource);
			return formatted.isEmpty() ? "mtr:textures/block/transparent.png"
					: formatted.split("\\.")[0] + ".png";
		}
		final String[] split = basePath.split("/");
		split[split.length - 1] = resource;
		final String formatted = formatIdentifierString(String.join("/", split));
		return formatted.isEmpty() ? "mtr:textures/block/transparent.png"
				: formatted.split("\\.")[0] + ".png";
	}

	public static void main(String[] args) {
		System.out.println("java.version = " + System.getProperty("java.version"));
		System.out.println();
		System.out.println("normaliseLikeMtr, i.e. texture.replace(\"\\\\\\\\\", \"/\").replace(\"\\\\\", \"/\")");
		probe("textures\\body.png");
		probe("textures\\\\body.png");
		probe("textures\\\\\\body.png");
		probe("textures\\\\\\\\body.png");
		probe("a\\b\\c.png");
		probe("a\\\\b\\\\c.png");
		probe("plain.png");
		probe("dir/sub/plain.png");
		probe("UPPER/Case.PNG");
		probe("\\leading.png");
		probe("trailing\\");

		System.out.println();
		System.out.println("full resolution (modelResource = train.obj)");
		probeResolved("textures\\\\body.png");
		probeResolved("textures\\body.png");
		probeResolved("UPPER/Thing.PNG");
		probeResolved("mtr:custom/thing.jpg");
	}

	private static void probe(String input) {
		System.out.printf("  %-24s -> \"%s\"%n", quote(input), normaliseLikeMtr(input));
	}

	private static void probeResolved(String input) {
		System.out.printf("  %-24s -> \"%s\"%n", quote(input),
				resolve("models/object/train.obj", normaliseLikeMtr(input)));
	}

	private static String quote(String value) {
		final StringBuilder builder = new StringBuilder("\"");
		for (final char character : value.toCharArray()) {
			if (character == '\\') {
				builder.append("\\\\");
			} else {
				builder.append(character);
			}
		}
		return builder.append('"').toString();
	}

	private TexturePathReference() {
	}
}
