package org.mtr.mod.data;

import com.sun.management.ThreadMXBean;
import org.mtr.core.data.*;
import org.mtr.core.path.compute.*;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;

import java.lang.management.ManagementFactory;
import java.util.concurrent.TimeUnit;

/** Optional synthetic benchmark; timings are not a server TPS estimate. */
public final class PathBenchmark {
	public static void main(String[] args) throws Exception {
		Data data = new Data() { };
		for (int i = 0; i < 512; i++) data.positionsToRail.put(RailPathSnapshotTest.position(i), new Object2ObjectOpenHashMap<>());
		for (int i = 0; i < 511; i++) RailPathSnapshotTest.connect(data, i, i + 1, false, false);
		RailPathSnapshot snapshot = new RailPathSnapshot(data, 1);
		Platform start = RailPathSnapshotTest.platform(data, 0, 1), end = RailPathSnapshotTest.platform(data, 510, 511);
		int from = snapshot.index(start.getRandomPosition()), to = snapshot.index(end.getRandomPosition());
		RailSearch search = new RailSearch();
		for (int i = 0; i < 20; i++) {
			RailPathSnapshotTest.originalNodes(data, start, end, snapshot);
			search.search(snapshot.graph, from, to);
		}
		ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
		long id = Thread.currentThread().getId();
		long before = bean.getThreadAllocatedBytes(id), time = System.nanoTime();
		for (int i = 0; i < 64; i++) RailPathSnapshotTest.originalNodes(data, start, end, snapshot);
		System.out.printf("ORIGINAL searches=64 ms=%.3f allocatedBytesPerSearch=%d%n", (System.nanoTime() - time) / 1e6, (bean.getThreadAllocatedBytes(id) - before) / 64);
		before = bean.getThreadAllocatedBytes(id); time = System.nanoTime();
		for (int i = 0; i < 64; i++) search.search(snapshot.graph, from, to);
		System.out.printf("PRIMITIVE searches=64 ms=%.3f allocatedBytesPerSearch=%d%n", (System.nanoTime() - time) / 1e6, (bean.getThreadAllocatedBytes(id) - before) / 64);
		for (int workers : new int[]{1, 2, 4, 6, 8, 10, 12}) {
			try (PathComputePool pool = new PathComputePool(workers, 32, true)) {
				for (int round = 0; round < 4; round++) {
					PathBatcher batcher = new PathBatcher(pool, snapshot.graph);
					time = System.nanoTime();
					for (int i = 0; i < 256; i++) batcher.request(from, snapshot.index(RailPathSnapshotTest.position(255 + i)));
					PathBatcher.Ticket last = batcher.request(from, to);
					batcher.flush();
					await(batcher, last);
					if (round == 3) System.out.printf("PARALLEL workers=%d unique=256 ms=%.3f%n", workers, (System.nanoTime() - time) / 1e6);
				}
				long computations = pool.metrics.computations.sum();
				PathBatcher repeats = new PathBatcher(pool, snapshot.graph);
				PathBatcher.Ticket last = null;
				for (int i = 0; i < 4096; i++) last = repeats.request(from, snapshot.index(RailPathSnapshotTest.position(495 + i % 16)));
				repeats.flush();
				await(repeats, last);
				System.out.printf("REPEATS workers=%d requests=4096 searches=%d%n", workers, pool.metrics.computations.sum() - computations);
			}
		}
	}

	private static void await(PathBatcher batcher, PathBatcher.Ticket ticket) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
		while (!ticket.ready() && !ticket.fallback() && System.nanoTime() < deadline) {
			batcher.collect();
			Thread.sleep(0, 100000);
		}
		if (!ticket.ready()) throw new AssertionError("Benchmark batch did not finish");
	}
}
