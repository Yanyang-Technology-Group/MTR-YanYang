package org.mtr.client;

import org.jspecify.annotations.Nullable;
import org.mtr.core.data.Data;
import org.mtr.core.data.Platform;
import org.mtr.core.data.Position;
import org.mtr.core.data.SimpleAreaBase;
import org.mtr.core.data.Station;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Client-side spatial index over stations and platforms.
 *
 * <p>Many block entity renderers (route maps, PIDS, station name signs, route signs)
 * query the station or the closest platform for their block position every frame.
 * Scanning every station / platform each time is O(N) per block entity per frame; this
 * index buckets areas into a fixed-size grid so queries only inspect nearby entries.</p>
 *
 * <p>Queries are additionally memoised in small LRU caches, because dozens of block
 * entities at the same position ask the same question every frame.</p>
 *
 * <p>Ported from the YanYang 4.0.5 optimisation series.</p>
 */
public final class ClientSpatialIndex {

	private final Data data;
	private final Grid<Station> stations = new Grid<>();
	private final Grid<Platform> platforms = new Grid<>();
	private final Map<Position, @Nullable Station> stationQueries = boundedCache(1024);
	// Every route-map block queries separately; a hub can exceed 1,024 in one frame.
	private final Map<PlatformQuery, @Nullable Platform> platformQueries = boundedCache(32768);

	public ClientSpatialIndex(Data data) {
		this.data = data;
	}

	/**
	 * Rebuilds the index from the current data set. Called from
	 * {@link MinecraftClientData#sync()} — static data changes only.
	 */
	public void rebuild() {
		stations.clear();
		platforms.clear();
		stationQueries.clear();
		platformQueries.clear();
		for (final Station station : data.stations) {
			if (SimpleAreaBase.validCorners(station)) {
				stations.add(station, station.getMinX(), station.getMinZ(), station.getMaxX(), station.getMaxZ());
			}
		}
		for (final Platform platform : data.platforms) {
			final Position start = platform.getRandomPosition();
			final Position end = platform.getOtherPosition(start);
			platforms.add(platform, Math.min(start.getX(), end.getX()), Math.min(start.getZ(), end.getZ()), Math.max(start.getX(), end.getX()), Math.max(start.getZ(), end.getZ()));
		}
	}

	/**
	 * Finds the station containing the given position, matching the iteration order of
	 * {@code data.stations} for tie-breaking.
	 */
	@Nullable
	public Station findStation(Position position) {
		if (stationQueries.containsKey(position)) {
			return stationQueries.get(position);
		}
		Station result = null;
		for (final Entry<Station> entry : stations.query(position, 0)) {
			if (entry.value.inArea(position)) {
				result = entry.value;
				break;
			}
		}
		stationQueries.put(position, result);
		return result;
	}

	/**
	 * Finds the platform closest to the given position (by approximate closest distance)
	 * among all platforms within the radius, matching the original stream semantics for
	 * tie-breaking.
	 */
	@Nullable
	public Platform findClosePlatform(Position position, int radius) {
		final PlatformQuery query = new PlatformQuery(position, radius);
		if (platformQueries.containsKey(query)) {
			return platformQueries.get(query);
		}
		Platform result = null;
		double distance = Double.MAX_VALUE;
		for (final Entry<Platform> entry : platforms.query(position, Math.max(0, radius))) {
			final Platform platform = entry.value;
			if (platform.closeTo(position, radius)) {
				final double candidateDistance = platform.getApproximateClosestDistance(position);
				if (result == null || Double.compare(candidateDistance, distance) < 0) {
					result = platform;
					distance = candidateDistance;
				}
			}
		}
		platformQueries.put(query, result);
		return result;
	}

	private static <K, V> Map<K, V> boundedCache(int capacity) {
		return new LinkedHashMap<K, V>(16, 0.75F, true) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
				return size() > capacity;
			}
		};
	}

	private static final class Grid<T> {

		private static final int CELL_SIZE = 128;
		private static final int MAX_CELLS = 256;
		private final Long2ObjectOpenHashMap<ObjectArrayList<Entry<T>>> cells = new Long2ObjectOpenHashMap<>();
		private final ObjectArrayList<Entry<T>> all = new ObjectArrayList<>();
		private final ObjectArrayList<Entry<T>> oversized = new ObjectArrayList<>();

		private void clear() {
			cells.clear();
			all.clear();
			oversized.clear();
		}

		private void add(T value, long minX, long minZ, long maxX, long maxZ) {
			final Entry<T> entry = new Entry<>(value, all.size());
			all.add(entry);
			final long x1 = Math.floorDiv(minX, CELL_SIZE);
			final long z1 = Math.floorDiv(minZ, CELL_SIZE);
			final long x2 = Math.floorDiv(maxX, CELL_SIZE);
			final long z2 = Math.floorDiv(maxZ, CELL_SIZE);
			// Large custom areas must not allocate millions of grid entries.
			if (tooLarge(x1, z1, x2, z2)) {
				oversized.add(entry);
				return;
			}
			for (long x = x1; x <= x2; x++) {
				for (long z = z1; z <= z2; z++) {
					cells.computeIfAbsent(key(x, z), ignored -> new ObjectArrayList<>()).add(entry);
				}
			}
		}

		private ObjectArrayList<Entry<T>> query(Position position, int radius) {
			final long x1 = (long) Math.floor(((double) position.getX() - radius) / CELL_SIZE);
			final long z1 = (long) Math.floor(((double) position.getZ() - radius) / CELL_SIZE);
			final long x2 = (long) Math.floor(((double) position.getX() + radius) / CELL_SIZE);
			final long z2 = (long) Math.floor(((double) position.getZ() + radius) / CELL_SIZE);
			if (tooLarge(x1, z1, x2, z2)) {
				return all;
			}
			final ObjectOpenHashSet<Entry<T>> matches = new ObjectOpenHashSet<>(oversized);
			for (long x = x1; x <= x2; x++) {
				for (long z = z1; z <= z2; z++) {
					final ObjectArrayList<Entry<T>> cell = cells.get(key(x, z));
					if (cell != null) {
						matches.addAll(cell);
					}
				}
			}
			final ObjectArrayList<Entry<T>> result = new ObjectArrayList<>(matches);
			// Preserve the original set order for overlapping areas and distance ties.
			result.sort(Comparator.comparingInt(entry -> entry.order));
			return result;
		}

		private static boolean tooLarge(long x1, long z1, long x2, long z2) {
			return x1 < Integer.MIN_VALUE || z1 < Integer.MIN_VALUE || x2 > Integer.MAX_VALUE || z2 > Integer.MAX_VALUE ||
				x2 - x1 >= MAX_CELLS || z2 - z1 >= MAX_CELLS || (x2 - x1 + 1) * (z2 - z1 + 1) > MAX_CELLS;
		}

		private static long key(long x, long z) {
			return (x << 32) | (z & 0xFFFFFFFFL);
		}
	}

	private static final class Entry<T> {

		private final T value;
		private final int order;

		private Entry(T value, int order) {
			this.value = value;
			this.order = order;
		}
	}

	private static final class PlatformQuery {

		private final Position position;
		private final int radius;

		private PlatformQuery(Position position, int radius) {
			this.position = position;
			this.radius = radius;
		}

		@Override
		public boolean equals(Object other) {
			return other instanceof PlatformQuery && radius == ((PlatformQuery) other).radius && position.equals(((PlatformQuery) other).position);
		}

		@Override
		public int hashCode() {
			return 31 * position.hashCode() + radius;
		}
	}
}
