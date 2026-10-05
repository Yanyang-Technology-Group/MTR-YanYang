package org.mtr.data;

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

import java.util.Arrays;

/**
 * Main-thread adapter between the {@link Data} rail graph and the immutable
 * {@link RailGraph} arrays consumed by the compute pool. Only the graph — never live
 * positions, data or rails — is sent to the worker threads.
 *
 * <p>Ported from the YanYang 4.0.5 optimisation series.</p>
 */
final class RailPathSnapshot {

	final RailGraph graph;
	final Position[] positions;
	private final Object2IntOpenHashMap<Position> indices = new Object2IntOpenHashMap<>();

	RailPathSnapshot(Data data, long epoch) {
		final int count = data.positionsToRail.size();
		if (count > 32768) {
			throw new IllegalArgumentException("Rail graph exceeds snapshot limit");
		}
		indices.defaultReturnValue(-1);
		positions = new Position[count];
		final long[] x = new long[count], y = new long[count], z = new long[count];
		int index = 0, edgeCount = 0;
		for (final Position position : data.positionsToRail.keySet()) {
			positions[index] = new Position(position.getX(), position.getY(), position.getZ());
			indices.put(positions[index], index);
			x[index] = position.getX();
			y[index] = position.getY();
			z[index] = position.getZ();
			edgeCount += data.positionsToRail.get(position).size();
			if (edgeCount > 262144) {
				throw new IllegalArgumentException("Rail graph exceeds edge limit");
			}
			index++;
		}
		final int[] offsets = new int[count + 1], targets = new int[edgeCount], starts = new int[edgeCount], arrivals = new int[edgeCount];
		final long[] duration = new long[edgeCount];
		final boolean[] turnBack = new boolean[edgeCount];
		final int[] cursor = {0};
		for (int i = 0; i < count; i++) {
			final Position position = positions[i];
			offsets[i] = cursor[0];
			// forEach, not the entry iterator: fastutil iteration order participates in tie breaking.
			data.positionsToRail.get(position).forEach((target, rail) -> {
				final double speed = rail.getSpeedLimitMetersPerMillisecond(position);
				if (!(speed > 0)) {
					return;
				}
				final Angle start = rail.getStartAngle(position);
				final Angle arrival = rail.getStartAngle(target).getOpposite();
				final int edge = cursor[0]++;
				targets[edge] = indices.getInt(target);
				starts[edge] = start.ordinal();
				arrivals[edge] = arrival.ordinal();
				duration[edge] = Math.max(1, Math.round(rail.railMath.getLength() / speed));
				turnBack[edge] = rail.canTurnBack();
			});
		}
		offsets[count] = cursor[0];
		graph = new RailGraph(epoch, x, y, z, offsets, Arrays.copyOf(targets, cursor[0]),
			Arrays.copyOf(starts, cursor[0]), Arrays.copyOf(arrivals, cursor[0]),
			Arrays.copyOf(duration, cursor[0]), Arrays.copyOf(turnBack, cursor[0]));
	}

	int index(Position position) {
		return indices.getInt(position);
	}

	ObjectArrayList<PathData> assemble(Data data, int[] nodes, SavedRailBase<?, ?> start, SavedRailBase<?, ?> end, int stopIndex) {
		final ObjectArrayList<Position> padded = new ObjectArrayList<>(nodes.length + 4);
		for (final int node : nodes) {
			padded.add(positions[node]);
		}
		final ObjectArrayList<PathData> result = new ObjectArrayList<>();
		if (padded.isEmpty()) {
			return result;
		}
		pad(data, padded, start, false);
		pad(data, padded, end, true);
		for (int i = 1; i < padded.size(); i++) {
			final Position position1 = padded.get(i - 1);
			final Position position2 = padded.get(i);
			final Rail rail = Data.tryGet(data.positionsToRail, position1, position2);
			if (rail == null) {
				return null;
			}
			if (i == padded.size() - 1) {
				result.add(new PathData(rail, end.getId(), end instanceof Platform ? ((Platform) end).getDwellTime() : 1, stopIndex + 1, position1, position2));
			} else {
				result.add(new PathData(rail, 0, rail.canTurnBack() && padded.get(i + 1).equals(position1) ? 1 : 0, stopIndex, position1, position2));
			}
		}
		return result;
	}

	private static void pad(Data data, ObjectArrayList<Position> path, SavedRailBase<?, ?> savedRail, boolean end) {
		final Position last = path.get(end ? path.size() - 1 : 0);
		if (!savedRail.containsPos(last)) {
			final var connections = data.positionsToRail.get(last);
			if (connections == null) {
				return;
			}
			for (final Position position : connections.keySet()) {
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
