package org.mtr.mod.data;

import org.mtr.core.data.Data;
import org.mtr.core.data.PathData;
import org.mtr.core.data.Platform;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.data.SavedRailBase;
import org.mtr.core.path.compute.RailGraph;
import org.mtr.core.tool.Angle;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;

/** Main-thread adapter. Only graph, never positions/data/rails, is sent to the compute pool. */
final class RailPathSnapshot {
	final RailGraph graph;
	final Position[] positions;
	private final Object2IntOpenHashMap<Position> indices = new Object2IntOpenHashMap<>();

	RailPathSnapshot(Data data, long epoch) {
		int count = data.positionsToRail.size();
		if (count > 32768) throw new IllegalArgumentException("Rail graph exceeds snapshot limit");
		indices.defaultReturnValue(-1);
		positions = new Position[count];
		long[] x = new long[count], y = new long[count], z = new long[count];
		int index = 0, edgeCount = 0;
		for (Position position : data.positionsToRail.keySet()) {
			positions[index] = new Position(position.getX(), position.getY(), position.getZ());
			indices.put(positions[index], index);
			x[index] = position.getX(); y[index] = position.getY(); z[index] = position.getZ();
			edgeCount += data.positionsToRail.get(position).size();
			if (edgeCount > 262144) throw new IllegalArgumentException("Rail graph exceeds edge limit");
			index++;
		}
		int[] offsets = new int[count + 1], targets = new int[edgeCount], starts = new int[edgeCount], arrivals = new int[edgeCount];
		long[] duration = new long[edgeCount];
		boolean[] turnBack = new boolean[edgeCount];
		int[] cursor = {0};
		for (int i = 0; i < count; i++) {
			Position position = positions[i];
			offsets[i] = cursor[0];
			// forEach, not the entry iterator: fastutil iteration order participates in tie breaking.
			data.positionsToRail.get(position).forEach((target, rail) -> {
				double speed = rail.getSpeedLimitMetersPerMillisecond(position);
				if (!(speed > 0)) return;
				Angle start = rail.getStartAngle(position), arrival = rail.getStartAngle(target).getOpposite();
				int edge = cursor[0]++;
				targets[edge] = indices.getInt(target);
				starts[edge] = start.ordinal();
				arrivals[edge] = arrival.ordinal();
				duration[edge] = Math.max(1, Math.round(rail.railMath.getLength() / speed));
				turnBack[edge] = rail.canTurnBack();
			});
		}
		offsets[count] = cursor[0];
		graph = new RailGraph(epoch, x, y, z, offsets, java.util.Arrays.copyOf(targets, cursor[0]),
			java.util.Arrays.copyOf(starts, cursor[0]), java.util.Arrays.copyOf(arrivals, cursor[0]),
			java.util.Arrays.copyOf(duration, cursor[0]), java.util.Arrays.copyOf(turnBack, cursor[0]));
	}

	int index(Position position) { return indices.getInt(position); }

	ObjectArrayList<PathData> assemble(Data data, int[] nodes, SavedRailBase<?, ?> start, SavedRailBase<?, ?> end, int stopIndex) {
		ObjectArrayList<Position> padded = new ObjectArrayList<>(nodes.length + 4);
		for (int node : nodes) padded.add(positions[node]);
		ObjectArrayList<PathData> result = new ObjectArrayList<>();
		if (padded.isEmpty()) return result;
		pad(data, padded, start, false);
		pad(data, padded, end, true);
		for (int i = 1; i < padded.size(); i++) {
			Position position1 = padded.get(i - 1), position2 = padded.get(i);
			Rail rail = Data.tryGet(data.positionsToRail, position1, position2);
			if (rail == null) return null;
			if (i == padded.size() - 1) {
				result.add(new PathData(rail, end.getId(), end instanceof Platform ? ((Platform) end).getDwellTime() : 1, stopIndex + 1, position1, position2));
			} else {
				result.add(new PathData(rail, 0, rail.canTurnBack() && padded.get(i + 1).equals(position1) ? 1 : 0, stopIndex, position1, position2));
			}
		}
		return result;
	}

	private static void pad(Data data, ObjectArrayList<Position> path, SavedRailBase<?, ?> savedRail, boolean end) {
		Position last = path.get(end ? path.size() - 1 : 0);
		if (!savedRail.containsPos(last)) {
			var connections = data.positionsToRail.get(last);
			if (connections == null) return;
			for (Position position : connections.keySet()) {
				if (savedRail.containsPos(position)) {
					path.add(end ? path.size() : 0, position);
					path.add(end ? path.size() : 0, savedRail.getOtherPosition(position));
					break;
				}
			}
		} else if (path.size() > 1 && !savedRail.containsPos(path.get(end ? path.size() - 2 : 1))) {
			path.add(end ? path.size() : 0, savedRail.getOtherPosition(last));
		}
	}
}
