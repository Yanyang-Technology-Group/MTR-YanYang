package org.mtr.mod.client;

import org.junit.jupiter.api.Test;
import org.mtr.core.data.*;
import org.mtr.core.tool.Angle;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.util.Comparator;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class ClientSpatialIndexTest {

	@Test
	void denseStationQueriesSurviveTheNextFrame() {
		final ClientData data = new ClientData();
		final int[] coordinateReads = {0};
		final Platform platform = new Platform(new Position(0, 0, 0), new Position(4000, 0, 0), TransportMode.TRAIN, data);
		data.platforms.add(platform);
		final ClientSpatialIndex index = new ClientSpatialIndex(data);
		index.rebuild();
		final Position[] positions = new Position[3000];
		for (int x = 0; x < positions.length; x++) {
			positions[x] = new Position(x, 0, 0) {
				@Override
				public long getX() {
					coordinateReads[0]++;
					return super.getX();
				}
			};
		}
		int firstFrameReads = 0;
		for (int frame = 0; frame < 3; frame++) {
			for (final Position position : positions) {
				assertSame(platform, index.findClosePlatform(position, 5));
			}
			if (frame == 0) {
				firstFrameReads = coordinateReads[0];
			} else {
				assertEquals(firstFrameReads, coordinateReads[0], "Static signs must not repeat spatial searches every frame");
			}
		}
		index.rebuild();
		index.findClosePlatform(positions[0], 5);
		assertTrue(coordinateReads[0] > firstFrameReads);
	}

	@Test
	void stationsMatchBruteForceIncludingOverlapAndNegativeCoordinates() {
		final ClientData data = new ClientData();
		final Random random = new Random(90210);
		for (int i = 0; i < 200; i++) {
			final Station station = new Station(data);
			final int x = random.nextInt(4096) - 2048;
			final int z = random.nextInt(4096) - 2048;
			station.setCorners(new Position(x, -10, z), new Position(x + 300, 100, z + 300));
			data.stations.add(station);
		}
		final ClientSpatialIndex index = new ClientSpatialIndex(data);
		index.rebuild();
		for (int i = 0; i < 2000; i++) {
			final Position position = new Position(random.nextInt(4096) - 2048, random.nextInt(150) - 20, random.nextInt(4096) - 2048);
			assertSame(data.stations.stream().filter(station -> station.inArea(position)).findFirst().orElse(null), index.findStation(position));
		}
	}

	@Test
	void nearestPlatformMatchesBruteForceAndInvalidatesAfterEdit() {
		final ClientData data = new ClientData();
		addPlatform(data, new Position(-400, 0, 0), new Position(400, 0, 0));
		addPlatform(data, new Position(-400, 0, 2), new Position(400, 0, 2));
		addPlatform(data, new Position(-2000, 0, 20), new Position(-1900, 0, 20));
		data.sync();
		final ClientSpatialIndex index = new ClientSpatialIndex(data);
		index.rebuild();
		for (int x = -2000; x < 2000; x += 17) {
			for (int z = -10; z < 30; z += 3) {
				final Position position = new Position(x, 0, z);
				final Platform expected = data.platforms.stream().filter(platform -> platform.closeTo(position, 5)).min(Comparator.comparingDouble(platform -> platform.getApproximateClosestDistance(position, data))).orElse(null);
				assertSame(expected, index.findClosePlatform(position, 5));
			}
		}
		final Position position = new Position(0, 0, 0);
		assertNotNull(index.findClosePlatform(position, 5));
		data.platforms.clear();
		index.rebuild();
		assertNull(index.findClosePlatform(position, 5));
	}

	@Test
	void hugeAreasDoNotExpandIntoUnboundedCellLists() {
		final ClientData data = new ClientData();
		final Station station = new Station(data);
		station.setCorners(new Position(-30000000, -64, -30000000), new Position(30000000, 320, 30000000));
		data.stations.add(station);
		final ClientSpatialIndex index = new ClientSpatialIndex(data);
		index.rebuild();
		assertSame(station, index.findStation(new Position(-100, 50, 100)));
		station.setCorners(new Position(0, 0, 0), new Position(10, 10, 10));
		index.rebuild();
		assertNull(index.findStation(new Position(-100, 50, 100)));
	}

	private static void addPlatform(ClientData data, Position start, Position end) {
		data.rails.add(Rail.newPlatformRail(start, Angle.E, end, Angle.E, Rail.Shape.QUADRATIC, 0, new ObjectArrayList<>(), TransportMode.TRAIN));
		data.platforms.add(new Platform(start, end, TransportMode.TRAIN, data));
	}
}
