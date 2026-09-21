package org.mtr.mod.data;

import org.junit.jupiter.api.Test;
import org.mtr.core.data.*;
import org.mtr.core.path.SidingPathFinder;
import org.mtr.core.path.compute.PathBatcher;
import org.mtr.core.path.compute.PathComputePool;
import org.mtr.core.path.compute.RailSearch;
import org.mtr.core.tool.Angle;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.lang.reflect.Method;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class RailPathSnapshotTest {
	@Test
	void matchesEmbeddedOriginalPathsIncludingBranchesTurnbacksAndDisconnectedTracks() throws Exception {
		for (int seed = 0; seed < 40; seed++) {
			Data data = network(seed);
			RailPathSnapshot snapshot = new RailPathSnapshot(data, seed);
			RailSearch search = new RailSearch();
			for (int destination = 2; destination < 12; destination++) {
				Platform start = platform(data, 0, 1), end = platform(data, destination, destination + 1);
				end.setDwellTime(17);
				ObjectArrayList<PathData> expected = original(data, start, end);
				int[] nodes = search.search(snapshot.graph, snapshot.index(start.getRandomPosition()), snapshot.index(end.getRandomPosition()));
				assertNotNull(nodes, "search exceeded budget seed=" + seed);
				assertArrayEquals(originalNodes(data, start, end, snapshot), nodes, "seed=" + seed + " destination=" + destination);
				ObjectArrayList<PathData> actual = snapshot.assemble(data, nodes, start, end, 3);
				if (actual == null) assertTrue(expected.isEmpty(), "Missing live rail must fall back to the original failure");
				else assertPath(expected, actual);
			}
		}
	}

	@Test
	void parallelRequestsMatchOriginalInStableOrderForAllWorkerCounts() throws Exception {
		Data data = network(42);
		RailPathSnapshot snapshot = new RailPathSnapshot(data, 4);
		ObjectArrayList<ObjectArrayList<PathData>> expected = new ObjectArrayList<>();
		Platform start = platform(data, 0, 1);
		for (int j = 2; j < 12; j++) expected.add(original(data, start, platform(data, j, j + 1)));
		for (int workers : new int[]{1, 2, 4, 6, 8, 10, 12}) {
			try (PathComputePool pool = new PathComputePool(workers, 32, true)) {
				PathBatcher batcher = new PathBatcher(pool, snapshot.graph);
				ObjectArrayList<PathBatcher.Ticket> tickets = new ObjectArrayList<>();
				for (int j = 2; j < 12; j++) tickets.add(batcher.request(snapshot.index(start.getRandomPosition()), snapshot.index(position(j))));
				batcher.flush();
				long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
				for (int j = 0; j < tickets.size(); j++) {
					PathBatcher.Ticket ticket = tickets.get(j);
					while (!ticket.ready() && System.nanoTime() < deadline) { batcher.collect(); Thread.sleep(1); }
					assertTrue(ticket.ready());
					ObjectArrayList<PathData> actual = snapshot.assemble(data, ticket.result(4), start, platform(data, j + 2, j + 3), 3);
					if (actual == null) assertTrue(expected.get(j).isEmpty()); else assertPath(expected.get(j), actual);
				}
			}
		}
	}

	@Test
	void liveTopologyMutationDoesNotChangeWorkerSnapshotAndNewEpochUsesNewRails() {
		Data data = network(0);
		RailPathSnapshot old = new RailPathSnapshot(data, 1);
		int from = old.index(position(0)), to = old.index(position(10));
		int[] expected = new RailSearch().search(old.graph, from, to);
		assertTrue(expected.length > 0);
		data.positionsToRail.values().forEach(Object2ObjectOpenHashMap::clear);
		assertArrayEquals(expected, new RailSearch().search(old.graph, from, to));
		RailPathSnapshot current = new RailPathSnapshot(data, 2);
		assertArrayEquals(new int[0], new RailSearch().search(current.graph, current.index(position(0)), current.index(position(10))));
	}

	static Data network(int seed) {
		Data data = new Data() { };
		for (int i = 0; i < 14; i++) data.positionsToRail.put(position(i), new Object2ObjectOpenHashMap<>());
		Random random = new Random(seed);
		for (int i = 0; i < 13; i++) connect(data, i, i + 1, seed % 2 == 0, false);
		for (int i = 1; i < 10; i++) {
			if (random.nextBoolean()) connect(data, i, i + 2, random.nextBoolean(), true);
		}
		if (seed % 7 == 0 && seed != 0) data.positionsToRail.get(position(9)).clear();
		return data;
	}

	static Platform platform(Data data, int a, int b) { return new Platform(position(a), position(b), TransportMode.TRAIN, data); }
	static Position position(int index) { return new Position(index * 100L, 64, 0); }

	static void connect(Data data, int a, int b, boolean reverse, boolean turnback) {
		Rail rail = turnback ? Rail.newTurnBackRail(position(a), Angle.E, position(b), Angle.W, Rail.Shape.QUADRATIC, 0, new ObjectArrayList<>(), TransportMode.TRAIN) :
			Rail.newRail(position(a), Angle.E, position(b), Angle.W, Rail.Shape.QUADRATIC, 0, new ObjectArrayList<>(), 80, reverse ? 80 : 0, false, true, true, false, false, TransportMode.TRAIN);
		data.positionsToRail.get(position(a)).put(position(b), rail);
		data.positionsToRail.get(position(b)).put(position(a), rail);
	}

	@SuppressWarnings("unchecked")
	static ObjectArrayList<PathData> original(Data data, Platform start, Platform end) throws Exception {
		SidingPathFinder<Station, Platform, Station, Platform> finder = new SidingPathFinder<>(data, start, end, 3);
		Method tick = SidingPathFinder.class.getDeclaredMethod("tick", long.class);
		tick.setAccessible(true);
		for (int i = 0; i < 2_000_000; i++) {
			ObjectArrayList<PathData> result = (ObjectArrayList<PathData>) tick.invoke(finder, 0L);
			if (result != null) return result;
		}
		throw new AssertionError("Original search did not finish");
	}

	static int[] originalNodes(Data data, Platform start, Platform end, RailPathSnapshot snapshot) throws Exception {
		SidingPathFinder<Station, Platform, Station, Platform> finder = new SidingPathFinder<>(data, start, end, 3);
		Method find = org.mtr.core.path.PathFinder.class.getDeclaredMethod("findPath");
		find.setAccessible(true);
		for (int i = 0; i < 2_000_000; i++) {
			ObjectArrayList<?> result = (ObjectArrayList<?>) find.invoke(finder);
			if (result != null) {
				int[] nodes = new int[result.size()];
				for (int j = 0; j < nodes.length; j++) {
					Object node = result.get(j).getClass().getField("node").get(result.get(j));
					var position = node.getClass().getDeclaredField("position");
					position.setAccessible(true);
					nodes[j] = snapshot.index((Position) position.get(node));
				}
				return nodes;
			}
		}
		throw new AssertionError("Original raw search did not finish");
	}

	static void assertPath(ObjectArrayList<PathData> expected, ObjectArrayList<PathData> actual) {
		assertNotNull(actual);
		assertEquals(expected.size(), actual.size());
		for (int i = 0; i < expected.size(); i++) {
			PathData a = expected.get(i), b = actual.get(i);
			assertEquals(a.getOrderedPosition1(), b.getOrderedPosition1());
			assertEquals(a.getOrderedPosition2(), b.getOrderedPosition2());
			assertEquals(a.getDwellTime(), b.getDwellTime());
			assertEquals(a.getStopIndex(), b.getStopIndex());
			assertEquals(a.getRailLength(), b.getRailLength());
			assertEquals(a.getSpeedLimitMetersPerMillisecond(), b.getSpeedLimitMetersPerMillisecond());
			assertEquals(a.getStartDistance(), b.getStartDistance());
			assertEquals(a.getEndDistance(), b.getEndDistance());
		}
	}
}
