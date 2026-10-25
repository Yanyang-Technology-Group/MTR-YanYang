import java.util.Objects;

/**
 * Byte-exact reference probe for the ARGB packing used by
 * org.mtr.mapping.render.obj.ObjModelLoader.
 *
 * The native rewrite has to reproduce this exactly, and the packing is easy to
 * get subtly wrong because JOML keeps MTL values as boxed Float and MTR truncates
 * with a plain (int) cast. This program prints what the JDK actually produces, so
 * the expected values in the native tests come from the platform rather than from
 * a reading of the source.
 */
public final class ColorReference {

	/** Verbatim copy of ObjModelLoader.mergeColor. */
	private static int mergeColor(int r, int g, int b, int a) {
		return r << 24 | g << 16 | b << 8 | a;
	}

	/**
	 * Verbatim copy of ObjModelLoader's Kd/d handling.
	 *
	 * Float kdX come from Mtl.getKd() (nullable), Float d from Mtl.getD() which
	 * returns 1.0F when unset. The casts are the ones the mod performs.
	 */
	private static int colorFromKd(Float kdX, Float kdY, Float kdZ, Float d) {
		if (kdX == null) {
			return mergeColor(255, 255, 255, 255);
		}
		final int r = (int) (kdX * 255.0F);
		final int g = (int) (kdY * 255.0F);
		final int b = (int) (kdZ * 255.0F);
		final int a = d == null ? 255 : (int) (d * 255.0F);
		return mergeColor(r, g, b, a);
	}

	public static void main(String[] args) {
		System.out.println("java.version = " + System.getProperty("java.version"));

		System.out.println();
		System.out.println("mergeColor channel mapping");
		System.out.printf("  mergeColor(255, 0, 0, 255)   = 0x%08X%n", mergeColor(255, 0, 0, 255));
		System.out.printf("  mergeColor(255, 0, 0, 0)     = 0x%08X%n", mergeColor(255, 0, 0, 0));
		System.out.printf("  mergeColor(0, 0, 255, 255)   = 0x%08X%n", mergeColor(0, 0, 255, 255));
		System.out.printf("  mergeColor(255, 255, 255, 255) = 0x%08X%n", mergeColor(255, 255, 255, 255));

		System.out.println();
		System.out.println("Kd/d truncation");
		probe("Kd 1 0 0, d unset", 1.0F, 0.0F, 0.0F, null);
		probe("Kd 0 0 1, d 0.5", 0.0F, 0.0F, 1.0F, 0.5F);
		probe("Kd 0.5 0.25 0.75, d unset", 0.5F, 0.25F, 0.75F, null);
		probe("Kd 0.1 0.2 0.3, d 0", 0.1F, 0.2F, 0.3F, 0.0F);
		probe("Kd 0.999 0.5 0.001, d 0.999", 0.999F, 0.5F, 0.001F, 0.999F);

		System.out.println();
		System.out.println("unsigned interpretation of the packed values");
		System.out.printf("  (255,0,0,255) as unsigned = %d (0x%08X)%n",
				mergeColor(255, 0, 0, 255), mergeColor(255, 0, 0, 255));
		System.out.println("  Objects.hash of the same = "
				+ Objects.hash(mergeColor(255, 0, 0, 255)));
	}

	private static void probe(String label, Float kdX, Float kdY, Float kdZ, Float d) {
		final int packed = colorFromKd(kdX, kdY, kdZ, d);
		System.out.printf("  %-28s -> 0x%08X  (r=%d g=%d b=%d a=%d)%n",
				label, packed,
				(packed >>> 24) & 0xFF, (packed >>> 16) & 0xFF, (packed >>> 8) & 0xFF, packed & 0xFF);
	}

	private ColorReference() {
	}
}
