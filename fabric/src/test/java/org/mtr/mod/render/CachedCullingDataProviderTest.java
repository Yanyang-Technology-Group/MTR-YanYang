package org.mtr.mod.render;

import com.logisticscraft.occlusionculling.DataProvider;
import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CachedCullingDataProviderTest {
	@Test
	void cacheCollisionsAndLargeCoordinatesDoNotChangeOpacity() {
		final DataProvider world = new DataProvider() {
			@Override
			public boolean prepareChunk(int x, int z) { return true; }
			@Override
			public boolean isOpaqueFullCube(int x, int y, int z) { return (x ^ y ^ z) < 0; }
		};
		final CachedCullingDataProvider cache = new CachedCullingDataProvider(world);
		final Random random = new Random(42);
		// Far more positions than cache slots, including coordinates that alias
		// if packed into a Minecraft block-position long without range checks.
		for (int i = 0; i < 100_000; i++) {
			final int x = random.nextInt(), y = random.nextInt(), z = random.nextInt();
			assertEquals(world.isOpaqueFullCube(x, y, z), cache.isOpaqueFullCube(x, y, z));
			assertEquals(world.isOpaqueFullCube(x, y, z), cache.isOpaqueFullCube(x, y, z));
			assertFalse(cache.isOpaqueFullCube(0, 0, 0));
			assertTrue(cache.isOpaqueFullCube(0, Integer.MIN_VALUE, 0));
		}
	}

	@Test
	void transparentBlocksAreCachedAndProviderLifecycleIsPreserved() {
		final AtomicInteger reads = new AtomicInteger(), cleanups = new AtomicInteger();
		final CachedCullingDataProvider cache = new CachedCullingDataProvider(new DataProvider() {
			@Override
			public boolean prepareChunk(int x, int z) { return x == 3 && z == -5; }
			@Override
			public boolean isOpaqueFullCube(int x, int y, int z) { reads.incrementAndGet(); return false; }
			@Override
			public void cleanup() { cleanups.incrementAndGet(); }
		});
		assertTrue(cache.prepareChunk(3, -5));
		assertFalse(cache.prepareChunk(0, 0));
		for (int i = 0; i < 20; i++) {
			assertFalse(cache.isOpaqueFullCube(48, 64, -80));
			cache.cleanup();
		}
		assertEquals(1, reads.get());
		assertEquals(20, cleanups.get());
		cache.resetCache();
		assertFalse(cache.isOpaqueFullCube(48, 64, -80));
		assertEquals(2, reads.get());
	}

	@Test
	void failedWorldReadsDoNotPoisonTheCache() {
		final AtomicInteger reads = new AtomicInteger();
		final CachedCullingDataProvider cache = new CachedCullingDataProvider(new DataProvider() {
			@Override
			public boolean prepareChunk(int x, int z) { return true; }
			@Override
			public boolean isOpaqueFullCube(int x, int y, int z) {
				if (reads.incrementAndGet() == 1) throw new IllegalStateException("Chunk unavailable");
				return true;
			}
		});
		assertThrows(IllegalStateException.class, () -> cache.isOpaqueFullCube(0, 0, 0));
		assertTrue(cache.isOpaqueFullCube(0, 0, 0));
		assertTrue(cache.isOpaqueFullCube(0, 0, 0));
		assertEquals(2, reads.get());
	}
}
