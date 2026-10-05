package org.mtr.core.path.compute;

import java.util.Arrays;
import java.util.function.BooleanSupplier;

/** The existing PathFinder search, with primitive state and reusable blacklists. Not Dijkstra/A*. */
public final class RailSearch {
	private long[] global = new long[0], local = new long[0], durations = new long[16];
	private int[] globalStamp = new int[0], localStamp = new int[0], path = new int[16], angles = new int[16], best = new int[16];
	private int generation, localGeneration;
	private static final int MAX_STEPS = 2_000_000;

	/** null requests the original incremental implementation; an empty array means no path. */
	public int[] search(RailGraph graph, int start, int end) {
		return search(graph, start, end, null);
	}

	int[] search(RailGraph graph, int start, int end, BooleanSupplier active) {
		if (start < 0 || end < 0 || start >= graph.nodeCount() || end >= graph.nodeCount()) throw new IllegalArgumentException("Invalid endpoint");
		if (start == end) return new int[0];
		int states = graph.nodeCount() * 16;
		if (states > global.length) {
			global = new long[states];
			local = new long[states];
			globalStamp = new int[states];
			localStamp = new int[states];
		}
		if (++generation == 0) {
			Arrays.fill(globalStamp, 0);
			generation = 1;
		}
		nextLocalGeneration();
		long totalTime = Long.MAX_VALUE;
		long elapsed = 1; // ConnectionDetails clamps the initial zero duration to one.
		int size = 1, bestSize = 0;
		path[0] = start;
		angles[0] = -1;
		durations[0] = 1;
		for (int step = 0; step < MAX_STEPS; step++) {
			if ((step & 255) == 0 && (Thread.currentThread().isInterrupted() || active != null && !active.getAsBoolean())) return null;
			int previous = size == 0 ? start : path[size - 1];
			int angle = size == 0 ? -1 : angles[size - 1];
			int bestEdge = -1;
			long bestIncrease = Long.MIN_VALUE;
			long weight = graph.distance(previous, end);
			for (int edge = graph.offsets[previous]; edge < graph.offsets[previous + 1]; edge++) {
				if (angle != -1 && angle != graph.startAngles[edge] && !graph.turnBack[edge]) continue;
				int next = graph.targets[edge];
				int state = next * 16 + graph.arrivalAngles[edge];
				long time = elapsed + graph.duration[edge];
				if (time < 0) return null;
				if (time < totalTime && (localStamp[state] != localGeneration || time < local[state]) &&
					(globalStamp[state] != generation || time <= global[state])) {
					long increase = (weight - graph.distance(next, end)) / graph.duration[edge];
					globalStamp[state] = generation;
					global[state] = time;
					if (increase > bestIncrease) {
						bestIncrease = increase;
						bestEdge = edge;
					}
				}
			}
			if (bestEdge == -1) {
				if (size == 0) return bestSize > 8192 ? null : Arrays.copyOf(best, bestSize);
				elapsed -= durations[--size];
			} else {
				int next = graph.targets[bestEdge];
				int nextAngle = graph.arrivalAngles[bestEdge];
				elapsed += graph.duration[bestEdge];
				int state = next * 16 + nextAngle;
				localStamp[state] = localGeneration;
				local[state] = elapsed;
				if (size == path.length) {
					int capacity = path.length * 2;
					path = Arrays.copyOf(path, capacity);
					angles = Arrays.copyOf(angles, capacity);
					durations = Arrays.copyOf(durations, capacity);
				}
				path[size] = next;
				angles[size] = nextAngle;
				durations[size++] = graph.duration[bestEdge];
				if (next == end) {
					if (elapsed > 0 && (elapsed < totalTime || elapsed == totalTime && size < bestSize)) {
						totalTime = elapsed;
						if (best.length < size) best = new int[path.length];
						System.arraycopy(path, 0, best, 0, size);
						bestSize = size;
					}
					elapsed = 0;
					size = 0;
					nextLocalGeneration();
				}
			}
		}
		return null;
	}

	private void nextLocalGeneration() {
		if (++localGeneration == 0) {
			Arrays.fill(localStamp, 0);
			localGeneration = 1;
		}
	}
}
