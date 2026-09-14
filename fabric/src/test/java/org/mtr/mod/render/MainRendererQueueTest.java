package org.mtr.mod.render;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mtr.mapping.holder.Identifier;
import org.mtr.mapping.holder.Vector3d;
import org.mtr.mapping.mapper.GraphicsHolder;

import java.lang.reflect.Field;
import java.util.*;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.*;

class MainRendererQueueTest {
	@AfterEach
	void clearQueues() throws Exception {
		for (final String field : List.of("RENDERS", "CURRENT_RENDERS")) {
			queues(field).forEach(stages -> stages.forEach(Map::clear));
		}
	}

	@Test
	void finishedFrameStorageIsReusedWithoutAliasingScheduledFrames() throws Exception {
		final Identifier material = new Identifier("mtr:reuse");
		final BiConsumer<GraphicsHolder, Vector3d> callback = (graphics, offset) -> {};
		for (int i = 0; i < 20000; i++) MainRenderer.scheduleRender(material, false, QueuedRenderLayer.EXTERIOR, callback);
		MainRenderer.prepareRenderQueues();
		final List<BiConsumer<GraphicsHolder, Vector3d>> rendered = queues("CURRENT_RENDERS").get(0).get(QueuedRenderLayer.EXTERIOR.ordinal()).get(material);
		MainRenderer.scheduleRender(material, false, QueuedRenderLayer.EXTERIOR, callback);
		assertEquals(20000, rendered.size());
		MainRenderer.prepareRenderQueues();
		assertTrue(rendered.isEmpty(), "finished frame still retains callback captures");
		for (int i = 0; i <= 256; i++) MainRenderer.scheduleRender(new Identifier("mtr:reuse_" + i), false, QueuedRenderLayer.EXTERIOR, callback);
		assertTrue(queues("RENDERS").get(0).get(QueuedRenderLayer.EXTERIOR.ordinal()).values().stream().anyMatch(list -> list == rendered));
		assertNotSame(rendered, queues("CURRENT_RENDERS").get(0).get(QueuedRenderLayer.EXTERIOR.ordinal()).get(material));
	}

	@Test
	void materialAndCallbackOrderSurviveFrameSwapAndCancellation() throws Exception {
		final Identifier first = new Identifier("mtr:first"), second = new Identifier("mtr:second");
		final List<Integer> rendered = new ArrayList<>();
		MainRenderer.scheduleRender(first, false, QueuedRenderLayer.EXTERIOR, (graphics, offset) -> rendered.add(1));
		MainRenderer.scheduleRender(second, false, QueuedRenderLayer.EXTERIOR, (graphics, offset) -> rendered.add(2));
		MainRenderer.scheduleRender(first, false, QueuedRenderLayer.EXTERIOR, (graphics, offset) -> rendered.add(3));
		MainRenderer.scheduleRender(first, true, QueuedRenderLayer.EXTERIOR, (graphics, offset) -> rendered.add(4));
		MainRenderer.prepareRenderQueues();
		final Map<Identifier, List<BiConsumer<GraphicsHolder, Vector3d>>> current = queues("CURRENT_RENDERS").get(0).get(QueuedRenderLayer.EXTERIOR.ordinal());
		assertEquals(List.of(first, second), new ArrayList<>(current.keySet()));
		current.values().forEach(callbacks -> callbacks.forEach(callback -> callback.accept(null, null)));
		queues("CURRENT_RENDERS").get(1).get(QueuedRenderLayer.EXTERIOR.ordinal()).get(first).get(0).accept(null, null);
		assertEquals(List.of(1, 3, 2, 4), rendered);
		MainRenderer.scheduleRender(second, false, QueuedRenderLayer.EXTERIOR, (graphics, offset) -> rendered.add(5));
		assertEquals(1, current.get(second).size());
		MainRenderer.cancelRender(first);
		assertFalse(current.containsKey(first));
		assertFalse(queues("CURRENT_RENDERS").get(1).get(QueuedRenderLayer.EXTERIOR.ordinal()).containsKey(first));
		MainRenderer.prepareRenderQueues();
		final Map<Identifier, List<BiConsumer<GraphicsHolder, Vector3d>>> next = queues("CURRENT_RENDERS").get(0).get(QueuedRenderLayer.EXTERIOR.ordinal());
		assertEquals(List.of(second), new ArrayList<>(next.keySet()));
		next.get(second).get(0).accept(null, null);
		assertEquals(List.of(1, 3, 2, 4, 5), rendered);
		assertTrue(queues("RENDERS").get(0).get(QueuedRenderLayer.EXTERIOR.ordinal()).isEmpty());
	}

	@SuppressWarnings("unchecked")
	private static List<List<Map<Identifier, List<BiConsumer<GraphicsHolder, Vector3d>>>>> queues(String name) throws Exception {
		final Field field = MainRenderer.class.getDeclaredField(name);
		field.setAccessible(true);
		return (List<List<Map<Identifier, List<BiConsumer<GraphicsHolder, Vector3d>>>>>) field.get(null);
	}
}
