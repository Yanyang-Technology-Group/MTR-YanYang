package org.mtr.data;

import org.mtr.MTR;
import org.mtr.core.data.*;
import org.mtr.core.path.SidingPathFinder;
import org.mtr.core.path.compute.PathBatcher;
import org.mtr.core.path.compute.PathComputePool;
import org.mtr.core.path.compute.PathMetrics;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.util.IdentityHashMap;
import java.util.function.BiConsumer;

/**
 * Bounded parallel server-side path computation.
 *
 * <p>The core's {@link SidingPathFinder#findPathTick} advances its A* searches on the
 * calling thread — the Minecraft server thread when threaded simulation is off — burning
 * up to 5 ms per tick per batch. With many vehicles requesting paths simultaneously, this
 * dominates the tick budget. This class moves the graph search onto a small worker pool:</p>
 *
 * <ul>
 *   <li>{@link RailPathSnapshot} compresses the {@link Data} rail graph into immutable
 *       arrays; only that snapshot ever leaves the server thread.</li>
 *   <li>{@link PathBatcher} coalesces identical requests and returns results by epoch, so
 *       topology changes (which bump the epoch via the {@code DataPathEpochMixin}) can
 *       never leak a stale path into a new graph.</li>
 *   <li>The per-tick commit loop is time-boxed (5 ms) and never blocks on an incomplete
 *       result; incomplete batches simply continue on the next tick.</li>
 * </ul>
 *
 * <p>Lifecycle and state commits are confined to the Minecraft server thread. Networks
 * whose graph exceeds the snapshot limits permanently fall back to the serial search.</p>
 *
 * <p>Ported from the YanYang 4.0.5 optimisation series (mod-side integration; the compute
 * module is vendored under {@code org.mtr.core.path.compute}).</p>
 */
public final class ServerRailPaths {

	private static final IdentityHashMap<Data, Network> NETWORKS = new IdentityHashMap<>();
	private static Thread owner;
	private static PathComputePool pool;
	private static long nextLogMillis;

	public static void start(boolean threadedSimulation) {
		stop();
		final int workers = PathComputePool.workerCount(Runtime.getRuntime().availableProcessors(), Integer.getInteger("mtr.path.workers", 0));
		if (threadedSimulation || !Boolean.parseBoolean(System.getProperty("mtr.path.parallel", "true")) || workers == 0) {
			return;
		}
		owner = Thread.currentThread();
		pool = new PathComputePool(workers, 32, Boolean.getBoolean("mtr.path.metrics"));
		nextLogMillis = System.currentTimeMillis() + 30000;
		MTR.LOGGER.info("Parallel path computation: {} workers, queue 32, batches up to 8", workers);
	}

	public static void stop() {
		if (pool != null) {
			for (final Network network : NETWORKS.values()) {
				network.invalidate();
			}
			pool.close();
		}
		NETWORKS.clear();
		pool = null;
		owner = null;
	}

	public static void beginTick() {
		if (!enabled()) {
			return;
		}
		for (final Network network : NETWORKS.values()) {
			if (network.batcher != null) {
				network.batcher.collect();
			}
		}
	}

	public static void endTick() {
		if (!enabled()) {
			return;
		}
		for (final Network network : NETWORKS.values()) {
			if (network.batcher != null) {
				network.batcher.flush();
			}
		}
		if (pool.metrics.enabled && System.currentTimeMillis() >= nextLogMillis) {
			nextLogMillis = System.currentTimeMillis() + 30000;
			MTR.LOGGER.info("Parallel paths: {}", pool.metrics.summary(pool.queueLength()));
		}
	}

	public static void invalidate(Data data) {
		if (!enabled()) {
			return;
		}
		final Network network = NETWORKS.get(data);
		if (network != null) {
			network.invalidate();
		}
	}

