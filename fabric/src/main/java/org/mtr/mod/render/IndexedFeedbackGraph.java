package org.mtr.mod.render;

import it.unimi.dsi.fastutil.objects.Object2IntLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;

import java.lang.reflect.Constructor;
import java.util.*;
import java.util.function.BiFunction;

/** Re-labels a cycle component without changing Iris's solver or its iteration order. */
public final class IndexedFeedbackGraph {
	private static final int MAX_RECORDS = 65536;
	private final Object[] originals;
	final Map<Object, Object2IntMap<Object>> vertices = new LinkedHashMap<>();
	private int edgeCount;

	private IndexedFeedbackGraph(Object[] originals) {
		this.originals = originals;
	}

	public static Object solve(Object provider, Object graph, Object weights, BiFunction<Object, Object, Object> original) {
		if (!RenderOrderCache.isActive() || !(graph instanceof MutableGraph) || !(weights instanceof RenderOrderCache.Graph)
				|| !provider.getClass().getName().equals("de.odysseus.ithaka.digraph.util.fas.SimpleFeedbackArcSetProvider")
				|| !graph.getClass().getName().equals("de.odysseus.ithaka.digraph.MapDigraph")) {
			return original.apply(graph, weights);
		}
		final RenderOrderCache.Graph input = (RenderOrderCache.Graph) graph;
		if (input.mtr$getVertices().size() < 16) {
			return original.apply(graph, weights);
		}
		final IndexedFeedbackGraph indexed = index(input, (RenderOrderCache.Graph) weights);
		if (indexed == null) {
			return original.apply(graph, weights);
		}
		final MutableGraph restored;
		final Constructor<?> constructor;
		try {
			// Reflect only the optional library's constructor; all edge operations use maps.
			constructor = graph.getClass().getConstructor();
			restored = (MutableGraph) constructor.newInstance();
		} catch (ReflectiveOperationException exception) {
			return original.apply(graph, weights);
		}
		final int[] order = ArrayFeedbackArcSet.solve(indexed.vertices);
		if (order != null) {
			final boolean[] seen = new boolean[order.length];
			for (int source : order) {
				seen[source] = true;
				for (Object2IntMap.Entry<Object> edge : indexed.vertices.get(source).object2IntEntrySet()) {
					final int target = (Integer) edge.getKey();
					if (!seen[target]) {
						// Match MapDigraph.put: insert the source, then its target,
						// including targets which never have outgoing feedback edges.
						restored.mtr$getVertices().computeIfAbsent(indexed.originals[source], ignored -> newEdges()).put(indexed.originals[target], edge.getIntValue());
						restored.mtr$getVertices().computeIfAbsent(indexed.originals[target], ignored -> newEdges());
					}
				}
			}
		} else {
			final MutableGraph numbered;
			try {
				numbered = (MutableGraph) constructor.newInstance();
			} catch (ReflectiveOperationException exception) {
				return original.apply(graph, weights);
			}
			numbered.mtr$getVertices().putAll(indexed.vertices);
			numbered.mtr$setEdgeCount(indexed.edgeCount);
			final Object result = FeedbackGraphTraversal.duringSolve(() -> original.apply(numbered, numbered));
			if (!(result instanceof RenderOrderCache.Graph)) return original.apply(graph, weights);
			restored.mtr$getVertices().putAll(indexed.restore((RenderOrderCache.Graph) result));
		}
		restored.mtr$setEdgeCount(restored.mtr$getVertices().values().stream().mapToInt(Map::size).sum());
		return restored;
	}

	private static Object2IntMap<Object> newEdges() {
		final Object2IntMap<Object> edges = new Object2IntLinkedOpenHashMap<>();
		edges.defaultReturnValue(Integer.MIN_VALUE);
		return edges;
	}

	static IndexedFeedbackGraph index(RenderOrderCache.Graph graph, RenderOrderCache.Graph weights) {
		final Map<Object, ? extends Map<Object, Integer>> source = graph.mtr$getVertices();
		if (source.isEmpty() || source.size() > MAX_RECORDS) {
			return null;
		}
		final Object[] originals = source.keySet().toArray();
		final IdentityHashMap<Object, Integer> indices = new IdentityHashMap<>(originals.length);
		for (int i = 0; i < originals.length; i++) {
			if (originals[i] == null) {
				return null;
			}
			indices.put(originals[i], i);
		}
		final IndexedFeedbackGraph result = new IndexedFeedbackGraph(originals);
		int from = 0;
		for (Map.Entry<Object, ? extends Map<Object, Integer>> vertex : source.entrySet()) {
			final Map<Object, Integer> edges = vertex.getValue();
			final Map<Object, Integer> parentEdges = weights == graph ? edges : weights.mtr$getVertices().get(vertex.getKey());
			if (parentEdges == null || edges.size() > MAX_RECORDS - originals.length - result.edgeCount) {
				return null;
			}
			final Object2IntMap<Object> numberedEdges = new Object2IntLinkedOpenHashMap<>(edges.size());
			numberedEdges.defaultReturnValue(Integer.MIN_VALUE);
			for (Map.Entry<Object, Integer> edge : edges.entrySet()) {
				final Integer target = indices.get(edge.getKey());
				final Integer weight = edge.getValue();
				// Equal but non-identical aliases and nonmatching parent weights use Iris unchanged.
				if (target == null || weight == null || weight == Integer.MIN_VALUE || (parentEdges != edges && !weight.equals(parentEdges.get(edge.getKey())))) {
					return null;
				}
				numberedEdges.put((Object) target, weight.intValue());
			}
			result.vertices.put(from++, numberedEdges);
			result.edgeCount += edges.size();
		}
		return result;
	}

	Map<Object, Object2IntMap<Object>> restore(RenderOrderCache.Graph feedback) {
		final Map<Object, Object2IntMap<Object>> result = new LinkedHashMap<>();
		for (Map.Entry<Object, ? extends Map<Object, Integer>> vertex : feedback.mtr$getVertices().entrySet()) {
			final Object2IntMap<Object> edges = new Object2IntLinkedOpenHashMap<>(vertex.getValue().size());
			edges.defaultReturnValue(Integer.MIN_VALUE);
			for (Map.Entry<Object, Integer> edge : vertex.getValue().entrySet()) {
				edges.put(originals[(Integer) edge.getKey()], edge.getValue().intValue());
			}
			result.put(originals[(Integer) vertex.getKey()], edges);
		}
		return result;
	}

	public interface MutableGraph extends RenderOrderCache.Graph {
		@Override
		Map<Object, Object2IntMap<Object>> mtr$getVertices();

		void mtr$setEdgeCount(int edgeCount);
	}
}
