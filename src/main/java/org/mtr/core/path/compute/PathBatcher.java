package org.mtr.core.path.compute;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.function.BooleanSupplier;

/** Owner-thread request merging and ordered publication. Workers only receive Batch and primitive graph data. */
public final class PathBatcher {
	private final Thread owner = Thread.currentThread();
	private final PathComputePool pool;
	private final RailGraph graph;
	private final Epoch epoch;
	private final LinkedHashMap<Long, Ticket> cache = new LinkedHashMap<>();
	private final ArrayList<Ticket> pending = new ArrayList<>();
	private final ArrayDeque<Batch> running = new ArrayDeque<>();
	private int cachedNodes;
	private static final int CACHE_SIZE = 512, MAX_CACHED_NODES = 65536, BATCH_SIZE = 8, MAX_BATCHES = 32;

	public PathBatcher(PathComputePool pool, RailGraph graph) {
		this.pool = pool;
		this.graph = graph;
		epoch = new Epoch(graph.epoch);
		if (pool.metrics.enabled) {
			pool.metrics.rebuilds.increment();
			pool.metrics.nodes = graph.nodeCount();
			pool.metrics.edges = graph.edgeCount();
		}
	}

	public Ticket request(int start, int end) {
		checkOwner();
		if (!epoch.valid || pool.isShutdown()) return null;
		if (start < 0 || end < 0 || start >= graph.nodeCount() || end >= graph.nodeCount()) return null;
		if (pool.metrics.enabled) pool.metrics.requests.increment();
		long key = ((long) start << 32) | (end & 0xffffffffL);
		Ticket existing = cache.get(key);
		if (existing != null) {
			if (pool.metrics.enabled) {
				if (existing.ready()) pool.metrics.cacheHits.increment(); else pool.metrics.merged.increment();
			}
			return existing;
		}
		if (cache.size() >= CACHE_SIZE || cachedNodes >= MAX_CACHED_NODES) {
			var iterator = cache.values().iterator();
			while (iterator.hasNext()) {
				Ticket old = iterator.next();
				if (old.published) {
					cachedNodes -= old.nodes == null ? 0 : old.nodes.length;
					iterator.remove();
					if (cache.size() < CACHE_SIZE && cachedNodes < MAX_CACHED_NODES) break;
				}
			}
			if (cache.size() >= CACHE_SIZE || cachedNodes >= MAX_CACHED_NODES) return null;
		}
		Ticket ticket = new Ticket(epoch, start, end);
		cache.put(key, ticket);
		pending.add(ticket);
		return ticket;
	}

	public void flush() {
		checkOwner();
		if (pending.isEmpty()) return;
		for (int from = 0; from < pending.size(); from += BATCH_SIZE) {
			int count = Math.min(BATCH_SIZE, pending.size() - from);
			Ticket[] tickets = new Ticket[count];
			for (int i = 0; i < count; i++) tickets[i] = pending.get(from + i);
			Batch batch = new Batch(graph, epoch, tickets, pool.metrics);
			if (running.size() >= MAX_BATCHES || !pool.execute(batch)) {
				// Rejected searches remain on the caller's original incremental finder.
				for (Ticket ticket : tickets) ticket.failed = true;
				batch.done = true;
			}
			running.addLast(batch);
		}
		pending.clear();
	}

	public void collect() {
		checkOwner();
		if (pool.isShutdown()) {
			invalidate();
			return;
		}
		while (!running.isEmpty() && running.peekFirst().done) {
			Batch batch = running.removeFirst();
			for (Ticket ticket : batch.tickets) {
				if (!epoch.valid) {
					if (pool.metrics.enabled) pool.metrics.stale.increment();
					ticket.nodes = null;
					ticket.failed = true;
				} else if (ticket.nodes != null) {
					cachedNodes += ticket.nodes.length;
				}
				ticket.published = true;
			}
		}
		if (cachedNodes > MAX_CACHED_NODES) {
			var iterator = cache.values().iterator();
			while (iterator.hasNext() && cachedNodes > MAX_CACHED_NODES) {
				Ticket ticket = iterator.next();
				if (ticket.published) {
					cachedNodes -= ticket.nodes == null ? 0 : ticket.nodes.length;
					iterator.remove();
				}
			}
		}
	}

	public void invalidate() {
		checkOwner();
		epoch.valid = false;
		if (pool.metrics.enabled) pool.metrics.stale.add(cache.size());
		cache.clear();
		pending.clear();
		running.clear();
		cachedNodes = 0;
	}

	private void checkOwner() {
		if (Thread.currentThread() != owner) throw new IllegalStateException("Path coordinator used off its owner thread");
	}

	private static final class Epoch implements BooleanSupplier {
		final long version;
		volatile boolean valid = true;
		Epoch(long version) { this.version = version; }
		@Override public boolean getAsBoolean() { return valid; }
	}

	public static final class Ticket {
		private final Epoch epoch;
		private final int start, end;
		private int[] nodes;
		private boolean published, failed;
		private Ticket(Epoch epoch, int start, int end) { this.epoch = epoch; this.start = start; this.end = end; }
		public boolean ready() { return epoch.valid && published && !failed; }
		public boolean fallback() { return !epoch.valid || published && failed; }
		/** Returned path is read-only and shared by identical requests. */
		public int[] result(long version) { return version == epoch.version && ready() ? nodes : null; }
	}

	private static final class Batch implements Runnable {
		final RailGraph graph;
		final Epoch epoch;
		final Ticket[] tickets;
		final PathMetrics metrics;
		volatile boolean done;
		Batch(RailGraph graph, Epoch epoch, Ticket[] tickets, PathMetrics metrics) {
			this.graph = graph; this.epoch = epoch; this.tickets = tickets; this.metrics = metrics;
		}
		@Override
		public void run() {
			long start = metrics.enabled ? System.nanoTime() : 0;
			try {
				RailSearch context = PathComputePool.searchContext();
				for (Ticket ticket : tickets) {
					if (epoch.valid && !Thread.currentThread().isInterrupted()) {
						if (metrics.enabled) metrics.computations.increment();
						ticket.nodes = context.search(graph, ticket.start, ticket.end, epoch);
					}
					ticket.failed = ticket.nodes == null;
				}
			} catch (Exception e) {
				if (metrics.enabled) metrics.failures.increment();
				for (Ticket ticket : tickets) ticket.failed = true;
			} finally {
				for (Ticket ticket : tickets) if (ticket.nodes == null) ticket.failed = true;
				if (metrics.enabled) {
					metrics.busyNanos.add(System.nanoTime() - start);
					metrics.batches.increment();
					metrics.batchItems.add(tickets.length);
				}
				done = true;
			}
		}
	}
}
