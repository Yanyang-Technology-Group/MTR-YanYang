package org.mtr.render;

import com.logisticscraft.occlusionculling.DataProvider;
import com.logisticscraft.occlusionculling.OcclusionCullingInstance;
import com.logisticscraft.occlusionculling.util.Vec3d;

/**
 * Wraps {@link OcclusionCullingInstance} with hard bounds on the scan work.
 *
 * <p>The library's {@code isAABBVisible} loops over every voxel of the (expanded) query
 * box before consulting its visibility cache. A large model bounding box — a long
 * curved rail, a big train — can contain millions of voxels, so a single visibility
 * query could run for seconds on the worker thread. This wrapper:</p>
 * <ol>
 *   <li>treats malformed boxes (NaN, inverted) as visible (conservative);</li>
 *   <li>rejects boxes entirely outside the cache reach before the voxel loops;</li>
 *   <li>fails open (treats as visible) for boxes whose voxel volume exceeds
 *       {@value #MAX_SCAN_VOXELS}, trading a little over-draw for bounded latency
 *       without ever hiding a potentially visible model.</li>
 * </ol>
 *
 * <p>Ported from the YanYang 4.0.5 optimisation series.</p>
 */
final class BoundedOcclusionCullingInstance extends OcclusionCullingInstance {

	private static final int MAX_SCAN_VOXELS = 65_536;
	private final int reach;
	private final CachedCullingDataProvider cachedProvider;
	/**
	 * The library's default constructor expands each side by half a block.
	 */
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
		// their entire volume first. Reject fully distant boxes before those loops.
		if (maxX < cameraX - reach || minX > cameraX + reach ||
			maxY < cameraY - reach || minY > cameraY + reach ||
			maxZ < cameraZ - reach || minZ > cameraZ + reach) {
			return false;
		}
		// A large enclosing box can contain millions of empty voxels. Failing open bounds
		// this work without hiding any potentially visible model. Use doubles so a
		// world-sized box cannot overflow the volume calculation.
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
