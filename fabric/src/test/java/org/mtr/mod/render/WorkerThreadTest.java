package org.mtr.mod.render;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class WorkerThreadTest {
	@Test
	void requestsSubmittedWhileAVisibilityTaskRunsAreNotLost() throws Exception {
		final WorkerThread worker = new WorkerThread();
		final CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
		final List<Integer> executed = new ArrayList<>();
		final AtomicReference<Throwable> failure = new AtomicReference<>();
		worker.scheduleVehicles(ignored -> {
			started.countDown();
			try {
				release.await();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			executed.add(0);
		});
		final var field = worker.getClass().getDeclaredField("occlusionQueueVehicle");
		field.setAccessible(true);
		final var run = worker.getClass().getDeclaredMethod("run", field.getType(), Consumer.class);
		run.setAccessible(true);
		final Consumer<Consumer<Object>> execute = task -> task.accept(null);
		final Thread consumer = new Thread(() -> {
			try {
				run.invoke(null, field.get(worker), execute);
			} catch (Throwable e) {
				failure.set(e);
			}
		});
		consumer.start();
		try {
			assertTrue(started.await(5, TimeUnit.SECONDS));
			worker.scheduleVehicles(ignored -> executed.add(1));
			worker.scheduleVehicles(ignored -> executed.add(2));
		} finally {
			release.countDown();
			consumer.join(5000);
		}
		assertFalse(consumer.isAlive());
		assertNull(failure.get());
		run.invoke(null, field.get(worker), execute);
		assertEquals(List.of(0, 2), executed);
	}

	@Test
	void visibilityQueuesUseTheLatestCameraRequest() throws Exception {
		final WorkerThread worker = new WorkerThread();
		for (String category : new String[]{"Vehicle", "Lift", "Rail", "Misc"}) {
			final List<Integer> executed = new ArrayList<>();
			final String method = switch (category) {
				case "Vehicle" -> "scheduleVehicles";
				case "Lift" -> "scheduleLifts";
				case "Rail" -> "scheduleMTRRails";
				default -> "scheduleRails";
			};
			for (int i = 0; i < 100; i++) {
				final int frame = i;
				worker.getClass().getMethod(method, Consumer.class).invoke(worker, (Consumer<Object>) ignored -> executed.add(frame));
			}
			final var field = worker.getClass().getDeclaredField("occlusionQueue" + category);
			field.setAccessible(true);
			final var run = worker.getClass().getDeclaredMethod("run", field.getType(), Consumer.class);
			run.setAccessible(true);
			final Consumer<Consumer<Object>> execute = task -> task.accept(null);
			run.invoke(null, field.get(worker), execute);
			run.invoke(null, field.get(worker), execute);
			assertEquals(List.of(99), executed, category + " queue retained stale camera requests");
		}
	}

	@Test
	void generatesTexturesWithoutWaitingForAnOcclusionTick() throws Exception {
		final WorkerThread worker = new WorkerThread();
		final CountDownLatch generated = new CountDownLatch(1);
		final AtomicReference<Thread> textureThread = new AtomicReference<>();
		worker.scheduleDynamicTextures(() -> {
			textureThread.set(Thread.currentThread());
			generated.countDown();
		});
		// No occlusion tick is run: a long rail scan must not hold this task up.
		assertTrue(generated.await(5, TimeUnit.SECONDS), "Texture generation waited for the occlusion worker");
		assertNotSame(Thread.currentThread(), textureThread.get());
		assertNotSame(worker, textureThread.get());
	}

	@Test
	void runsTextureRequestsInOrderAndNeverConcurrently() throws Exception {
		final WorkerThread worker = new WorkerThread();
		final CountDownLatch firstStarted = new CountDownLatch(1);
		final CountDownLatch releaseFirst = new CountDownLatch(1);
		final CountDownLatch finished = new CountDownLatch(100);
		final List<Integer> order = new ArrayList<>();
		worker.scheduleDynamicTextures(() -> {
			firstStarted.countDown();
			try {
				releaseFirst.await();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			order.add(-1);
		});
		try {
			assertTrue(firstStarted.await(5, TimeUnit.SECONDS));
			for (int i = 0; i < 100; i++) {
				final int index = i;
				worker.scheduleDynamicTextures(() -> {
					order.add(index);
					finished.countDown();
				});
			}
			assertEquals(100, finished.getCount(), "Texture generators must remain serial");
		} finally {
			releaseFirst.countDown();
		}
		assertTrue(finished.await(5, TimeUnit.SECONDS));
		assertEquals(101, order.size());
		for (int i = 0; i < order.size(); i++) {
			assertEquals(i - 1, order.get(i));
		}
	}

	@Test
	void continuesAfterAFailedTextureRequest() throws Exception {
		final WorkerThread worker = new WorkerThread();
		final CountDownLatch finished = new CountDownLatch(1);
		worker.scheduleDynamicTextures(() -> { throw new IllegalStateException("Expected test failure"); });
		worker.scheduleDynamicTextures(finished::countDown);
		assertTrue(finished.await(5, TimeUnit.SECONDS), "One failed image prevented subsequent textures from loading");
	}
}
