package org.mtr.mod.render;

import java.util.*;
import java.util.function.Supplier;

/** Per-solve adjacency snapshots for Iris's immutable, numbered graph variants. */
public final class FeedbackGraphTraversal {
	private static final ThreadLocal<FeedbackGraphTraversal> ACTIVE = new ThreadLocal<>();
	private final Map<Object, Snapshot> snapshots = new IdentityHashMap<>();
	private int records;

	public static <T> T duringSolve(Supplier<T> solve) {
		final FeedbackGraphTraversal previous = ACTIVE.get();
		ACTIVE.set(new FeedbackGraphTraversal());
		try {
			return solve.get();
		} finally {
			if (previous == null) ACTIVE.remove(); else ACTIVE.set(previous);
		}
	}

	public static boolean tryTraverse(Object graph, Object root, Set<Object> visited, Collection<Object> result) {
		final FeedbackGraphTraversal active = ACTIVE.get();
		return active != null && active.traverse(graph, root, visited, result);
	}

	public boolean traverse(Object graph, Object root, Set<Object> visited, Collection<Object> result) {
		if (!(graph instanceof RenderOrderCache.Graph) || !(root instanceof Integer) || !visited.isEmpty() || !result.isEmpty()) return false;
		Snapshot snapshot = snapshots.get(graph);
		if (snapshot == null) {
			if (snapshots.size() >= 12) return false;
			snapshot = Snapshot.create(((RenderOrderCache.Graph) graph).mtr$getVertices(), 262144 - records);
			if (snapshot == null) return false;
			snapshots.put(graph, snapshot);
			records += snapshot.records;
		}
		final int start = (Integer) root;
		if (start < 0 || start >= snapshot.vertices.length) return false;
		Arrays.fill(snapshot.seen, false);
		int depth = 0;
		snapshot.nodes[0] = start;
		snapshot.edges[0] = 0;
		snapshot.seen[start] = true;
		visited.add(snapshot.vertices[start]);
		// Match the library's recursive DFS discovery and postorder, without graph lookups per edge.
		while (depth >= 0) {
			final int node = snapshot.nodes[depth];
			final int edge = snapshot.edges[depth];
			if (edge < snapshot.targets[node].length) {
				final int target = snapshot.targets[node][edge];
				snapshot.edges[depth]++;
				if (!snapshot.seen[target]) {
					snapshot.seen[target] = true;
					visited.add(snapshot.vertices[target]);
					snapshot.nodes[++depth] = target;
					snapshot.edges[depth] = 0;
				}
			} else {
				result.add(snapshot.vertices[node]);
				depth--;
			}
		}
		return true;
	}

	private static final class Snapshot {
		private final Object[] vertices;
		private final int[][] targets;
		private final int[] nodes, edges;
		private final boolean[] seen;
		private int records;

		private Snapshot(int size) {
			vertices = new Object[size];
			targets = new int[size][];
			nodes = new int[size];
			edges = new int[size];
			seen = new boolean[size];
			records = size;
		}

		private static Snapshot create(Map<Object, ? extends Map<Object, Integer>> graph, int budget) {
			final int size = graph.size();
			if (size == 0 || size > 65536 || size > budget) return null;
			final Snapshot snapshot = new Snapshot(size);
			for (Map.Entry<Object, ? extends Map<Object, Integer>> vertex : graph.entrySet()) {
				if (!(vertex.getKey() instanceof Integer)) return null;
				final int node = (Integer) vertex.getKey();
				if (node < 0 || node >= size || snapshot.vertices[node] != null || vertex.getValue().size() > budget - snapshot.records) return null;
				snapshot.vertices[node] = vertex.getKey();
				final int[] targets = new int[vertex.getValue().size()];
				int i = 0;
				for (Object target : vertex.getValue().keySet()) {
					if (!(target instanceof Integer) || (Integer) target < 0 || (Integer) target >= size) return null;
					targets[i++] = (Integer) target;
				}
				snapshot.targets[node] = targets;
				snapshot.records += targets.length;
			}
			return snapshot;
		}
	}
}
