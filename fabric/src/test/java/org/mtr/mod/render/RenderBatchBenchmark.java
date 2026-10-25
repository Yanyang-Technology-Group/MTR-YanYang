package org.mtr.mod.render;

import com.sun.management.ThreadMXBean;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

public final class RenderBatchBenchmark {

	private static final int WARMUP_FRAMES = 2000;
	private static final int MEASURED_FRAMES = 1500;
	private static final int SAMPLES = 7;
	private static volatile long blackhole;

	public static void main(String[] args) {
		final ThreadMXBean allocation = (ThreadMXBean) ManagementFactory.getThreadMXBean();
		if (!allocation.isThreadAllocatedMemorySupported()) throw new IllegalStateException("Thread allocation measurement is not supported");
		allocation.setThreadAllocatedMemoryEnabled(true);
		System.out.println("Java " + System.getProperty("java.version") + ", " + System.getProperty("java.vm.name"));
		System.out.printf(Locale.ROOT, "Batch storage only; preallocated keys/calls, no matrices or GL; warmup=%d frames, samples=%d x %d frames%n", WARMUP_FRAMES, SAMPLES, MEASURED_FRAMES);
		System.out.println("calls,materials,baseline_bytes/frame,pooled_bytes/frame,baseline_ns/frame,pooled_ns/frame");
		for (final int[] scenario : new int[][]{{1000, 1}, {10000, 1}, {10000, 32}, {10000, 256}}) {
			final State baseline = new State(scenario[0], scenario[1], false);
			final State pooled = new State(scenario[0], scenario[1], true);
			for (int frame = 0; frame < WARMUP_FRAMES; frame++) {
				baseline.frame();
				pooled.frame();
			}
			final double[] baselineBytes = new double[SAMPLES];
			final double[] pooledBytes = new double[SAMPLES];
			final double[] baselineNanos = new double[SAMPLES];
			final double[] pooledNanos = new double[SAMPLES];
			for (int sample = 0; sample < SAMPLES; sample++) {
				final Measurement first = measure(sample % 2 == 0 ? baseline : pooled, allocation);
				final Measurement second = measure(sample % 2 == 0 ? pooled : baseline, allocation);
				final Measurement oldResult = sample % 2 == 0 ? first : second;
				final Measurement newResult = sample % 2 == 0 ? second : first;
				baselineBytes[sample] = oldResult.bytes;
				pooledBytes[sample] = newResult.bytes;
				baselineNanos[sample] = oldResult.nanos;
				pooledNanos[sample] = newResult.nanos;
			}
			System.out.printf(Locale.ROOT, "%d,%d,%.0f,%.0f,%.0f,%.0f%n", scenario[0], scenario[1], median(baselineBytes), median(pooledBytes), median(baselineNanos), median(pooledNanos));
		}
		System.out.println("checksum=" + blackhole);
	}

	private static Measurement measure(State state, ThreadMXBean allocation) {
		final long thread = Thread.currentThread().getId();
		final long beforeBytes = allocation.getThreadAllocatedBytes(thread);
		final long beforeNanos = System.nanoTime();
		for (int frame = 0; frame < MEASURED_FRAMES; frame++) state.frame();
		final long nanos = System.nanoTime() - beforeNanos;
		final long bytes = allocation.getThreadAllocatedBytes(thread) - beforeBytes;
		final long expected = (long) state.calls.length * (state.calls.length - 1) / 2;
		if (state.checksum != expected || !state.batches.isEmpty()) throw new AssertionError("Batch contents changed");
		blackhole += state.checksum;
		return new Measurement((double) bytes / MEASURED_FRAMES, (double) nanos / MEASURED_FRAMES);
	}

	private static double median(double[] samples) {
		Arrays.sort(samples);
		return samples[samples.length / 2];
	}

	private static final class State {

		private final Map<Integer, List<Object>> batches = new HashMap<>();
		private final Integer[] keys;
		private final Call[] calls;
		private final boolean pooled;
		private final Function<Object, List<Object>> factory;
		private long checksum;
		private final Consumer<Object> draw = call -> checksum += ((Call) call).index;
		private final BiConsumer<Integer, List<Object>> drawBatch = (material, batch) -> batch.forEach(draw);

		private State(int callCount, int materials, boolean pooled) {
			this.pooled = pooled;
			factory = pooled ? RenderBatchListPool.factory() : material -> new ArrayList<>();
			keys = new Integer[materials];
			for (int material = 0; material < materials; material++) keys[material] = material;
			calls = new Call[callCount];
			for (int index = 0; index < callCount; index++) calls[index] = new Call(index);
		}

		private void frame() {
			for (int index = 0; index < calls.length; index++) batches.computeIfAbsent(keys[index % keys.length], factory).add(calls[index]);
			checksum = 0;
			batches.forEach(drawBatch);
			if (pooled) {
				RenderBatchListPool.clear(batches);
			} else {
				batches.clear();
			}
		}
	}

	private record Call(int index) {
	}

	private record Measurement(double bytes, double nanos) {
	}
}
