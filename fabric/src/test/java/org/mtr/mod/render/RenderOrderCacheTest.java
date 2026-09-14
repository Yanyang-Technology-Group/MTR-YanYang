package org.mtr.mod.render;

import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class RenderOrderCacheTest {
	private final Object a = new Object(), b = new Object(), c = new Object();

	@Test
	void repeatsReuseOrderingDespiteOriginalSolverRemovingCycleEdges() {
		final RenderOrderCache cache = new RenderOrderCache();
		final AtomicInteger solves = new AtomicInteger();
		for (int frame = 0; frame < 100; frame++) {
			final TestGraph graph = cycle();
			final List<?> result = cache.getOrCompute(List.of(graph), () -> {
				solves.incrementAndGet();
				graph.vertices.get(c).remove(a);
				return new ArrayList<>(List.of(a, b, c));
			});
			assertEquals(List.of(a, b, c), result);
			// Iris removes whole transparency categories from its returned list.
			result.clear();
		}
		assertEquals(1, solves.get());
	}

	@Test
	void alternatingWorldAndMinimapGraphsKeepBothOrders() {
		final RenderOrderCache cache = new RenderOrderCache();
		final AtomicInteger worldSolves = new AtomicInteger();
		final AtomicInteger minimapSolves = new AtomicInteger();
		for (int frame = 0; frame < 100; frame++) {
			assertEquals(List.of(a, b, c), cache.getOrCompute(List.of(cycle()), () -> {
				worldSolves.incrementAndGet();
				return List.of(a, b, c);
			}));
			assertEquals(List.of(a, b), cache.getOrCompute(List.of(new TestGraph().edge(a, b, 1)), () -> {
				minimapSolves.incrementAndGet();
				return List.of(a, b);
			}));
		}
		assertEquals(1, worldSolves.get());
		assertEquals(1, minimapSolves.get());
	}

	@Test
	void changingAnotherComponentDoesNotRepeatTheStableCycleSolver() {
		final RenderOrderCache cache = new RenderOrderCache();
		final Object provider = new Object(), feedback = new Object();
		final AtomicInteger solves = new AtomicInteger();
		for (int frame = 0; frame < 20; frame++) {
			final TestGraph stable = cycle();
			final TestGraph changing = new TestGraph().edge(a, b, frame + 1);
			cache.getOrCompute(List.of(stable, changing), () -> {
				assertSame(feedback, RenderOrderCache.getFeedbackEdges(provider, stable, stable, () -> {
					solves.incrementAndGet();
					return feedback;
				}));
				return List.of(a, b, c);
			});
		}
		assertEquals(1, solves.get());
		assertNotSame(feedback, RenderOrderCache.getFeedbackEdges(provider, cycle(), cycle(), Object::new));
	}

	@Test
	void failedOrderSolveAlwaysLeavesTheIrisScope() {
		final RenderOrderCache cache = new RenderOrderCache();
		final Object provider = new Object(), feedback = new Object();
		assertThrows(IllegalStateException.class, () -> cache.getOrCompute(List.of(cycle()), () -> {
			RenderOrderCache.getFeedbackEdges(provider, cycle(), cycle(), () -> feedback);
			throw new IllegalStateException();
		}));
		assertNotSame(feedback, RenderOrderCache.getFeedbackEdges(provider, cycle(), cycle(), Object::new));
	}

	@Test
	void nestedOrderingRestoresTheOuterCycleCacheAndUnknownWeightsBypassIt() {
		final RenderOrderCache outer = new RenderOrderCache(), inner = new RenderOrderCache();
		final Object provider = new Object(), outerEdges = new Object(), innerEdges = new Object();
		outer.getOrCompute(List.of(cycle()), () -> {
			assertSame(outerEdges, RenderOrderCache.getFeedbackEdges(provider, cycle(), cycle(), () -> outerEdges));
			inner.getOrCompute(List.of(cycle()), () -> {
				assertSame(innerEdges, RenderOrderCache.getFeedbackEdges(provider, cycle(), cycle(), () -> innerEdges));
				return List.of(a, b, c);
			});
			assertSame(outerEdges, RenderOrderCache.getFeedbackEdges(provider, cycle(), cycle(), () -> fail("outer scope was lost")));
			assertNotSame(outerEdges, RenderOrderCache.getFeedbackEdges(provider, cycle(), new Object(), Object::new));
			return List.of(a, b, c);
		});
	}

	@Test
	void weightsEdgesVertexOrderAndCategoriesInvalidateIndependently() {
		final RenderOrderCache cache = new RenderOrderCache();
		final AtomicInteger solves = new AtomicInteger();
		final Supplier<List<?>> solve = () -> {
			solves.incrementAndGet();
			return new ArrayList<>(List.of(a, b, c));
		};
		cache.getOrCompute(List.of(cycle()), solve);
		final TestGraph weighted = cycle();
		weighted.edge(a, b, 2);
		cache.getOrCompute(List.of(weighted), solve);
		final TestGraph edge = cycle();
		edge.vertices.get(a).clear();
		edge.edge(a, c, 1);
		cache.getOrCompute(List.of(edge), solve);
		final TestGraph ordered = new TestGraph();
		ordered.edge(c, a, 1).edge(a, b, 1).edge(b, c, 1);
		cache.getOrCompute(List.of(ordered), solve);
		cache.getOrCompute(List.of(ordered, new TestGraph()), solve);
		cache.getOrCompute(List.of(new TestGraph(), ordered), solve);
		assertEquals(6, solves.get());
	}

	@Test
	void equalButReplacedRenderLayersAreNotReused() {
		final RenderOrderCache cache = new RenderOrderCache();
		final AtomicInteger solves = new AtomicInteger();
		for (int i = 0; i < 2; i++) {
			final Object layer = new String("same");
			final TestGraph graph = new TestGraph().edge(layer, b, 1);
			final List<?> result = cache.getOrCompute(List.of(graph), () -> {
				solves.incrementAndGet();
				return List.of(layer, b);
			});
			assertSame(layer, result.get(0));
		}
		assertEquals(2, solves.get());
	}

	@Test
	void oversizedAndUnknownGraphsUseOriginalSolver() {
		final RenderOrderCache cache = new RenderOrderCache(5);
		final AtomicInteger solves = new AtomicInteger();
		final Supplier<List<?>> solve = () -> {
			solves.incrementAndGet();
			return List.of(a, b, c);
		};
		cache.getOrCompute(List.of(cycle()), solve);
		cache.getOrCompute(List.of(cycle()), solve);
		cache.getOrCompute(List.of(new Object()), solve);
		cache.getOrCompute(List.of(new Object()), solve);
		assertEquals(4, solves.get());
	}

	private TestGraph cycle() {
		return new TestGraph().edge(a, b, 1).edge(b, c, 1).edge(c, a, 1);
	}

	private static final class TestGraph implements RenderOrderCache.Graph {
		private final Map<Object, Map<Object, Integer>> vertices = new LinkedHashMap<>();

		TestGraph edge(Object from, Object to, int weight) {
			vertices.computeIfAbsent(from, ignored -> new LinkedHashMap<>()).put(to, weight);
			vertices.computeIfAbsent(to, ignored -> new LinkedHashMap<>());
			return this;
		}

		@Override
		public Map<Object, ? extends Map<Object, Integer>> mtr$getVertices() {
			return vertices;
		}
	}
}
