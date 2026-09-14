package org.mtr.mod.render;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntMaps;

import java.util.*;
import java.util.function.Supplier;

// Confined to the render thread. Hashes only filter candidates; hits require exact identity comparisons.
final class GraphResultCache<T> {
	private final int maxSnapshotSize;
	private final int maxEntries;
	private final int maxRecords;
	private final List<Entry<T>> entries = new ArrayList<>();
	private Object[] identities = new Object[0];
	private int[] weights = new int[0];
	private int size;
	private int retainedRecords;
	private long hash;

	GraphResultCache(int maxSnapshotSize, int maxEntries, int maxRecords) {
		this.maxSnapshotSize = Math.min(maxSnapshotSize, maxRecords);
		this.maxEntries = maxEntries;
		this.maxRecords = maxRecords;
	}

	T getOrCompute(Collection<?> graphs, Object qualifier, RenderOrderCache.Graph edgeWeights, Supplier<T> compute) {
		final int previousSize = size;
		size = 0;
		hash = 1;
		boolean valid = append(qualifier, graphs.size());
		for (final Object graph : graphs) {
			if (!valid || !(graph instanceof RenderOrderCache.Graph) || !snapshot(((RenderOrderCache.Graph) graph).mtr$getVertices(), edgeWeights)) {
				Arrays.fill(identities, 0, Math.max(previousSize, size), null);
				size = 0;
				return compute.get();
			}
		}
		if (size < previousSize) {
			Arrays.fill(identities, size, previousSize, null);
		}
		if (!valid || maxEntries <= 0) {
			return compute.get();
		}
		for (int i = 0; i < entries.size(); i++) {
			final Entry<T> entry = entries.get(i);
			if (matches(entry)) {
				if (i > 0) {
					entries.remove(i);
					entries.add(0, entry);
				}
				return entry.result;
			}
		}
		// The original solver can mutate the input or recursively query another cache.
		final Entry<T> entry = new Entry<>(Arrays.copyOf(identities, size), Arrays.copyOf(weights, size), hash);
		entry.result = compute.get();
		while (!entries.isEmpty() && (entries.size() >= maxEntries || retainedRecords + entry.identities.length > maxRecords)) {
			retainedRecords -= entries.remove(entries.size() - 1).identities.length;
		}
		entries.add(0, entry);
		retainedRecords += entry.identities.length;
		return entry.result;
	}

	private boolean snapshot(Map<Object, ? extends Map<Object, Integer>> vertices, RenderOrderCache.Graph edgeWeights) {
		if (!append(null, vertices.size())) {
			return false;
		}
		final Map<Object, ? extends Map<Object, Integer>> weightedVertices = edgeWeights == null ? null : edgeWeights.mtr$getVertices();
		for (final Map.Entry<Object, ? extends Map<Object, Integer>> vertex : vertices.entrySet()) {
			final Map<Object, Integer> edges = vertex.getValue();
			final Map<Object, Integer> weightedEdges = weightedVertices == null ? edges : weightedVertices.get(vertex.getKey());
			if (weightedEdges == null || !append(vertex.getKey(), edges.size())) {
				return false;
			}
			if (edges instanceof Object2IntMap) {
				final Iterator<Object2IntMap.Entry<Object>> iterator = Object2IntMaps.fastIterator((Object2IntMap<Object>) edges);
				while (iterator.hasNext()) {
					final Object2IntMap.Entry<Object> edge = iterator.next();
					if (!appendEdge(edge.getKey(), edge.getIntValue(), edges, weightedEdges)) {
						return false;
					}
				}
			} else {
				for (final Map.Entry<Object, Integer> edge : edges.entrySet()) {
					if (!appendEdge(edge.getKey(), edge.getValue(), edges, weightedEdges)) {
						return false;
					}
				}
			}
		}
		return true;
	}

	private boolean appendEdge(Object target, int weight, Map<Object, Integer> edges, Map<Object, Integer> weightedEdges) {
		// SCCs use a subgraph for topology but read weights from their parent graph.
		if (edges != weightedEdges) {
			if (weightedEdges instanceof Object2IntMap) {
				final Object2IntMap<Object> primitiveWeights = (Object2IntMap<Object>) weightedEdges;
				if (!primitiveWeights.containsKey(target) || primitiveWeights.getInt(target) != weight) {
					return false;
				}
			} else {
				final Integer actualWeight = weightedEdges.get(target);
				if (actualWeight == null || actualWeight != weight) {
					return false;
				}
			}
		}
		return append(target, weight);
	}

	private boolean append(Object identity, int weight) {
		if (size >= maxSnapshotSize) {
			return false;
		}
		if (size >= identities.length) {
			final int capacity = Math.min(maxSnapshotSize, Math.max(64, identities.length * 2));
			identities = Arrays.copyOf(identities, capacity);
			weights = Arrays.copyOf(weights, capacity);
		}
		identities[size] = identity;
		weights[size++] = weight;
		hash = (hash * 31 + System.identityHashCode(identity)) * 31 + weight;
		return true;
	}

	private boolean matches(Entry<T> entry) {
		if (entry.hash != hash || entry.identities.length != size) {
			return false;
		}
		for (int i = 0; i < size; i++) {
			if (entry.identities[i] != identities[i] || entry.weights[i] != weights[i]) {
				return false;
			}
		}
		return true;
	}

	private static final class Entry<T> {
		private final Object[] identities;
		private final int[] weights;
		private final long hash;
		private T result;

		private Entry(Object[] identities, int[] weights, long hash) {
			this.identities = identities;
			this.weights = weights;
			this.hash = hash;
		}
	}
}