	public static <T extends AreaBase<T, U>, U extends SavedRailBase<U, T>, V extends AreaBase<V, W>, W extends SavedRailBase<W, V>> boolean process(
		ObjectArrayList<PathData> path, ObjectArrayList<SidingPathFinder<T, U, V, W>> finders, Runnable success, BiConsumer<U, W> fail
	) {
		if (!enabled() || finders.isEmpty()) {
			return false;
		}
		final SidingPathFinder<T, U, V, W> first = finders.get(0);
		if (!((Object) first instanceof FinderAccess)) {
			return false;
		}
		final FinderAccess firstAccess = (FinderAccess) (Object) first;
		if (firstAccess.mtr$getRequest() != null && firstAccess.mtr$getRequest().serialFallback) {
			return false;
		}
		if (first.startSavedRail.getTransportMode() == TransportMode.AIRPLANE) {
			return false;
		}
		final Data data = firstAccess.mtr$getData();
		Network network = NETWORKS.get(data);
		if (network == null) {
			if (NETWORKS.size() >= 16) {
				return false;
			}
			network = new Network(data);
			NETWORKS.put(data, network);
		}
		if (!network.prepare()) {
			return false;
		}
		// Prefetch a bounded group while preserving the caller's existing list order at commit.
		for (int i = 0; i < Math.min(64, finders.size()); i++) {
			final SidingPathFinder<T, U, V, W> finder = finders.get(i);
			if (finder.startSavedRail.getTransportMode() == TransportMode.AIRPLANE) {
				return false;
			}
			final FinderAccess access = (FinderAccess) (Object) finder;
			if (access.mtr$getData() != data) {
				return false;
			}
			final Request request = access.mtr$getRequest();
			if (request != null && request.serialFallback) {
				return false;
			}
			final int start = network.snapshot.index(finder.startSavedRail.getRandomPosition());
			final int end = network.snapshot.index(finder.endSavedRail.getRandomPosition());
			if (start < 0 || end < 0) {
				return false;
			}
			if (request == null || request.epoch != network.epoch || request.start != start || request.end != end) {
				final PathBatcher.Ticket ticket = network.batcher.request(start, end);
				if (ticket == null) {
					return false;
				}
				access.mtr$setRequest(new Request(network.epoch, start, end, ticket));
			}
		}
		final PathMetrics metrics = pool.metrics;
		final long started = System.nanoTime();
		try {
			final int limit = Math.min(64, finders.size());
			for (int i = 0; i < limit && !finders.isEmpty(); i++) {
				final SidingPathFinder<T, U, V, W> finder = finders.get(0);
				final Request request = ((FinderAccess) (Object) finder).mtr$getRequest();
				if (request == null || request.epoch != network.epoch || request.ticket.fallback()) {
					return false;
				}
				final int[] nodes = request.ticket.result(network.epoch);
				if (nodes == null) {
					// No busy loop and no waiting on the main thread; retry next tick.
					return true;
				}
				final ObjectArrayList<PathData> part = network.snapshot.assemble(data, nodes, finder.startSavedRail, finder.endSavedRail, finder.stopIndex);
				if (part == null) {
					request.serialFallback = true;
					network.invalidate();
					return false;
				}
				if (part.size() < 2) {
					finders.clear();
					path.clear();
					fail.accept(finder.startSavedRail, finder.endSavedRail);
					return true;
				}
				if (SidingPathFinder.overlappingPaths(path, part)) {
					part.remove(0);
				}
				path.addAll(part);
				finders.remove(0);
				if (finders.isEmpty()) {
					success.run();
					return true;
				}
				if (System.nanoTime() - started >= 5_000_000) {
					return true;
				}
			}
			return true;
		} finally {
			if (metrics.enabled) {
				metrics.commitNanos.add(System.nanoTime() - started);
			}
		}
	}

	private static boolean enabled() {
		return Thread.currentThread() == owner && pool != null;
	}

	public interface FinderAccess {

		Data mtr$getData();

		Request mtr$getRequest();

		void mtr$setRequest(Request request);
	}

	public static final class Request {

		final long epoch;
		final int start, end;
		final PathBatcher.Ticket ticket;
		boolean serialFallback;

		Request(long epoch, int start, int end, PathBatcher.Ticket ticket) {
			this.epoch = epoch;
			this.start = start;
			this.end = end;
			this.ticket = ticket;
		}
	}

	private static final class Network {

		final Data data;
		long epoch;
		RailPathSnapshot snapshot;
		PathBatcher batcher;
		boolean unsupported;

		Network(Data data) {
			this.data = data;
		}

		void invalidate() {
			epoch++;
			if (batcher != null) {
				batcher.invalidate();
			}
			batcher = null;
			snapshot = null;
			unsupported = false;
		}

		boolean prepare() {
			if (unsupported) {
				return false;
			}
			if (snapshot == null) {
				try {
					snapshot = new RailPathSnapshot(data, epoch);
					batcher = new PathBatcher(pool, snapshot.graph);
				} catch (RuntimeException e) {
					unsupported = true;
					MTR.LOGGER.debug("Using serial path search for unsupported snapshot", e);
					return false;
				}
			}
			return true;
		}
	}
}
