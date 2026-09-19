package org.mtr.mod.render;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class WorkerThreadTest {
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
