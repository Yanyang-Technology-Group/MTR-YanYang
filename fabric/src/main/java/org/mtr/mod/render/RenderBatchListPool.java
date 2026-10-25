package org.mtr.mod.render;

import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public final class RenderBatchListPool {

	private static final ThreadLocal<RenderBatchListPool> LOCAL = ThreadLocal.withInitial(RenderBatchListPool::new);
	private static final Function<Object, List<Object>> FACTORY = material -> LOCAL.get().acquire();
	private final ArrayDeque<BatchList> lists = new ArrayDeque<>();
	private final int maxLists;
	private final int maxListCapacity;
	private final int maxTotalCapacity;
	private int retainedCapacity;

	private RenderBatchListPool() {
		this(256, 65536, 262144);
	}

	RenderBatchListPool(int maxLists, int maxListCapacity, int maxTotalCapacity) {
		this.maxLists = maxLists;
		this.maxListCapacity = maxListCapacity;
		this.maxTotalCapacity = maxTotalCapacity;
	}

	public static Function<Object, List<Object>> factory() {
		return FACTORY;
	}

	public static void clear(Map<?, ? extends List<?>> batches) {
		if (batches.isEmpty()) {
			batches.clear();
		} else {
			LOCAL.get().clearBatches(batches);
		}
	}

	List<Object> acquire() {
		final BatchList list = lists.pollFirst();
		if (list == null) {
			return new BatchList(this);
		}
		retainedCapacity -= list.elements().length;
		list.pooled = false;
		return list;
	}

	void clearBatches(Map<?, ? extends List<?>> batches) {
		for (final List<?> calls : batches.values()) {
			if (calls instanceof BatchList) {
				final BatchList list = (BatchList) calls;
				if (list.pooled) {
					continue;
				}
				list.clear();
				final int capacity = list.elements().length;
				if (list.owner == this && capacity > 0 && capacity <= maxListCapacity && retainedCapacity + capacity <= maxTotalCapacity && lists.size() < maxLists) {
					list.pooled = true;
					lists.addLast(list);
					retainedCapacity += capacity;
				}
			}
		}
		batches.clear();
	}

	private static final class BatchList extends ObjectArrayList<Object> {

		private final RenderBatchListPool owner;
		private boolean pooled;

		private BatchList(RenderBatchListPool owner) {
			this.owner = owner;
		}
	}
}
