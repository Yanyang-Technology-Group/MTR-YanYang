package org.mtr.mod.render;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class FeedbackGraphTraversalTest {
	@Test
	void visitsEveryRootInOriginalPostorderAndReadsAdjacencyOnlyOnce() throws Exception {
		final Map<Object, Map<Object, Integer>> vertices = new LinkedHashMap<>();
		vertices.put(0, edges(2, 1));
		vertices.put(1, edges(3));
		vertices.put(2, edges(3, 0));
		vertices.put(3, edges(1));
		final AtomicInteger reads = new AtomicInteger();
		final RenderOrderCache.Graph graph = () -> { reads.incrementAndGet(); return vertices; };
		final Class<?> type = Class.forName("org.mtr.mod.render.FeedbackGraphTraversal");
		final Object traversal = type.getConstructor().newInstance();
		final var method = type.getMethod("traverse", Object.class, Object.class, Set.class, Collection.class);
		for (int repeat = 0; repeat < 30; repeat++) {
			for (int root = 0; root < 4; root++) {
				final Set<Object> expectedVisited = new LinkedHashSet<>(), actualVisited = new LinkedHashSet<>();
				final List<Object> expected = new ArrayList<>(), actual = new ArrayList<>();
				dfs(vertices, root, expectedVisited, expected);
				assertEquals(true, method.invoke(traversal, graph, root, actualVisited, actual));
				assertEquals(expected, actual);
				assertEquals(new ArrayList<>(expectedVisited), new ArrayList<>(actualVisited));
			}
		}
		assertEquals(1, reads.get());
	}

	@Test
	void rejectsUnsupportedKeysAndNonemptyDestinationsWithoutMutation() throws Exception {
		final Class<?> type = Class.forName("org.mtr.mod.render.FeedbackGraphTraversal");
		final Object traversal = type.getConstructor().newInstance();
		final var method = type.getMethod("traverse", Object.class, Object.class, Set.class, Collection.class);
		final Set<Object> visited = new HashSet<>();
		final List<Object> result = new ArrayList<>();
		assertEquals(false, method.invoke(traversal, new Object(), 0, visited, result));
		assertEquals(false, method.invoke(traversal, (RenderOrderCache.Graph) () -> Map.of("a", Map.of()), "a", visited, result));
		assertEquals(false, method.invoke(traversal, (RenderOrderCache.Graph) () -> Map.of(0, Map.of(2, 1)), 0, visited, result));
		assertTrue(visited.isEmpty());
		assertTrue(result.isEmpty());
		visited.add(1);
		assertEquals(false, method.invoke(traversal, (RenderOrderCache.Graph) () -> Map.of(0, Map.of()), 0, visited, result));
		assertEquals(Set.of(1), visited);
	}

	@Test
	void scopeEndsOnFailureAndNextSolveSeesChangedEdges() {
		final Map<Object, Map<Object, Integer>> vertices = new LinkedHashMap<>();
		vertices.put(0, edges(1));
		vertices.put(1, edges());
		final RenderOrderCache.Graph graph = () -> vertices;
		assertFalse(FeedbackGraphTraversal.tryTraverse(graph, 0, new HashSet<>(), new ArrayList<>()));
		assertThrows(IllegalStateException.class, () -> FeedbackGraphTraversal.duringSolve(() -> {
			assertTrue(FeedbackGraphTraversal.tryTraverse(graph, 0, new HashSet<>(), new ArrayList<>()));
			throw new IllegalStateException();
		}));
		assertFalse(FeedbackGraphTraversal.tryTraverse(graph, 0, new HashSet<>(), new ArrayList<>()));
		vertices.get(0).clear();
		FeedbackGraphTraversal.duringSolve(() -> {
			final List<Object> result = new ArrayList<>();
			assertTrue(FeedbackGraphTraversal.tryTraverse(graph, 0, new HashSet<>(), result));
			assertEquals(List.of(0), result);
			return null;
		});
	}

	private static Map<Object, Integer> edges(int... targets) {
		final Map<Object, Integer> result = new LinkedHashMap<>();
		for (int target : targets) result.put(target, 1);
		return result;
	}

	private static void dfs(Map<Object, Map<Object, Integer>> graph, Object root, Set<Object> visited, List<Object> result) {
		if (visited.add(root)) {
			for (Object target : graph.get(root).keySet()) dfs(graph, target, visited, result);
			result.add(root);
		}
	}
}
