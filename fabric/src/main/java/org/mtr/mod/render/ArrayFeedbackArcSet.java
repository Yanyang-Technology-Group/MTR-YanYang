package org.mtr.mod.render;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import java.util.*;

/** Iris's search on numbered vertices, with the same candidates and tie breaks. */
final class ArrayFeedbackArcSet {
	static int[] solve(Map<Object, Object2IntMap<Object>> vertices) {
		final int size = vertices.size();
		if (size == 0 || size > 65_536) return null;
		final int[] offsets = new int[size + 1];
		for (int source = 0; source < size; source++) {
			final Object2IntMap<Object> edges = vertices.get(source);
			if (edges == null || edges.size() > 65_536 - size - offsets[source]) return null;
			offsets[source + 1] = offsets[source] + edges.size();
		}
		final int edgeCount = offsets[size];
		final int[] targets = new int[edgeCount], weights = new int[edgeCount];
		for (int source = 0; source < size; source++) {
			int index = offsets[source];
			for (Object2IntMap.Entry<Object> edge : vertices.get(source).object2IntEntrySet()) {
				if (!(edge.getKey() instanceof Integer)) return null;
				final int target = (Integer) edge.getKey();
				if (target < 0 || target >= size) return null;
				targets[index] = target;
				weights[index++] = edge.getIntValue();
			}
		}

		// Each copied graph differs only in adjacency order. Match Collections.shuffle
		// on the original ArrayList and its comparator, without allocating tree maps.
		final int[][] copies = new int[Math.min(10, size) + 1][];
		copies[0] = targets;
		final int[] shuffle = new int[size];
		for (int i = 0; i < size; i++) shuffle[i] = i;
		final Random random = new Random(7);
		final long[] sorted = new long[edgeCount];
		for (int copy = 1; copy < copies.length; copy++) {
			for (int i = size; i > 1; i--) {
				final int other = random.nextInt(i), value = shuffle[i - 1];
				shuffle[i - 1] = shuffle[other];
				shuffle[other] = value;
			}
			final int[] ordered = new int[edgeCount];
			for (int i = 0; i < edgeCount; i++) sorted[i] = ((long) shuffle[targets[i]] << 32) | targets[i];
			for (int source = 0; source < size; source++) Arrays.sort(sorted, offsets[source], offsets[source + 1]);
			for (int i = 0; i < edgeCount; i++) ordered[i] = (int) sorted[i];
			copies[copy] = ordered;
		}

		final boolean[] seen = new boolean[size];
		final int[] nodes = new int[size], cursors = new int[size], finished = new int[size], best = new int[size];
		int minWeight = Integer.MAX_VALUE, minSize = Integer.MAX_VALUE;
		final int starts = Math.min(size, Math.max(1, 1_000_000 / (size + edgeCount)));
		for (int start = 0; start < starts; start++) {
			for (int[] copy : copies) {
				Arrays.fill(seen, false);
				int depth = 0, count = 0;
				nodes[0] = start;
				cursors[0] = offsets[start];
				seen[start] = true;
				while (depth >= 0) {
					final int source = nodes[depth];
					if (cursors[depth] < offsets[source + 1]) {
						final int target = copy[cursors[depth]++];
						if (!seen[target]) {
							seen[target] = true;
							nodes[++depth] = target;
							cursors[depth] = offsets[target];
						}
					} else {
						finished[count++] = source;
						depth--;
					}
				}
				// lfas normally receives a strongly connected component. Let the
				// library handle other inputs instead of assuming all vertices were seen.
				if (count != size) return null;
				Arrays.fill(seen, false);
				int weight = 0, removed = 0;
				for (int source : finished) {
					seen[source] = true;
					for (int i = offsets[source]; i < offsets[source + 1]; i++) {
						if (!seen[targets[i]]) {
							weight += weights[i];
							removed++;
						}
					}
					if (weight > minWeight) break;
				}
				if (weight < minWeight || weight == minWeight && removed < minSize) {
					System.arraycopy(finished, 0, best, 0, size);
					minWeight = weight;
					minSize = removed;
				}
			}
		}
		return best;
	}
}
