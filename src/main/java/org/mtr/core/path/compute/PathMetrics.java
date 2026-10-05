package org.mtr.core.path.compute;

import java.util.concurrent.atomic.LongAdder;

public final class PathMetrics {
	public final boolean enabled;
	public final LongAdder requests = new LongAdder(), computations = new LongAdder(), merged = new LongAdder(), cacheHits = new LongAdder();
	public final LongAdder stale = new LongAdder(), rebuilds = new LongAdder(), busyNanos = new LongAdder(), commitNanos = new LongAdder();
	public final LongAdder batches = new LongAdder(), batchItems = new LongAdder(), failures = new LongAdder(), rejected = new LongAdder();
	public volatile int nodes, edges, queuePeak;

	PathMetrics(boolean enabled) { this.enabled = enabled; }

	public String summary(int queueLength) {
		long requestCount = requests.sum();
		return "requests=" + requests.sum() + " searches=" + computations.sum() + " merged=" + merged.sum() +
			" hits=" + cacheHits.sum() + " hitPercent=" + (requestCount == 0 ? 0 : 100 * cacheHits.sum() / requestCount) +
			" queue=" + queueLength + " peak=" + queuePeak + " stale=" + stale.sum() +
			" rebuilds=" + rebuilds.sum() + " nodes=" + nodes + " edges=" + edges + " busyMs=" + busyNanos.sum() / 1_000_000 +
			" commitMs=" + commitNanos.sum() / 1_000_000 + " batches=" + batches.sum() + " batchItems=" + batchItems.sum() +
			" failures=" + failures.sum() + " rejected=" + rejected.sum();
	}
}
