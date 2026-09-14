package org.mtr.mod.render;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import java.util.function.ToIntFunction;

/** One frame's suffix distances along a station's route-map blocks. */
public final class RouteMapSpanCache {
	private final Long2IntOpenHashMap[] distances = new Long2IntOpenHashMap[6];
	private final LongArrayList path = new LongArrayList();
	private final BlockPos.Mutable cursor = new BlockPos.Mutable();

	// Kind bits: 1 = matching block, 2 = this end, 4 = opposite end.
	public int distance(BlockPos start, Direction direction, ToIntFunction<BlockPos> kind) {
		Long2IntOpenHashMap cache = distances[direction.ordinal()];
		if (cache == null) {
			cache = new Long2IntOpenHashMap();
			cache.defaultReturnValue(-1);
			distances[direction.ordinal()] = cache;
		}
		final int cached = cache.get(start.asLong());
		if (cached >= 0) return cached;
		path.clear();
		cursor.set(start);
		int suffix = -1;
		int walked = 0;
		while (true) {
			final int state = kind.applyAsInt(cursor);
			if ((state & 1) == 0 || walked > 0 && (state & 4) != 0) break;
			final int existing = cache.get(cursor.asLong());
			if (existing >= 0) { suffix = existing; break; }
			// Retain only a bounded prefix; distance calculation still handles longer runs.
			if (path.size() < 65536) path.add(cursor.asLong());
			walked++;
			if ((state & 2) != 0) break;
			cursor.move(direction);
		}
		suffix += walked - path.size();
		if (cache.size() + path.size() > 65536) cache.clear();
		for (int i = path.size() - 1; i >= 0; i--) cache.put(path.getLong(i), ++suffix);
		return suffix;
	}
}
