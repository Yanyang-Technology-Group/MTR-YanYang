package org.mtr.mod.client;

import org.junit.jupiter.api.Test;
import org.mtr.core.data.Lift;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.data.TransportMode;
import org.mtr.core.serializer.JsonReader;
import org.mtr.core.tool.Angle;
import org.mtr.core.tool.Utilities;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;

import static org.junit.jupiter.api.Assertions.*;

class MinecraftClientDataTest {

	@Test
	void dynamicSyncPreservesRailAdjacencyAndWrappers() {
		final MinecraftClientData data = new MinecraftClientData();
		final Position start = new Position(0, 0, 0);
		final Rail rail = Rail.newPlatformRail(start, Angle.fromAngle(0), new Position(40, 0, 0), Angle.fromAngle(0), Rail.Shape.QUADRATIC, 0, new ObjectArrayList<>(), TransportMode.TRAIN);
		data.rails.add(rail);
		data.sync();
		final Object adjacency = data.positionsToRail.get(start);
		final MinecraftClientData.RailWrapper wrapper = data.railWrapperList.get(rail.getHexId());
		data.syncDynamic();
		assertSame(adjacency, data.positionsToRail.get(start));
		assertSame(wrapper, data.railWrapperList.get(rail.getHexId()));
	}

	@Test
	void replacingARailRefreshesItsCullingBounds() {
		final MinecraftClientData data = new MinecraftClientData();
		final Rail original = Rail.newPlatformRail(new Position(0, 0, 0), Angle.E, new Position(40, 0, 0), Angle.E, Rail.Shape.QUADRATIC, 0, new ObjectArrayList<>(), TransportMode.TRAIN);
		data.rails.add(original);
		data.sync();
		final MinecraftClientData.RailWrapper oldWrapper = data.railWrapperList.get(original.getHexId());
		final Rail replacement = Rail.copy(original, Rail.Shape.CABLE, 10);
		data.rails.clear();
		data.rails.add(replacement);
		data.sync();
		final MinecraftClientData.RailWrapper wrapper = data.railWrapperList.get(original.getHexId());
		assertNotSame(oldWrapper, wrapper);
		assertSame(replacement, wrapper.getRail());
		assertEquals(replacement.railMath.minY, wrapper.startVector.y);
		assertEquals(replacement.railMath.maxY, wrapper.endVector.y);
	}

	@Test
	void liftModelsSurviveMovementUpdatesButRebuildForGeometryChanges() {
		final MinecraftClientData data = new MinecraftClientData();
		final Lift lift = new Lift(data);
		lift.setDimensions(3, 3, 3, 0, 0, 0);
		data.lifts.add(lift);
		data.syncDynamic();
		final MinecraftClientData.LiftWrapper wrapper = data.liftWrapperList.get(lift.getId());
		final Object first = wrapper.getModel();
		assertSame(first, wrapper.getModel());
		final Lift replacement = new Lift(new JsonReader(Utilities.getJsonObjectFromData(lift)), data);
		data.lifts.clear();
		data.lifts.add(replacement);
		data.syncDynamic();
		assertSame(first, wrapper.getModel());
		replacement.setDimensions(4, 3, 3, 1, 0, 0);
		final Object resized = wrapper.getModel();
		assertNotSame(first, resized);
		replacement.setIsDoubleSided(!replacement.getIsDoubleSided());
		final Object doubleSided = wrapper.getModel();
		assertNotSame(resized, doubleSided);
		assertSame(doubleSided, wrapper.getModel());
	}

	@Test
	void dynamicSyncReplacesAndRemovesLiftsWithoutLosingWrapperState() {
		final MinecraftClientData data = new MinecraftClientData();
		final Lift lift = new Lift(data);
		data.lifts.add(lift);
		data.syncDynamic();
		final MinecraftClientData.LiftWrapper wrapper = data.liftWrapperList.get(lift.getId());
		wrapper.shouldRender = true;
		final Lift replacement = new Lift(new JsonReader(Utilities.getJsonObjectFromData(lift)), data);
		data.lifts.clear();
		data.lifts.add(replacement);
		data.syncDynamic();
		assertSame(wrapper, data.liftWrapperList.get(lift.getId()));
		assertSame(replacement, wrapper.getLift());
		assertTrue(wrapper.shouldRender);
		data.lifts.clear();
		data.vehicleIdToPersistentVehicleData.put(123, null);
		data.syncDynamic();
		assertTrue(data.liftWrapperList.isEmpty());
		assertTrue(data.vehicleIdToPersistentVehicleData.isEmpty());
	}
}
