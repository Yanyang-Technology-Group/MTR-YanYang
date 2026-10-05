package org.mtr.core.path.compute;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** One process-owned pool. It accepts pure computations only, never world callbacks. */
public final class PathComputePool implements AutoCloseable {
	private final ThreadPoolExecutor executor;
	public final PathMetrics metrics;

	public PathComputePool(int workers, int queueCapacity, boolean counters) {
		if (workers < 1 || workers > 12 || queueCapacity < 1 || queueCapacity > 256) throw new IllegalArgumentException("Invalid pool limits");
		metrics = new PathMetrics(counters);
		AtomicInteger ids = new AtomicInteger();
		executor = new ThreadPoolExecutor(workers, workers, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(queueCapacity), runnable -> {
			Thread thread = new Worker(runnable, "TSC Path Worker-" + ids.incrementAndGet());
			thread.setDaemon(true);
			thread.setUncaughtExceptionHandler((ignored, error) -> System.getLogger(PathComputePool.class.getName()).log(System.Logger.Level.ERROR, "Path worker failed", error));
			return thread;
		}, new ThreadPoolExecutor.AbortPolicy());
	}

	public static int workerCount(int processors, int configured) {
		if (processors <= 2) return 0;
		return Math.min(Math.min(12, Math.max(1, processors - 2)), configured <= 0 ? 8 : configured);
	}

	public boolean execute(Runnable batch) {
		try {
			executor.execute(new SafeTask(batch, metrics));
			if (metrics.enabled) metrics.queuePeak = Math.max(metrics.queuePeak, executor.getQueue().size());
			return true;
		} catch (RejectedExecutionException e) {
			if (metrics.enabled) metrics.rejected.increment();
			return false;
		}
	}

	static RailSearch searchContext() { return ((Worker) Thread.currentThread()).search; }
	public int queueLength() { return executor.getQueue().size(); }
	public boolean isShutdown() { return executor.isShutdown(); }
	public boolean isTerminated() { return executor.isTerminated(); }

	@Override
	public void close() {
		executor.shutdownNow();
		try {
			if (!executor.awaitTermination(5, TimeUnit.SECONDS)) throw new IllegalStateException("Path workers did not stop");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private static final class Worker extends Thread {
		final RailSearch search = new RailSearch();
		Worker(Runnable runnable, String name) { super(runnable, name); }
	}

	private static final class SafeTask implements Runnable {
		private final Runnable task;
		private final PathMetrics metrics;
		SafeTask(Runnable task, PathMetrics metrics) { this.task = task; this.metrics = metrics; }
		@Override
		public void run() {
			try { task.run(); } catch (RuntimeException e) {
				if (metrics.enabled) metrics.failures.increment();
				System.getLogger(PathComputePool.class.getName()).log(System.Logger.Level.WARNING, "Path computation failed", e);
			}
		}
	}
}
