package org.mtr.mod.render;

import com.logisticscraft.occlusionculling.DataProvider;
import com.logisticscraft.occlusionculling.OcclusionCullingInstance;
import com.logisticscraft.occlusionculling.util.Vec3d;

final class BoundedOcclusionCullingInstance extends OcclusionCullingInstance {
	private static final int MAX_SCAN_VOXELS = 65_536;
	private final int reach;
	private final CachedCullingDataProvider cachedProvider;
	// The library's default constructor expands each side by half a block.
	private static final double EXPANSION = 0.5;

	BoundedOcclusionCullingInstance(int maxDistance, DataProvider provider) {
		this(maxDistance, new CachedCullingDataProvider(provider));
	}

	private BoundedOcclusionCullingInstance(int maxDistance, CachedCullingDataProvider provider) {
		super(maxDistance, provider);
		cachedProvider = provider;
		reach = maxDistance - 2;
	}

	@Override
	public void resetCache() {
		super.resetCache();
		cachedProvider.resetCache();
	}

	@Override
	public boolean isAABBVisible(Vec3d min, Vec3d max, Vec3d camera) {
		if (!valid(min) || !valid(max) || !valid(camera) || min.x > max.x || min.y > max.y || min.z > max.z) {
			return true;
		}
		final double minX = Math.floor(min.x - EXPANSION), maxX = Math.floor(max.x + EXPANSION);
		final double minY = Math.floor(min.y - EXPANSION), maxY = Math.floor(max.y + EXPANSION);
		final double minZ = Math.floor(min.z - EXPANSION), maxZ = Math.floor(max.z + EXPANSION);
		final double cameraX = Math.floor(camera.x), cameraY = Math.floor(camera.y), cameraZ = Math.floor(camera.z);

		// The library skips all voxels outside its cache reach, but still loops over
		// their entire volume first. Reject fully distant rails before those loops.
		if (maxX < cameraX - reach || minX > cameraX + reach ||
				maxY < cameraY - reach || minY > cameraY + reach ||
				maxZ < cameraZ - reach || minZ > cameraZ + reach) {
			return false;
		}
		// A long curved rail's enclosing box can contain millions of empty voxels.
		// Failing open bounds this work without hiding any potentially visible model.
		// Use doubles so a world-sized box cannot overflow the volume calculation.
		if ((maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1) > MAX_SCAN_VOXELS) {
			return true;
		}
		return super.isAABBVisible(min, max, camera);
	}

	private static boolean valid(Vec3d vector) {
		return vector.x > Integer.MIN_VALUE + 1D && vector.x < Integer.MAX_VALUE - 1D &&
				vector.y > Integer.MIN_VALUE + 1D && vector.y < Integer.MAX_VALUE - 1D &&
				vector.z > Integer.MIN_VALUE + 1D && vector.z < Integer.MAX_VALUE - 1D;
	}
}
