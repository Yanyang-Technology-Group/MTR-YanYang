package org.mtr.mod.render;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class IndexedFeedbackGraphTest {
	@Test
	void preservesTopologyWeightsIterationOrderAndOriginalIdentities() {
		final Object a = new Collision(), b = new Collision(), c = new Collision();
		final Map<Object, Map<Object, Integer>> source = new LinkedHashMap<>();
		source.put(c, edges(a, 7, b, 3));
		source.put(a, edges(a, 2, c, 9));
		source.put(b, new LinkedHashMap<>());
		final RenderOrderCache.Graph graph = () -> source;
		final IndexedFeedbackGraph indexed = IndexedFeedbackGraph.index(graph, graph);
		assertNotNull(indexed);
		assertEquals(List.of(0, 1, 2), new ArrayList<>(indexed.vertices.keySet()));
		assertEquals(List.of(1, 2), new ArrayList<>(indexed.vertices.get(0).keySet()));
		assertEquals(7, indexed.vertices.get(0).getInt(1));
		assertEquals(Integer.MIN_VALUE, indexed.vertices.get(2).defaultReturnValue());
		final Map<Object, Map<Object, Integer>> feedback = new LinkedHashMap<>();
		feedback.put(1, edges(0, 9, 1, 2));
		feedback.put(0, new LinkedHashMap<>());
		final Map<Object, ?> restored = indexed.restore(() -> feedback);
		final List<Object> restoredVertices = new ArrayList<>(restored.keySet());
		assertSame(a, restoredVertices.get(0));
		assertSame(c, restoredVertices.get(1));
		assertEquals(List.of(c, a), new ArrayList<>(((Map<?, ?>) restored.get(a)).keySet()));
		assertEquals(9, ((Map<?, ?>) restored.get(a)).get(c));
		assertEquals(List.of(c, a, b), new ArrayList<>(source.keySet()));
		assertEquals(2, source.get(c).size());
	}

	@Test
	void onlyMatchingComponentWeightsCanUseNumberedGraph() {
		final Object a = new Object(), b = new Object(), c = new Object();
		final Map<Object, Map<Object, Integer>> source = new LinkedHashMap<>();
		source.put(a, edges(b, 2));
		source.put(b, edges(a, 1));
		final Map<Object, Map<Object, Integer>> parent = new LinkedHashMap<>();
		parent.put(a, edges(b, 2, c, 900));
		parent.put(b, edges(a, 1));
		assertNotNull(IndexedFeedbackGraph.index(() -> source, () -> parent));
		parent.get(a).put(b, 3);
		assertNull(IndexedFeedbackGraph.index(() -> source, () -> parent));
		parent.get(a).remove(b);
		assertNull(IndexedFeedbackGraph.index(() -> source, () -> parent));
	}

	@Test
	void missingAndEqualButNonidenticalTargetsFallBack() {
		final Object a = new Object(), b = new String("b");
		final Map<Object, Map<Object, Integer>> source = new LinkedHashMap<>();
		source.put(a, edges(new String("b"), 1));
		source.put(b, edges(a, 1));
		final RenderOrderCache.Graph graph = () -> source;
		assertNull(IndexedFeedbackGraph.index(graph, graph));
		source.get(a).clear();
		source.get(a).put(new Object(), 1);
		assertNull(IndexedFeedbackGraph.index(graph, graph));
	}

	@Test
	void oversizedGraphsAndEmptyGraphsFallBack() {
		final Map<Object, Map<Object, Integer>> source = new LinkedHashMap<>();
		final RenderOrderCache.Graph graph = () -> source;
		assertNull(IndexedFeedbackGraph.index(graph, graph));
		for (int i = 0; i < 32769; i++) {
			final Object vertex = new Object();
			source.put(vertex, edges(vertex, 1));
		}
		assertNull(IndexedFeedbackGraph.index(graph, graph));
	}

	@Test
	void outsideIrisScopePreservesInputsAndExceptions() {
		final Object provider = new Object(), graph = new Object(), weights = new Object(), result = new Object();
		assertSame(result, IndexedFeedbackGraph.solve(provider, graph, weights, (g, w) -> {
			assertSame(graph, g);
			assertSame(weights, w);
			return result;
		}));
		assertThrows(IllegalStateException.class, () -> IndexedFeedbackGraph.solve(provider, graph, weights, (g, w) -> { throw new IllegalStateException(); }));
	}

	private static Map<Object, Integer> edges(Object... pairs) {
		final Map<Object, Integer> edges = new LinkedHashMap<>();
		for (int i = 0; i < pairs.length; i += 2) edges.put(pairs[i], (Integer) pairs[i + 1]);
		return edges;
	}

	private static final class Collision {
		@Override
		public int hashCode() {
			return 1;
		}
	}
}
