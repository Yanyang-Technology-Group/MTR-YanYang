package org.mtr.mod.render;

import de.odysseus.ithaka.digraph.Digraph;
import de.odysseus.ithaka.digraph.MapDigraph;
import de.odysseus.ithaka.digraph.util.fas.SimpleFeedbackArcSetProvider;
import it.unimi.dsi.fastutil.objects.Object2IntLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ArrayFeedbackArcSetTest {
	@Test
	void matchesActualIrisSolverForChangingWeightedCyclesAndTies() {
		for (int seed = 0; seed < 80; seed++) {
			final Random random = new Random(seed);
			final int n = 2 + random.nextInt(45);
			final MapDigraph<Integer> graph = new MapDigraph<>();
			for (int i = 0; i < n; i++) graph.add(i);
			for (int i = 0; i < n; i++) graph.put(i, (i + 1) % n, 1 + random.nextInt(5));
			for (int i = 0; i < n * 4; i++) graph.put(random.nextInt(n), random.nextInt(n), 1 + random.nextInt(5));
			assertMatches(graph);
			graph.put(0, 1, 10_000);
			assertMatches(graph);
		}
	}

	@Test
	void preservesSignedWeightOverflowAndLargeComponentIterationLimit() {
		for (int n : new int[]{4, 180}) {
			final MapDigraph<Integer> graph = new MapDigraph<>();
			for (int i = 0; i < n; i++) graph.add(i);
			for (int i = 0; i < n; i++) {
				graph.put(i, (i + 1) % n, Integer.MAX_VALUE);
				graph.put(i, (i + 2) % n, -20);
				graph.put(i, (i + 3) % n, 0);
			}
			assertMatches(graph);
		}
	}

	@Test
	void disconnectedInputFallsBackWithoutChangingTheGraph() {
		final Map<Object, Object2IntMap<Object>> vertices = new LinkedHashMap<>();
		vertices.put(0, new Object2IntLinkedOpenHashMap<>());
		vertices.put(1, new Object2IntLinkedOpenHashMap<>());
		assertNull(ArrayFeedbackArcSet.solve(vertices));
		assertEquals(2, vertices.size());
		assertTrue(vertices.get(0).isEmpty());
	}

	@Test
	void denseComponentsKeepTheOriginalSearchBudget() {
		final MapDigraph<Integer> graph = new MapDigraph<>();
		for (int i = 0; i < 128; i++) graph.add(i);
		for (int i = 0; i < 128; i++) {
			for (int j = 1; j < 100; j++) graph.put(i, (i + j) % 128, (i * j) % 9 + 1);
		}
		assertMatches(graph);
	}

	private static void assertMatches(MapDigraph<Integer> graph) {
		final Map<Object, Object2IntMap<Object>> vertices = new LinkedHashMap<>();
		for (int source : graph.vertices()) {
			final Object2IntMap<Object> edges = new Object2IntLinkedOpenHashMap<>();
			for (int target : graph.targets(source)) edges.put((Object) target, graph.get(source, target).orElseThrow());
			vertices.put(source, edges);
		}
		final Digraph<Integer> expected = new OriginalSolver().solve(graph);
		final int[] order = ArrayFeedbackArcSet.solve(vertices);
		assertNotNull(order);
		assertEquals(graph.getVertexCount(), order.length);
		final MapDigraph<Integer> actual = new MapDigraph<>();
		final Set<Integer> discovered = new HashSet<>();
		for (int source : order) {
			assertTrue(discovered.add(source));
			for (int target : graph.targets(source)) {
				if (!discovered.contains(target)) actual.put(source, target, graph.get(source, target).orElseThrow());
			}
		}
		assertEquals(snapshot(expected), snapshot(actual));
	}

	private static List<String> snapshot(Digraph<Integer> graph) {
		final List<String> result = new ArrayList<>();
		for (int source : graph.vertices()) {
			result.add("vertex:" + source);
			for (int target : graph.targets(source)) result.add(source + ">" + target + ":" + graph.get(source, target).orElseThrow());
		}
		return result;
	}

	private static final class OriginalSolver extends SimpleFeedbackArcSetProvider {
		Digraph<Integer> solve(Digraph<Integer> graph) { return lfas(graph, graph); }
	}
}
