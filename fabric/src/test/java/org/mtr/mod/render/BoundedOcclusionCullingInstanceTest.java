package org.mtr.mod.render;

import com.logisticscraft.occlusionculling.DataProvider;
import com.logisticscraft.occlusionculling.OcclusionCullingInstance;
import com.logisticscraft.occlusionculling.cache.OcclusionCache;
import com.logisticscraft.occlusionculling.util.Vec3d;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BoundedOcclusionCullingInstanceTest {
	private final Vec3d camera = new Vec3d(0.5, 0.5, 0.5);

	@Test
	void raysStartingInsideSolidBlocksReuseOpacityUntilTheNextPass() {
		final CountingWorld world = new CountingWorld();
		final BoundedOcclusionCullingInstance culling = new BoundedOcclusionCullingInstance(64, world);
		final OcclusionCullingInstance original = new OcclusionCullingInstance(64, new CountingWorld());
		for (int z = -8; z <= 8; z++) {
			final Vec3d min = new Vec3d(10, 0, z), max = new Vec3d(11, 1, z + 1);
			assertEquals(original.isAABBVisible(min, max, camera), culling.isAABBVisible(min, max, camera));
		}
		assertEquals(1, world.reads.get("0,0,0"), "All rays start in the same solid block");
		culling.resetCache();
		culling.isAABBVisible(new Vec3d(10, 0, 0), new Vec3d(11, 1, 1), camera);
		assertEquals(2, world.reads.get("0,0,0"), "A new pass must read the world again");
	}

	@Test
	void opacityChangesBecomeVisibleOnTheNextPass() {
		final CountingWorld world = new CountingWorld();
		final BoundedOcclusionCullingInstance culling = new BoundedOcclusionCullingInstance(64, world);
		final Vec3d min = new Vec3d(10, 0, 0), max = new Vec3d(11, 1, 1);
		world.wall = true;
		assertFalse(culling.isAABBVisible(min, max, camera));
		world.wall = false;
		culling.resetCache();
		assertTrue(culling.isAABBVisible(min, max, camera));
		world.wall = true;
		culling.resetCache();
		assertFalse(culling.isAABBVisible(min, max, camera));
	}

	private static final class CountingWorld implements DataProvider {
		private final Map<String, Integer> reads = new HashMap<>();
		private boolean wall;

		@Override
		public boolean prepareChunk(int x, int z) { return true; }

		@Override
		public boolean isOpaqueFullCube(int x, int y, int z) {
			reads.merge(x + "," + y + "," + z, 1, Integer::sum);
			return x <= 2 || (wall && x == 5);
		}
	}

	@Test
	void oversizedRailBoundsCannotBlockOtherVisibilityUpdates() throws Exception {
		final BoundedOcclusionCullingInstance culling = new BoundedOcclusionCullingInstance(64, wall());
		final var field = OcclusionCullingInstance.class.getDeclaredField("cache");
		field.setAccessible(true);
		final OcclusionCache cache = (OcclusionCache) field.get(culling);
		// Seed the library's real cache with hidden voxels, preventing its early-visible
		// exit and reproducing the expensive complete scan of a hidden rail's volume.
		for (int x = 9; x <= 50; x++) {
			for (int y = 9; y <= 50; y++) {
				for (int z = 9; z <= 50; z++) cache.setHidden(x + 64, y + 64, z + 64);
			}
		}
		// Scanning every voxel of a long diagonal rail is too costly. Keep it visible
		// conservatively and let the renderer apply its normal distance clipping.
		assertTrue(culling.isAABBVisible(new Vec3d(10, 10, 10), new Vec3d(50, 50, 50), camera));
		assertTrue(culling.isAABBVisible(new Vec3d(1, 0, 0), new Vec3d(2, 1, 1), camera));
	}

	@Test
	void normalBoundsMatchOriginalCullingIncludingHiddenObjects() {
		final OcclusionCullingInstance original = new OcclusionCullingInstance(128, wall());
		final BoundedOcclusionCullingInstance bounded = new BoundedOcclusionCullingInstance(128, wall());
		for (int x : new int[]{1, 10, -10, 100}) {
			final Vec3d min = new Vec3d(x, 0, 0), max = new Vec3d(x + 1, 2, 1);
			original.resetCache();
			bounded.resetCache();
			assertEquals(original.isAABBVisible(min, max, camera), bounded.isAABBVisible(min, max, camera));
		}
		bounded.resetCache();
		assertFalse(bounded.isAABBVisible(new Vec3d(10, 0, 0), new Vec3d(11, 2, 1), camera));
	}

	@Test
	void entirelyDistantBoundsStayCulled() {
		final BoundedOcclusionCullingInstance culling = new BoundedOcclusionCullingInstance(128, wall());
		assertFalse(culling.isAABBVisible(new Vec3d(200, 10, 10), new Vec3d(300, 100, 100), camera));
	}

	@Test
	void worldSizedAndInvalidBoundsFailOpenWithoutOverflowing() {
		final BoundedOcclusionCullingInstance culling = new BoundedOcclusionCullingInstance(32, wall());
		assertTrue(culling.isAABBVisible(new Vec3d(-30_000_000, -64, -30_000_000), new Vec3d(30_000_000, 320, 30_000_000), camera));
		assertTrue(culling.isAABBVisible(new Vec3d(Double.NaN, 0, 0), new Vec3d(1, 1, 1), camera));
		assertTrue(culling.isAABBVisible(new Vec3d(0, 0, 0), new Vec3d(Double.POSITIVE_INFINITY, 1, 1), camera));
		assertTrue(culling.isAABBVisible(new Vec3d(10, 0, 0), new Vec3d(0, 1, 1), camera));
	}

	@Test
	void distantRejectionUsesWorldSpaceAndTheExpandedVoxelBounds() {
		final Vec3d camera = new Vec3d(1_000_000.5, 64.5, -1_000_000.5);
		final OcclusionCullingInstance original = new OcclusionCullingInstance(32, wall());
		final BoundedOcclusionCullingInstance bounded = new BoundedOcclusionCullingInstance(32, wall());
		for (int offset : new int[]{-34, -31, -30, -29, 29, 30, 31, 34}) {
			final Vec3d min = new Vec3d(camera.x + offset, camera.y, camera.z);
			final Vec3d max = new Vec3d(min.x + 1, min.y + 1, min.z + 1);
			original.resetCache();
			bounded.resetCache();
			assertEquals(original.isAABBVisible(min, max, camera), bounded.isAABBVisible(min, max, camera), "Boundary offset " + offset);
		}
	}

	private static DataProvider wall() {
		return new DataProvider() {
			@Override
			public boolean prepareChunk(int x, int z) { return true; }
			@Override
			public boolean isOpaqueFullCube(int x, int y, int z) { return x >= 4 && x <= 6; }
		};
	}
}
