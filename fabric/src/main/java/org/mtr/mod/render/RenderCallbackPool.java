package org.mtr.mod.render;

import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.mapping.holder.Vector3d;
import org.mtr.mapping.mapper.GraphicsHolder;

import java.util.ArrayDeque;
import java.util.function.BiConsumer;

final class RenderCallbackPool {

	private static final int MAX_LISTS = 256;
	private static final int MAX_LIST_CAPACITY = 65536;
	private static final int MAX_TOTAL_CAPACITY = 262144;
	private final ArrayDeque<ObjectArrayList<BiConsumer<GraphicsHolder, Vector3d>>> lists = new ArrayDeque<>();
	private int retainedCapacity;

	ObjectArrayList<BiConsumer<GraphicsHolder, Vector3d>> acquire() {
		final ObjectArrayList<BiConsumer<GraphicsHolder, Vector3d>> list = lists.pollFirst();
		if (list == null) {
			return new ObjectArrayList<>();
		}
		retainedCapacity -= capacity(list);
		return list;
	}

	void recycle(ObjectArrayList<BiConsumer<GraphicsHolder, Vector3d>> list) {
		list.clear();
		final int capacity = capacity(list);
		if (capacity > 0 && capacity <= MAX_LIST_CAPACITY && retainedCapacity + capacity <= MAX_TOTAL_CAPACITY && lists.size() < MAX_LISTS) {
			lists.addLast(list);
			retainedCapacity += capacity;
		}
	}

	private static int capacity(ObjectArrayList<?> list) {
		return list.elements().length;
	}
}
