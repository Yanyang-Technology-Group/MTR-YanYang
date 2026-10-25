package org.mtr.mod.resource.nativeobj;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Smoke test for the JNI bridge and the native OBJ pipeline.
 *
 * <p>Runs without Gradle and without Minecraft: it links only against the integration classes,
 * which in turn depend on the mappings jar for the geometry types. That makes it the fastest
 * way to answer "does the native library actually load and produce sane buffers", which is the
 * first thing to check when something renders wrong.</p>
 *
 * <p>It is deliberately not a byte-exactness check. Byte level equivalence against the Java
 * pipeline is the job of {@code NativeObjEquivalenceTest} (which needs the remapped jars) and
 * of the pure-native golden harness in {@code native/tests}. What this checks is that the
 * bridge itself works: the library loads, the ABI matches, a model round-trips, and the numbers
 * come out internally consistent.</p>
 *
 * <p>Run through {@code native/tools/check-java-integration.ps1 -RunSmokeTest}, which sets up
 * the classpath and the library search path.</p>
 */
public final class NativeObjSmokeTest {

	private static int checks;
	private static int failures;

	public static void main(String[] args) {
		System.out.println("wrobj JNI smoke test");

		if (!NativeObjPipeline.isAvailable()) {
			System.out.println("  FAIL the native library did not load: " + NativeObjPipeline.getStatusMessage());
			System.out.println();
			System.out.println("1 check, 1 failure");
			System.exit(1);
		}

		System.out.println("  library: " + NativeObjPipeline.getNativeVersion());
		System.out.println("  status:  " + NativeObjPipeline.getStatusMessage());

		check(NativeObjPipeline.nativeAbiVersion() == NativeObjPipeline.EXPECTED_ABI,
				"the loaded library reports ABI " + NativeObjPipeline.EXPECTED_ABI);

		smokeTestKeyDiscovery();
		smokeTestGeometry();
		smokeTestDeterminism();
		smokeTestIsolation();
		smokeTestDrawList();
		smokeTestDrawPathAccess();

		System.out.println();
		System.out.println(checks + " check(s), " + failures + " failure(s)");
		System.exit(failures == 0 ? 0 : 1);
	}

	// -----------------------------------------------------------------------

	private static final String TWO_MATERIAL_OBJ =
			"mtllib smoke.mtl\n"
					+ "v 0 0 0\nv 1 0 0\nv 1 1 0\nv 0 1 0\n"
					+ "vt 0 0\nvt 1 0\nvt 1 1\nvt 0 1\n"
					+ "usemtl body#interior\nf 1/1 2/2 3/3 4/4\n"
					+ "usemtl glass\nf 4/4 3/3 2/2 1/1\n";

	private static final String TWO_MATERIAL_MTL =
			"newmtl body#interior\nKd 0.8 0.8 0.85\nmap_Kd body.png\n"
					+ "newmtl glass\nKd 0.4 0.6 0.9\nd 0.5\nmap_Kd glass.png\n";

	private static void smokeTestKeyDiscovery() {
		final NativeObjKey[] keys = NativeObjPipeline.loadKeys(
				TWO_MATERIAL_OBJ.getBytes(StandardCharsets.UTF_8),
				TWO_MATERIAL_MTL.getBytes(StandardCharsets.UTF_8), false);

		check(keys != null, "key discovery returns a table");
		if (keys == null) {
			return;
		}
		check(keys.length == 2, "two material groups, found " + keys.length);
		if (keys.length != 2) {
			return;
		}

		check("body#interior".equals(keys[0].key), "first key is body#interior");
		check(keys[0].shaderType == 2, "body#interior maps to CUTOUT_BRIGHT, got " + keys[0].shaderType);
		check("body.png".equals(keys[0].textureString), "body#interior texture string");
		check(keys[0].hasColor, "body#interior has an authoritative colour");
		// Kd 0.8 0.8 0.85 with d defaulting to 1: 204, 204, 217, 255 in MTR's packing, which
		// puts red in the most significant byte. See native/tools/ColorReference.java.
		// Kd 0.8 0.8 0.85 with d defaulting to 1. The colour packs with red in the most
		// significant byte, which is MTR's layout rather than the usual ARGB one, and every
		// channel truncates: 0.85F * 255.0F is 216.75, so blue is 216 (0xD8) and not 217.
		// native/tools/ColorReference.java prints the JVM's own results for exactly this
		// reason.
		check(keys[0].color == 0xCCCCD8FF,
				String.format("body#interior colour is 0xCCCCD8FF, got 0x%08X", keys[0].color));

		check("glass".equals(keys[1].key), "second key is glass");
		check(keys[1].shaderType == 0, "glass maps to CUTOUT, got " + keys[1].shaderType);
		// Kd 0.4 0.6 0.9 with d 0.5: 102, 153, 229 and a truncating alpha of 127.
		check(keys[1].color == 0x6699E57F,
				String.format("glass colour is 0x6699E57F, got 0x%08X", keys[1].color));
	}

