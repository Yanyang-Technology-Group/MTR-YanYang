package org.mtr.render;

import com.logisticscraft.occlusionculling.DataProvider;

import java.util.Arrays;

/**
 * Worker-owned block opacity cache, valid only until the next visibility pass.
 *
 * <p>The occlusion culling library queries block opacity per voxel along every ray. Each
 * delegated query allocates a block position and re-reads the world. This direct-mapped
 * cache memoises the results for the current pass; conflicts only cost a re-query, never
 * a wrong answer, because all three coordinates are compared.</p>
 *
 * <p>Ported from the YanYang 4.0.5 optimisation series.</p>
 */
final class CachedCullingDataProvider implements DataProvider {

	private static final int CAPACITY = 16_384;
	private final DataProvider delegate;
	private final int[] xs = new int[CAPACITY];
	private final int[] ys = new int[CAPACITY];
	private final int[] zs = new int[CAPACITY];
	private final byte[] states = new byte[CAPACITY];

	CachedCullingDataProvider(DataProvider delegate) {
		this.delegate = delegate;
	}

	@Override
	public boolean prepareChunk(int chunkX, int chunkZ) {
		return delegate.prepareChunk(chunkX, chunkZ);
	}

	@Override
	public boolean isOpaqueFullCube(int x, int y, int z) {
		final int hash = x * 73_428_767 ^ y * 912_931 ^ z * 43_828_973;
		final int index = (hash ^ (hash >>> 16)) & (CAPACITY - 1);
		if (states[index] != 0 && xs[index] == x && ys[index] == y && zs[index] == z) {
			return states[index] == 2;
		}
		final boolean opaque = delegate.isOpaqueFullCube(x, y, z);
		xs[index] = x;
		ys[index] = y;
		zs[index] = z;
		states[index] = (byte) (opaque ? 2 : 1);
		return opaque;
	}

	@Override
	public void cleanup() {
		// The library calls cleanup after every ray. Keep opacity until resetCache,
		// including solid blocks around the camera that its visibility cache omits.
		delegate.cleanup();
	}

	void resetCache() {
		Arrays.fill(states, (byte) 0);
	}
}
