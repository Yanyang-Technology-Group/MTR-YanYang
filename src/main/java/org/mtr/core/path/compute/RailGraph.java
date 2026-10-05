package org.mtr.core.path.compute;

/** Immutable directed adjacency, in the original rail map's iteration order. */
public final class RailGraph {
	public final long epoch;
	final long[] x, y, z, duration;
	final int[] offsets, targets, startAngles, arrivalAngles;
	final boolean[] turnBack;

	public RailGraph(long epoch, long[] x, long[] y, long[] z, int[] offsets, int[] targets,
		int[] startAngles, int[] arrivalAngles, long[] duration, boolean[] turnBack) {
		int nodes = x.length;
		int edges = targets.length;
		if (nodes > 32768 || y.length != nodes || z.length != nodes || offsets.length != nodes + 1 ||
			startAngles.length != edges || arrivalAngles.length != edges || duration.length != edges ||
			turnBack.length != edges || edges > 262144 || offsets[0] != 0 || offsets[nodes] != edges) {
			throw new IllegalArgumentException("Invalid or oversized rail snapshot");
		}
		for (int i = 0; i < nodes; i++) {
			if (offsets[i] < 0 || offsets[i] > offsets[i + 1] || outsideWorld(x[i]) || outsideWorld(y[i]) || outsideWorld(z[i])) {
				throw new IllegalArgumentException("Invalid rail node");
			}
		}
		for (int i = 0; i < edges; i++) {
			if (targets[i] < 0 || targets[i] >= nodes || startAngles[i] < 0 || startAngles[i] >= 16 ||
				arrivalAngles[i] < 0 || arrivalAngles[i] >= 16 || duration[i] <= 0) {
				throw new IllegalArgumentException("Invalid rail edge");
			}
		}
		this.epoch = epoch;
		this.x = x.clone();
		this.y = y.clone();
		this.z = z.clone();
		this.offsets = offsets.clone();
		this.targets = targets.clone();
		this.startAngles = startAngles.clone();
		this.arrivalAngles = arrivalAngles.clone();
		this.duration = duration.clone();
		this.turnBack = turnBack.clone();
	}

	public int nodeCount() { return x.length; }
	public int edgeCount() { return targets.length; }
	private static boolean outsideWorld(long value) { return value < -30000000 || value > 30000000; }

	long distance(int a, int b) {
		return Math.abs(x[a] - x[b]) + Math.abs(y[a] - y[b]) + Math.abs(z[a] - z[b]);
	}
}