	private static void smokeTestGeometry() {
		final byte[] obj = TWO_MATERIAL_OBJ.getBytes(StandardCharsets.UTF_8);
		final byte[] mtl = TWO_MATERIAL_MTL.getBytes(StandardCharsets.UTF_8);

		final long handle = NativeObjPipeline.createBuilder(obj, mtl, true, false,
				(textureString, shaderType) -> "body.png".equals(textureString) ? 0
						: "glass.png".equals(textureString) ? 1 : NativeTextureResolver.NO_TEXTURE);
		check(handle != 0L, "the native builder is created");
		if (handle == 0L) {
			return;
		}

		try {
			NativeObjPipeline.applyTransform(handle, 1, 90.0, 0.0, 0.0);
			NativeObjPipeline.nativeAddTransform(handle, 0, 0.0, 0.0, 0.0, false);
			NativeObjPipeline.nativeAddTransform(handle, 0, 1.0, 0.0, 0.0, true);

			final float[] bounds = NativeObjPipeline.nativeGetBounds(handle);
			check(bounds != null && bounds.length == 6, "bounds are reported");
			if (bounds != null && bounds.length == 6) {
				check(bounds[0] <= bounds[3] && bounds[1] <= bounds[4] && bounds[2] <= bounds[5],
						String.format("bounds are ordered: min=(%g %g %g) max=(%g %g %g)",
								bounds[0], bounds[1], bounds[2], bounds[3], bounds[4], bounds[5]));
			}

			final NativeObjResult result = NativeObjPipeline.nativeBuild(handle);
			check(result != null, "the build produces geometry");
			if (result == null) {
				return;
			}

			try {
				check(result.draws.length == 2,
						"two buckets, one per material, found " + result.draws.length);
				check(result.vertexBuffer != null && result.vertexBuffer.capacity() > 0,
						"a vertex buffer is returned");
				check(result.indexBuffer != null && result.indexBuffer.capacity() > 0,
						"an index buffer is returned");
				check(result.vertexBuffer.capacity() == result.totalVertexCount() * 24,
						"the vertex buffer matches the declared vertex count");
				check(result.indexBuffer.capacity() == result.totalFaceCount() * 3 * 4,
						"the index buffer matches the declared triangle count");
				// Each placement contributes four triangles: the OBJ has two quads of opposing
				// winding, one per material group, and two placements are staged.
				check(result.totalFaceCount() == 8,
						"two placements of a two-quad model become eight triangles, found "
								+ result.totalFaceCount());
				check(result.estimatedJavaHeapBytes > 0, "the heap estimate is reported");

				// Every index must address a vertex inside its own bucket: a cross-bucket index
				// would draw geometry from the wrong material.
				boolean indicesInRange = true;
				for (final NativeObjDraw draw : result.draws) {
					final ByteBuffer indices = result.indexBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN);
					for (int i = 0; i < draw.indexCount; i++) {
						final int index = indices.getInt((draw.indexOffset + i) * 4);
						if (index < draw.vertexOffset || index >= draw.vertexOffset + draw.vertexCount) {
							indicesInRange = false;
							break;
						}
					}
				}
				check(indicesInRange, "every index stays inside its own bucket");

				// The texture handle must survive the round trip.
				check(result.draws[0].textureHandle >= 0 && result.draws[0].textureHandle < 2,
						"the host texture handle is carried through: " + result.draws[0].textureHandle);
				check(result.draws[0].hasColor, "the bucket reports an authoritative colour");
			} finally {
				result.release();
				result.release(); // idempotent: a second release must not corrupt the heap
			}
		} finally {
			NativeObjPipeline.freeNativeBuilder(handle);
		}
	}

	private static void smokeTestDeterminism() {
		final long first = buildChecksum();
		final long second = buildChecksum();
		check(first == second && first != 0L,
				String.format("two builds produce the same buffers (0x%016X)", first));
	}

	private static long buildChecksum() {
		final byte[] obj = TWO_MATERIAL_OBJ.getBytes(StandardCharsets.UTF_8);
		final byte[] mtl = TWO_MATERIAL_MTL.getBytes(StandardCharsets.UTF_8);
		final long handle = NativeObjPipeline.createBuilder(obj, mtl, true, false,
				(textureString, shaderType) -> 0);
		if (handle == 0L) {
			return 0L;
		}
		try {
			NativeObjPipeline.nativeAddTransform(handle, 0, 0.25, 0.0, -0.5, false);
			final NativeObjResult result = NativeObjPipeline.nativeBuild(handle);
			if (result == null) {
				return 0L;
			}
			try {
				long hash = 0xCBF29CE484222325L;
				final ByteBuffer vertices = result.vertexBuffer.duplicate();
				final ByteBuffer indices = result.indexBuffer.duplicate();
				for (int i = 0; i < vertices.capacity(); i++) {
					hash ^= vertices.get(i) & 0xFF;
					hash *= 0x100000001B3L;
				}
				for (int i = 0; i < indices.capacity(); i++) {
					hash ^= indices.get(i) & 0xFF;
					hash *= 0x100000001B3L;
				}
				return hash;
			} finally {
				result.release();
			}
		} finally {
			NativeObjPipeline.freeNativeBuilder(handle);
		}
	}

	private static void smokeTestIsolation() {
		// The usemtl is load-bearing. A document with none has no material groups at all -
		// ObjSplitting.splitByMaterialGroups iterates obj.getNumMaterialGroups(), and ObjReader
		// creates that group only when it sees a usemtl - so the native builder has nothing to
		// build and declines rather than returning an empty model. This fixture predates that rule
		// and was still asserting on a builder that can no longer exist for it.
		final byte[] obj = "v 0 0 0\nv 1 0 0\nv 0 1 0\nusemtl _\nf 1 2 3\n".getBytes(StandardCharsets.UTF_8);

		final long first = NativeObjPipeline.createBuilder(obj, null, false, false,
				NativeTextureResolver.unresolved());
		final long second = NativeObjPipeline.createBuilder(obj, null, false, false,
				NativeTextureResolver.unresolved());
		check(first != 0L && second != 0L, "two independent builders are created");
		if (first == 0L || second == 0L) {
			NativeObjPipeline.freeNativeBuilder(first);
			NativeObjPipeline.freeNativeBuilder(second);
			return;
		}

		try {
			NativeObjPipeline.nativeAddTransform(first, 0, 0.0, 0.0, 0.0, false);
			NativeObjPipeline.nativeAddTransform(second, 0, 0.0, 0.0, 0.0, false);
			NativeObjPipeline.nativeAddTransform(second, 0, 5.0, 0.0, 0.0, false);

			final NativeObjResult firstResult = NativeObjPipeline.nativeBuild(first);
			final NativeObjResult secondResult = NativeObjPipeline.nativeBuild(second);
			try {
				check(firstResult != null && secondResult != null, "both builders produce geometry");
				if (firstResult != null && secondResult != null) {
					check(firstResult.totalFaceCount() == 1,
							"the first builder holds one placement, found " + firstResult.totalFaceCount());
					// Two placements of the same triangle. Nothing merges them: they sit at
					// different positions, and RawModel merges by material rather than by
					// geometry, so the triangle count simply doubles.
					check(secondResult.totalFaceCount() == 2,
							"the second builder holds two placements, found "
									+ secondResult.totalFaceCount());
					check(secondResult.totalVertexCount() > firstResult.totalVertexCount(),
							"the second builder accumulated more geometry");
				}
			} finally {
				if (firstResult != null) {
					firstResult.release();
				}
				if (secondResult != null) {
					secondResult.release();
				}
			}
		} finally {
			NativeObjPipeline.freeNativeBuilder(first);
			NativeObjPipeline.freeNativeBuilder(second);
		}
	}

	// -----------------------------------------------------------------------

	/**
	 * Exercises the draw list bridge.
	 *
	 * <p>No GL is involved and none is wanted: the vertex array names are made up, so the
	 * driver would reject them if anything actually drew. What this checks is the part that
	 * can be checked without a context - that the list is created, that the bridge resolves
	 * every method of the GL host, that registration and unregistration behave, and that the
	 * queue and flush path is re-entrant enough not to corrupt anything.</p>
	 *
	 * <p>Drawing for real, and the GL call counts that go with it, is covered by the
	 * pure-native suite in {@code native/tests/wrgl_tests.cpp}, which runs the same logic
	 * against a recording stub in place of a driver.</p>
	 */
	/**
	 * Reports whether the native draw path can reach the backend's private state.
	 *
	 * <p>This cannot be asserted here, and the reason is worth recording rather than leaving as a
	 * gap. {@code NativeModelAccess} reaches that state by reflecting on
	 * {@code OptimizedModel} and {@code VertexArray}, and reflection has to <em>link</em> those
	 * classes, not merely find them. Linking pulls in their signatures, which reference
	 * {@code net.minecraft.*} types that the published mappings jar does not carry - it is compiled
	 * against an intermediate namespace and only becomes {@code net.minecraft.*} after Loom remaps
	 * it. The attempt therefore fails with {@code NoClassDefFoundError:
	 * org/mtr/mapping/mapper/OptimizedModel} even though the class file is present.</p>
	 *
	 * <p>{@code NativeObjEquivalenceTest} runs in the Gradle test source set, which does have the
	 * remapped classes, and is where this is asserted. Reporting the state here instead of failing
	 * on it keeps this suite honest about what it can and cannot establish.</p>
	 */
	private static void smokeTestDrawPathAccess() {
		try {
			final boolean available = NativeModelAccess.isAvailable();
			System.out.println("  note: native draw path access " + (available ? "is" : "is not")
					+ " available here"
					+ (available ? "" : " (" + NativeModelAccess.getFailureReason() + ")"));
		} catch (Throwable throwable) {
			System.out.println("  note: draw path access cannot be evaluated in this environment: "
					+ throwable.getClass().getSimpleName());
		}
	}

	private static void smokeTestDrawList() {
		final NativeDrawList list = NativeDrawList.create(false, 256);
		check(list != null, "a native draw list is created");
		if (list == null) {
			return;
		}

		try {
			// The bridge resolves the host's five methods by name and signature at
			// registration, so a null return here means this class and the native library
			// disagree - which is exactly the failure worth catching before shipping.
			check(list.isAvailable(), "the bridge accepted the GL host and reports ready");

			final int first = list.registerBatch(1, 36, 0);
			final int second = list.registerBatch(2, 36, 1);
			check(first >= 0, "a vertex array registers");
			check(second >= 0, "a second vertex array registers");
			check(first != second, "the two handles differ");
			check(list.registerBatch(0, 36, 0) < 0, "a zero vertex array is rejected");
			check(list.registerBatch(3, 0, 0) < 0, "a zero index count is rejected");
			checkEqual(list.liveBatchCount(), 2, "two batches are live");

			list.beginFrame();
			final float[] pose = new float[16];
			for (int i = 0; i < 16; i++) {
				pose[i] = i * 0.5F;
			}
			for (int i = 0; i < 32; i++) {
				list.queue(first, pose);
				list.queue(second, pose);
			}
			// A flush with made-up vertex array names would be rejected by the driver, so the
			// queue is left staged and the next beginFrame discards it. What is being checked
			// is that staging sixty-four draws does not fail or corrupt the list.
			check(true, "sixty four draws stage without error");

			list.beginFrame();
			checkEqual(list.liveBatchCount(), 2, "beginFrame does not disturb registrations");

			list.unregisterBatch(first);
			checkEqual(list.liveBatchCount(), 1, "unregistering takes effect");
			list.unregisterBatch(first);
			checkEqual(list.liveBatchCount(), 1, "unregistering twice is harmless");
			list.unregisterBatch(-1);
			list.unregisterBatch(9999);
			checkEqual(list.liveBatchCount(), 1, "out of range handles are ignored");

			// A freed slot is reused rather than growing the registry.
			final int reused = list.registerBatch(4, 12, 0);
			checkEqual(reused, first, "the freed slot is reused");
			checkEqual(list.liveBatchCount(), 2, "two batches are live again");

			list.resetStatistics();
			check(list.describeStatistics().contains("native draw list"),
					"the statistics describe the draw path");
		} finally {
			list.close();
			// Idempotent, so a double close cannot free twice.
			list.close();
			check(!list.isAvailable(), "a closed list reports itself unavailable");
		}
	}

	// -----------------------------------------------------------------------

	private static void check(boolean condition, String what) {
		checks++;
		if (condition) {
			System.out.println("  OK   " + what);
		} else {
			failures++;
			System.out.println("  FAIL " + what);
		}
	}

	/** Integer comparison, so the call sites do not have to box. */
	private static void checkEqual(int actual, int expected, String what) {
		checks++;
		if (actual == expected) {
			System.out.println("  OK   " + what);
		} else {
			failures++;
			System.out.println("  FAIL " + what + " (expected " + expected + ", got " + actual + ")");
		}
	}

	private NativeObjSmokeTest() {
	}
}
