package org.mtr.render;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongArrayList;

import java.util.function.ToIntFunction;

/**
 * One frame's suffix distances along a row of route-map blocks.
 *
 * <p>Route map blocks (PSD tops, APG glass) scan the neighbouring blocks every frame to
 * find out how long the current row of connected blocks is, walking block by block —
 * an O(N) world query per block per frame, O(N²) per row. This cache stores, per block
 * position and direction, the number of remaining matching blocks; a single walk
 * back-fills the whole row, so a row of N blocks costs N queries once per frame instead
 * of N²/2.</p>
 *
 * <p>The cache lives inside {@link BlockEntityRenderCulling}'s frame and is dropped when
 * the frame ends, so block edits become visible the next frame.</p>
 *
 * <p>Ported from the YanYang 4.0.5 optimisation series.</p>
 */
public final class RouteMapSpanCache {

	private final Long2IntOpenHashMap[] distances = new Long2IntOpenHashMap[6];
	private final LongArrayList path = new LongArrayList();
	private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

	// Kind bits: 1 = matching block, 2 = this end, 4 = opposite end.

	/**
	 * @param start     the block to compute the remaining span for
	 * @param direction the direction to walk
	 * @param kind      classifies a block: bit 1 set if it continues the row, bit 2 if it
	 *                  is the terminator on this side, bit 4 if it is a terminator from
	 *                  the opposite side (stops the walk without counting)
	 * @return the number of matching blocks from (excluding) {@code start} in the given
	 * direction before the row ends
	 */
	int distance(BlockPos start, Direction direction, ToIntFunction<BlockPos> kind) {
		final int directionIndex = direction.ordinal();
		Long2IntOpenHashMap cache = distances[directionIndex];
		if (cache == null) {
			cache = new Long2IntOpenHashMap();
			cache.defaultReturnValue(-1);
			distances[directionIndex] = cache;
		}

		final int cached = cache.get(start.asLong());
		if (cached >= 0) {
			return cached;
		}

		path.clear();
		cursor.set(start);
		int suffix = -1;
		int walked = 0;
		while (true) {
			final int state = kind.applyAsInt(cursor);
			if ((state & 1) == 0 || walked > 0 && (state & 4) != 0) {
				break;
			}
			final int existing = cache.get(cursor.asLong());
			if (existing >= 0) {
				suffix = existing;
				break;
			}
			// Retain only a bounded prefix; distance calculation still handles longer runs.
			if (path.size() < MAX_PATH) {
				path.add(cursor.asLong());
			}
			walked++;
			if ((state & 2) != 0) {
				break;
			}
			cursor.move(direction);
		}

		suffix += walked - path.size();
		if (cache.size() + path.size() > MAX_ENTRIES) {
			cache.clear();
		}
		for (int i = path.size() - 1; i >= 0; i--) {
			cache.put(path.getLong(i), ++suffix);
		}
		return suffix;
	}

	private static final int MAX_PATH = 65536;
	private static final int MAX_ENTRIES = 65536;
}
