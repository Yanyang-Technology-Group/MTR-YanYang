package org.mtr.mod.data;

import org.mtr.core.data.Data;
import org.mtr.core.data.Depot;
import org.mtr.core.data.PathData;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.data.Siding;
import org.mtr.core.data.Vehicle;
import org.mtr.core.data.VehicleExtraData;
import org.mtr.core.operation.UpdateDataResponse;
import org.mtr.core.path.SidingPathFinder;
import org.mtr.core.serializer.JsonReader;
import org.mtr.core.tool.Utilities;
import org.mtr.libraries.com.google.gson.JsonObject;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectList;
import org.mtr.mod.mixin.SidingRailGeometryAccessor;
import org.mtr.mod.mixin.SidingRailLengthAccessor;
import org.mtr.mod.mixin.UpdateDataResponseRailGeometryAccessor;
import org.mtr.mod.mixin.VehicleRailGeometryAccessor;
import org.mtr.mod.mixin.VehicleSchemaRailGeometryAccessor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class RailGeometryRefresh {

	private final Data data;
	private final Set<Depot> depots = new LinkedHashSet<>();
	private final Map<Siding, List<VehicleState>> sidings = new LinkedHashMap<>();
	private final Set<Siding> geometryOnlySidings = new LinkedHashSet<>();

	private RailGeometryRefresh(Data data) {
		this.data = data;
	}

	public static RailGeometryRefresh capture(Data data, ObjectArrayList<Rail> updates) {
		Set<String> lengthChangedRails = new HashSet<>();
		Set<String> geometryChangedRails = new HashSet<>();
		for (Rail rail : updates) {
			Rail previous = data.railIdMap.get(rail.getHexId());
			if (previous != null && rail.isValid()) {
				if (Double.compare(previous.railMath.getLength(), rail.railMath.getLength()) != 0) {
					lengthChangedRails.add(rail.getHexId());
				} else if (previous.railMath.getShape() != rail.railMath.getShape()
						|| Double.compare(previous.railMath.getVerticalRadius(), rail.railMath.getVerticalRadius()) != 0) {
					geometryChangedRails.add(rail.getHexId());
				}
			}
		}
		if (lengthChangedRails.isEmpty() && geometryChangedRails.isEmpty()) {
			return null;
		}
		RailGeometryRefresh refresh = new RailGeometryRefresh(data);
		for (Depot depot : data.depots) {
			if (containsChangedRail(depot.getPath(), lengthChangedRails)) {
				refresh.depots.add(depot);
			}
		}
		for (Siding siding : data.sidings) {
			if (containsChangedRail(data, siding, lengthChangedRails)) {
				refresh.sidings.put(siding, new ArrayList<>());
				if (siding.area != null) {
					refresh.depots.add(siding.area);
				}
			} else if (containsChangedRail(data, siding, geometryChangedRails)) {
				refresh.geometryOnlySidings.add(siding);
			}
		}
		for (Depot depot : refresh.depots) {
			for (Siding siding : depot.savedRails) {
				refresh.sidings.putIfAbsent(siding, new ArrayList<>());
			}
		}
		refresh.geometryOnlySidings.removeAll(refresh.sidings.keySet());
		refresh.sidings.forEach((siding, states) -> sidingAccess(siding).mtr$getVehicles().forEach(vehicle -> states.add(new VehicleState(siding, vehicle))));
		return refresh;
	}

	public static void refreshSidingLengths(Data data) {
		for (Siding siding : data.sidings) {
			Rail rail = getSidingRail(data, siding);
			if (rail != null) {
				((SidingRailLengthAccessor) (Object) siding).mtr$setRailLength(Siding.getRailLength(rail.railMath.getLength()));
			}
		}
	}

	public void apply(UpdateDataResponse response) {
		for (Depot depot : depots) {
			SidingPathFinder.generatePathDataDistances(depot.getPath(), 0);
		}
		for (Map.Entry<Siding, List<VehicleState>> entry : sidings.entrySet()) {
			Siding siding = entry.getKey();
			SidingRailGeometryAccessor accessor = sidingAccess(siding);
			PathData defaultPath = accessor.mtr$getDefaultPath();
			if (defaultPath != null) {
				ObjectArrayList<PathData> defaultPaths = ObjectArrayList.of(defaultPath);
				PathData.writePathCache(defaultPaths, data, siding.getTransportMode());
				accessor.mtr$setDefaultPath(new PathData(defaultPath, 0, defaultPath.getRailLength()));
			}
			accessor.mtr$generateDistancesAndTimeSegments();
			for (VehicleState state : entry.getValue()) {
				state.refresh(siding, data);
				accessor.mtr$getVehicles().add(state.vehicle);
			}
			ObjectArrayList<Siding> responseSidings = ((UpdateDataResponseRailGeometryAccessor) (Object) response).mtr$getSidings();
			if (!responseSidings.contains(siding)) {
				responseSidings.add(siding);
			}
		}
		for (Depot depot : depots) {
			depot.generatePlatformDirectionsAndWriteDeparturesToSidings();
			Set<Long> reservedDepartures = new HashSet<>();
			for (Siding siding : depot.savedRails) {
				for (VehicleState state : sidings.getOrDefault(siding, Collections.emptyList())) {
					if (state.departure != null) {
						reservedDepartures.add(state.departure);
					}
				}
			}
			for (Siding siding : depot.savedRails) {
				LongArrayList departures = sidingAccess(siding).mtr$getDepartures();
				departures.removeIf((long departure) -> reservedDepartures.contains(departure));
				for (VehicleState state : sidings.getOrDefault(siding, Collections.emptyList())) {
					if (state.departure != null && !departures.contains(state.departure.longValue())) {
						departures.add(state.departure.longValue());
					}
				}
				Collections.sort(departures);
				for (VehicleState state : sidings.getOrDefault(siding, Collections.emptyList())) {
					if (state.departure != null) {
						vehicleSchema(state.vehicle).mtr$setDepartureIndex(departures.indexOf(state.departure.longValue()));
					}
				}
			}
		}
		sidings.values().forEach(states -> states.forEach(state -> vehicleAccess(state.vehicle).mtr$updateDeviation()));
		for (Siding siding : geometryOnlySidings) {
			SidingRailGeometryAccessor accessor = sidingAccess(siding);
			PathData.writePathCache(accessor.mtr$getOutboundPath(), data, siding.getTransportMode());
			PathData.writePathCache(accessor.mtr$getMainPath(), data, siding.getTransportMode());
			PathData.writePathCache(accessor.mtr$getInboundPath(), data, siding.getTransportMode());
			if (accessor.mtr$getDefaultPath() != null) {
				PathData.writePathCache(ObjectArrayList.of(accessor.mtr$getDefaultPath()), data, siding.getTransportMode());
			}
			for (Vehicle vehicle : accessor.mtr$getVehicles()) {
				PathData.writePathCache(vehicle.vehicleExtraData.immutablePath, data, siding.getTransportMode());
				((UpdateFlag) vehicle.vehicleExtraData).mtr$markGeometryChanged();
			}
		}
	}

	private static boolean containsChangedRail(ObjectList<PathData> path, Set<String> changedRails) {
		return !changedRails.isEmpty() && path.stream().anyMatch(segment -> changedRails.contains(segment.getRail().getHexId()));
	}

	private static boolean containsChangedRail(Data data, Siding siding, Set<String> changedRails) {
		if (changedRails.isEmpty()) {
			return false;
		}
		SidingRailGeometryAccessor accessor = sidingAccess(siding);
		Rail rail = getSidingRail(data, siding);
		return rail != null && changedRails.contains(rail.getHexId()) || containsChangedRail(accessor.mtr$getOutboundPath(), changedRails)
				|| containsChangedRail(accessor.mtr$getMainPath(), changedRails) || containsChangedRail(accessor.mtr$getInboundPath(), changedRails)
				|| accessor.mtr$getVehicles().stream().anyMatch(vehicle -> containsChangedRail(vehicle.vehicleExtraData.immutablePath, changedRails));
	}

	private static Rail getSidingRail(Data data, Siding siding) {
		Position position = siding.getRandomPosition();
		return Data.tryGet(data.positionsToRail, position, siding.getOtherPosition(position));
	}

	private static SidingRailGeometryAccessor sidingAccess(Siding siding) {
		return (SidingRailGeometryAccessor) (Object) siding;
	}

	private static VehicleRailGeometryAccessor vehicleAccess(Vehicle vehicle) {
		return (VehicleRailGeometryAccessor) vehicle;
	}

	private static VehicleSchemaRailGeometryAccessor vehicleSchema(Vehicle vehicle) {
		return (VehicleSchemaRailGeometryAccessor) vehicle;
	}

	private static double mapDistance(double distance, ObjectList<PathData> previous, ObjectList<PathData> updated) {
		if (!Double.isFinite(distance) || distance == Double.MAX_VALUE) {
			return distance;
		}
		int selected = -1;
		for (int index = 0; index < previous.size(); index++) {
			PathData segment = previous.get(index);
			if (segment.getEndDistance() != Double.MAX_VALUE && distance >= segment.getStartDistance()) {
				selected = index;
			}
		}
		if (selected < 0) {
			return distance;
		}
		PathData oldSegment = previous.get(selected);
		int occurrence = 0;
		for (int index = 0; index < selected; index++) {
			if (previous.get(index).isSameRail(oldSegment)) {
				occurrence++;
			}
		}
		for (PathData newSegment : updated) {
			if (newSegment.isSameRail(oldSegment) && occurrence-- == 0) {
				double oldLength = oldSegment.getEndDistance() - oldSegment.getStartDistance();
				double offset = distance - oldSegment.getStartDistance();
				return newSegment.getStartDistance() + (oldLength > 0 ? offset / oldLength * (newSegment.getEndDistance() - newSegment.getStartDistance()) : offset);
			}
		}
		return distance;
	}

	public interface UpdateFlag {
		void mtr$markGeometryChanged();
	}

	private static final class VehicleState {

		private final Vehicle vehicle;
		private final VehicleExtraData previous;
		private final double progress;
		private final boolean onRoute;
		private final Long departure;

		private VehicleState(Siding siding, Vehicle vehicle) {
			this.vehicle = vehicle;
			previous = vehicle.vehicleExtraData;
			progress = vehicleSchema(vehicle).mtr$getRailProgress();
			onRoute = vehicle.getIsOnRoute();
			LongArrayList departures = sidingAccess(siding).mtr$getDepartures();
			long index = vehicle.getDepartureIndex();
			departure = onRoute && index >= 0 && index < departures.size() ? departures.getLong((int) index) : null;
		}

		private void refresh(Siding siding, Data data) {
			SidingRailGeometryAccessor accessor = sidingAccess(siding);
			if (accessor.mtr$getDefaultPath() == null) {
				return;
			}
			VehicleExtraData generated = VehicleExtraData.create(previous.getDepotId(), siding.getId(), siding.getRailLength(),
					new ObjectArrayList<>(previous.immutableVehicleCars), accessor.mtr$getOutboundPath(), accessor.mtr$getMainPath(), accessor.mtr$getInboundPath(),
					accessor.mtr$getDefaultPath(), siding.area != null && siding.area.getRepeatInfinitely(), siding.getAcceleration(), siding.getDeceleration(),
					siding.getIsManual(), siding.getMaxManualSpeed(), siding.getManualToAutomaticTime());
			JsonObject oldGeometry = Utilities.getJsonObjectFromData(previous);
			JsonObject merged = oldGeometry.deepCopy();
			JsonObject geometry = Utilities.getJsonObjectFromData(generated);
			for (String key : new String[]{"railLength", "repeatIndex1", "repeatIndex2", "totalDistance", "defaultPosition", "path"}) {
				merged.add(key, geometry.get(key));
			}
			VehicleExtraData updated = new VehicleExtraData(new JsonReader(merged));
			PathData.writePathCache(updated.immutablePath, data, siding.getTransportMode());
			double stoppingPoint = previous.getStoppingPoint();
			double oldReturnPosition = oldGeometry.get("totalDistance").getAsDouble() - (oldGeometry.get("railLength").getAsDouble() - previous.getTotalVehicleLength()) / 2;
			if (oldGeometry.get("repeatIndex2").getAsLong() == 0 && Math.abs(stoppingPoint - oldReturnPosition) < 0.000001) {
				stoppingPoint = geometry.get("totalDistance").getAsDouble() - (geometry.get("railLength").getAsDouble() - updated.getTotalVehicleLength()) / 2;
			} else {
				stoppingPoint = mapDistance(stoppingPoint, previous.immutablePath, updated.immutablePath);
			}
			JsonObject stoppingUpdate = new JsonObject();
			stoppingUpdate.addProperty("stoppingPoint", stoppingPoint);
			updated.updateData(new JsonReader(stoppingUpdate));
			vehicleAccess(vehicle).mtr$setExtraData(updated);
			double defaultPosition = geometry.get("defaultPosition").getAsDouble();
			double mappedProgress = onRoute ? Math.max(Math.nextUp(defaultPosition), mapDistance(progress, previous.immutablePath, updated.immutablePath)) : defaultPosition;
			vehicleSchema(vehicle).mtr$setRailProgress(mappedProgress);
			vehicleAccess(vehicle).mtr$setStoppingCooldown(0);
			vehicleAccess(vehicle).mtr$setDeviationSpeedAdjustment(1);
			((UpdateFlag) updated).mtr$markGeometryChanged();
		}
	}
}
