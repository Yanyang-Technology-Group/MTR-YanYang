package org.mtr.mod.render;

import it.unimi.dsi.fastutil.objects.Object2IntLinkedOpenHashMap;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class GraphResultCacheTest {
	private final Object a = new Object(), b = new Object(), c = new Object();

	@Test
	void changedParentWeightsCannotReuseFeedbackEdges() {
		final GraphResultCache<String> cache = new GraphResultCache<>(100, 8, 1000);
		final TestGraph component = new TestGraph().edge(a, b, 1).edge(b, a, 2);
		final TestGraph parent = new TestGraph().edge(a, b, 1).edge(b, a, 2).edge(c, a, 5);
		assertEquals("first", cache.getOrCompute(List.of(component), null, parent, () -> "first"));
		parent.edge(c, a, 100);
		assertEquals("first", cache.getOrCompute(List.of(component), null, parent, () -> fail("unrelated weight caused a miss")));
		parent.edge(a, b, 3);
		assertEquals("changed", cache.getOrCompute(List.of(component), null, parent, () -> "changed"));
		parent.vertices.get(a).remove(b);
		assertEquals("missing", cache.getOrCompute(List.of(component), null, parent, () -> "missing"));
	}

	@Test
	void equalFingerprintsStillRequireExactEdgeWeights() {
		final GraphResultCache<String> cache = new GraphResultCache<>(100, 8, 1000);
		final TestGraph first = new TestGraph().edge(a, b, 1).edge(a, c, 1000);
		final TestGraph collision = new TestGraph().edge(a, b, 2).edge(a, c, 39);
		assertEquals("first", cache.getOrCompute(List.of(first), null, null, () -> "first"));
		assertEquals("collision", cache.getOrCompute(List.of(collision), null, null, () -> "collision"));
		assertEquals("first", cache.getOrCompute(List.of(first), null, null, () -> fail("collision replaced the other entry")));
	}

	@Test
	void solverIdentityAndEdgeIterationOrderArePartOfTheKey() {
		final GraphResultCache<String> cache = new GraphResultCache<>(100, 8, 1000);
		final TestGraph graph = new TestGraph().edge(a, b, 1).edge(a, c, 2);
		final Object provider = new Object();
		assertEquals("first", cache.getOrCompute(List.of(graph), provider, null, () -> "first"));
		assertEquals("provider", cache.getOrCompute(List.of(graph), new Object(), null, () -> "provider"));
		graph.vertices.get(a).remove(b);
		graph.edge(a, b, 1);
		assertEquals("reordered", cache.getOrCompute(List.of(graph), provider, null, () -> "reordered"));
	}

	@Test
	void leastRecentlyUsedEntriesAreEvictedByCountAndRecordBudget() {
		for (final GraphResultCache<String> cache : List.of(new GraphResultCache<String>(100, 2, 100), new GraphResultCache<String>(100, 10, 10))) {
			final TestGraph first = new TestGraph().edge(a, b, 1);
			final TestGraph second = new TestGraph().edge(b, c, 1);
			final TestGraph third = new TestGraph().edge(c, a, 1);
			assertEquals("first", cache.getOrCompute(List.of(first), null, null, () -> "first"));
			assertEquals("second", cache.getOrCompute(List.of(second), null, null, () -> "second"));
			assertEquals("first", cache.getOrCompute(List.of(first), null, null, () -> fail("recent entry lost")));
			assertEquals("third", cache.getOrCompute(List.of(third), null, null, () -> "third"));
			assertEquals("first", cache.getOrCompute(List.of(first), null, null, () -> fail("wrong entry evicted")));
			assertEquals("recomputed", cache.getOrCompute(List.of(second), null, null, () -> "recomputed"));
		}
	}

	@Test
	void failuresAndUnsupportedInputsDoNotPoisonOtherEntries() {
		final GraphResultCache<String> cache = new GraphResultCache<>(8, 8, 32);
		final TestGraph graph = new TestGraph().edge(a, b, 1);
		assertThrows(IllegalStateException.class, () -> cache.getOrCompute(List.of(graph), null, null, () -> { throw new IllegalStateException(); }));
		assertEquals("recovered", cache.getOrCompute(List.of(graph), null, null, () -> "recovered"));
		assertEquals("unknown", cache.getOrCompute(List.of(new Object()), null, null, () -> "unknown"));
		final TestGraph large = new TestGraph().edge(a, b, 1).edge(a, c, 1).edge(b, a, 1).edge(b, c, 1);
		assertEquals("large", cache.getOrCompute(List.of(large), null, null, () -> "large"));
		assertEquals("recovered", cache.getOrCompute(List.of(graph), null, null, () -> fail("fallback cleared a valid entry")));
	}

	private static final class TestGraph implements RenderOrderCache.Graph {
		private final Map<Object, Map<Object, Integer>> vertices = new LinkedHashMap<>();

		TestGraph edge(Object from, Object to, int weight) {
			vertices.computeIfAbsent(from, ignored -> new Object2IntLinkedOpenHashMap<>()).put(to, weight);
			vertices.computeIfAbsent(to, ignored -> new Object2IntLinkedOpenHashMap<>());
			return this;
		}

		@Override
		public Map<Object, ? extends Map<Object, Integer>> mtr$getVertices() {
			return vertices;
		}
	}
}
