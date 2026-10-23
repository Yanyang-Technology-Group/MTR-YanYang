package org.mtr.mod.render;

import org.junit.jupiter.api.Test;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class RenderBatchListPoolTest {

	@Test
	void completedBatchesReleaseCallsAndReuseStorageWithoutSharingActiveLists() {
		final RenderBatchListPool pool = new RenderBatchListPool(4, 256, 512);
		final List<Object> first = pool.acquire();
		final List<Object> second = pool.acquire();
		assertNotSame(first, second);
		for (int index = 0; index < 100; index++) {
			first.add(new Object());
			second.add(new Object());
		}
		final Object[] firstStorage = ((ObjectArrayList<?>) first).elements();
		final Object[] secondStorage = ((ObjectArrayList<?>) second).elements();
		final Map<Integer, List<Object>> batches = new LinkedHashMap<>();
		batches.put(1, first);
		batches.put(2, second);
		pool.clearBatches(batches);
		assertTrue(batches.isEmpty());
		for (final Object call : firstStorage) assertNull(call);
		for (final Object call : secondStorage) assertNull(call);
		final List<Object> nextFirst = pool.acquire();
		final List<Object> nextSecond = pool.acquire();
		assertNotSame(nextFirst, nextSecond);
		assertTrue(nextFirst == first || nextFirst == second);
		assertTrue(nextSecond == first || nextSecond == second);
		assertTrue(nextFirst.isEmpty());
		assertSame(nextFirst == first ? firstStorage : secondStorage, ((ObjectArrayList<?>) nextFirst).elements());
		assertNotSame(nextFirst, pool.acquire());
	}

	@Test
	void transientBatchesCannotRetainUnboundedStorage() {
		final RenderBatchListPool pool = new RenderBatchListPool(2, 64, 64);
		final Map<Integer, List<Object>> batches = new HashMap<>();
		for (int material = 0; material < 20; material++) {
			final List<Object> calls = pool.acquire();
			for (int index = 0; index < (material == 0 ? 1000 : 32); index++) calls.add(new Object());
			batches.put(material, calls);
		}
		final List<Object> huge = batches.get(0);
		pool.clearBatches(batches);
		assertTrue(huge.isEmpty());
		int capacity = 0;
		int retained = 0;
		for (int material = 0; material < 20; material++) {
			final List<Object> calls = pool.acquire();
			assertNotSame(huge, calls);
			final int slots = ((ObjectArrayList<?>) calls).elements().length;
			capacity += slots;
			if (slots > 0) retained++;
		}
		assertTrue(capacity <= 64, "retained " + capacity + " slots");
		assertTrue(retained <= 2);
	}

	@Test
	void consecutiveFramesKeepMaterialEqualityAndCallOrder() {
		final RenderBatchListPool pool = new RenderBatchListPool(8, 128, 512);
		final Map<String, List<Object>> batches = new HashMap<>();
		for (int frame = 0; frame < 4; frame++) {
			for (int call = 0; call < 12; call++) {
				batches.computeIfAbsent(new String(call % 2 == 0 ? "glass" : "body"), key -> pool.acquire()).add(call);
			}
			assertEquals(List.of(0, 2, 4, 6, 8, 10), batches.get("glass"));
			assertEquals(List.of(1, 3, 5, 7, 9, 11), batches.get("body"));
			pool.clearBatches(batches);
			assertTrue(batches.isEmpty());
		}
	}

	@Test
	void externalListsAreRemovedWithoutMutatingTheirContents() {
		final RenderBatchListPool pool = new RenderBatchListPool(4, 64, 128);
		final Map<String, List<Object>> batches = new HashMap<>();
		final Object call = new Object();
		final List<Object> external = List.of(call);
		batches.put("external", external);
		pool.clearBatches(batches);
		assertTrue(batches.isEmpty());
		assertEquals(List.of(call), external);
	}

	@Test
	void aListReferencedByTwoDiscardedBucketsIsNotRecycledTwice() {
		final RenderBatchListPool pool = new RenderBatchListPool(4, 64, 128);
		final List<Object> calls = pool.acquire();
		calls.add(new Object());
		final Map<Integer, List<Object>> batches = new HashMap<>();
		batches.put(1, calls);
		batches.put(2, calls);
		pool.clearBatches(batches);
		final List<Object> next = pool.acquire();
		assertSame(calls, next);
		assertNotSame(next, pool.acquire());
	}

	@Test
	void anotherPoolCannotTakeOwnershipOfActiveStorage() {
		final RenderBatchListPool first = new RenderBatchListPool(4, 64, 128);
		final RenderBatchListPool second = new RenderBatchListPool(4, 64, 128);
		final List<Object> calls = first.acquire();
		calls.add(new Object());
		second.clearBatches(new HashMap<>(Map.of("foreign", calls)));
		assertTrue(calls.isEmpty());
		assertNotSame(calls, second.acquire());
	}

	@Test
	void listCountIsBoundedEvenWhenCapacityBudgetIsAvailable() {
		final RenderBatchListPool pool = new RenderBatchListPool(2, 64, 1024);
		final Map<Integer, List<Object>> batches = new HashMap<>();
		for (int material = 0; material < 10; material++) {
			final List<Object> calls = pool.acquire();
			calls.add(material);
			batches.put(material, calls);
		}
		final List<List<Object>> previous = List.copyOf(batches.values());
		pool.clearBatches(batches);
		int reused = 0;
		for (int material = 0; material < 10; material++) {
			final List<Object> calls = pool.acquire();
			if (previous.stream().anyMatch(list -> list == calls)) reused++;
		}
		assertEquals(2, reused);
	}

	@Test
	void threadLocalFactoryCannotShareStorageBetweenRenderThreads() throws Exception {
		final Function<Object, List<Object>> factory = RenderBatchListPool.factory();
		final List<Object> calls = factory.apply("main");
		calls.add(new Object());
		RenderBatchListPool.clear(new HashMap<>(Map.of("main", calls)));
		final AtomicReference<List<Object>> other = new AtomicReference<>();
		final Thread worker = new Thread(() -> other.set(factory.apply("worker")));
		worker.start();
		worker.join();
		assertNotSame(calls, other.get());
		assertSame(calls, factory.apply("main-again"));
		assertNotSame(calls, factory.apply("main-still-active"));
	}
}
