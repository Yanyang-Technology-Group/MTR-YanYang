package org.mtr.mod.render;

import java.util.*;
import java.util.function.Supplier;

public final class RenderOrderCache {
	private static final ThreadLocal<RenderOrderCache> ACTIVE = new ThreadLocal<>();
	private final GraphResultCache<List<?>> orders;
	private final GraphResultCache<Object> feedbackEdges;

	public RenderOrderCache() {
		this(65536);
	}

	RenderOrderCache(int maxSize) {
		orders = new GraphResultCache<>(maxSize, 8, maxSize * 4);
		feedbackEdges = new GraphResultCache<>(maxSize, 64, maxSize * 2);
	}

	public List<?> getOrCompute(Collection<?> graphs, Supplier<List<?>> compute) {
		// Iris removes rendered transparency categories from the returned list.
		return new ArrayList<>(orders.getOrCompute(graphs, null, null, () -> {
			final RenderOrderCache previous = ACTIVE.get();
			ACTIVE.set(this);
			try {
				return new ArrayList<>(compute.get());
			} finally {
				if (previous == null) {
					ACTIVE.remove();
				} else {
					ACTIVE.set(previous);
				}
			}
		}));
	}

	public static Object getFeedbackEdges(Object provider, Object graph, Object weights, Supplier<Object> compute) {
		final RenderOrderCache active = ACTIVE.get();
		if (active == null || !(weights instanceof Graph)) {
			return compute.get();
		}
		// Iris only reads the feedback graph. Its original solver still handles every cache miss.
		return active.feedbackEdges.getOrCompute(Collections.singletonList(graph), provider, (Graph) weights, compute);
	}

	static boolean isActive() {
		return ACTIVE.get() != null;
	}

	public interface Graph {
		Map<Object, ? extends Map<Object, Integer>> mtr$getVertices();
	}
}
