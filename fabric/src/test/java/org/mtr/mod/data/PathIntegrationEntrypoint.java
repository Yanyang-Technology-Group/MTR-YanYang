package org.mtr.mod.data;

import net.fabricmc.api.ModInitializer;
import org.mtr.core.data.*;
import org.mtr.core.path.SidingPathFinder;
import org.mtr.core.path.compute.PathComputePool;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/** Runs under real Fabric/Mixin before opening a world or starting a Minecraft server. */
public final class PathIntegrationEntrypoint implements ModInitializer {
	@Override
	public void onInitialize() {
		int status = 1;
		try {
			runChecks();
			System.out.println("MTR_PATH_INTEGRATION_OK: actual mixins, ordered commits, epoch rejection, fallback and shutdown");
			status = 0;
		} catch (Throwable e) {
			e.printStackTrace();
		} finally {
			ServerRailPaths.stop();
		}
		System.exit(status);
	}

	private static void runChecks() throws Exception {
		ServerRailPaths.start(false);
		Data data = RailPathSnapshotTest.network(0);
		Platform start = RailPathSnapshotTest.platform(data, 0, 1);
		Platform end = RailPathSnapshotTest.platform(data, 10, 11);
		ObjectArrayList<PathData> expected = RailPathSnapshotTest.original(data, start, end);
		assertTrue(expected.size() >= 2);
		ObjectArrayList<SidingPathFinder<Station, Platform, Station, Platform>> finders = new ObjectArrayList<>();
		for (int i = 0; i < 32; i++) finders.add(new SidingPathFinder<>(data, start, end, 3));
		assertTrue((Object) finders.get(0) instanceof ServerRailPaths.FinderAccess, "Constructor mixin was not applied");
		ObjectArrayList<PathData> path = new ObjectArrayList<>();
		AtomicBoolean done = new AtomicBoolean();
		SidingPathFinder.findPathTick(path, finders, 0, () -> done.set(true), (a, b) -> fail("Expected connected path"));
		assertFalse(done.get(), "Computations must wait for main-thread publication");
		assertTrue(path.isEmpty());
		ServerRailPaths.endTick();
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (!done.get() && System.nanoTime() < deadline) {
			ServerRailPaths.beginTick();
			SidingPathFinder.findPathTick(path, finders, 0, () -> done.set(true), (a, b) -> fail("Expected connected path"));
			ServerRailPaths.endTick();
			Thread.sleep(1);
		}
		assertTrue(done.get());
		for (int i = 0; i < 32; i++) RailPathSnapshotTest.assertPath(expected, new ObjectArrayList<>(path.subList(i * expected.size(), (i + 1) * expected.size())));
		var poolField = ServerRailPaths.class.getDeclaredField("pool");
		poolField.setAccessible(true);
		PathComputePool pool = (PathComputePool) poolField.get(null);
		assertEquals(1, pool.metrics.computations.sum());
		assertEquals(31, pool.metrics.merged.sum());

		finders.add(new SidingPathFinder<>(data, start, end, 3));
		path.clear();
		// Data.sync's real injected hook must invalidate a previously cached path.
		data.sync();
		SidingPathFinder.findPathTick(path, finders, 0, () -> fail("Old topology was reused"), (a, b) -> done.set(false));
		assertTrue(path.isEmpty());
		assertTrue(pool.metrics.stale.sum() > 0);

		ServerRailPaths.stop();
		assertTrue(pool.isTerminated());
		ServerRailPaths.start(true);
		assertNull(poolField.get(null), "Threaded simulation must retain the original serial owner");
		ServerRailPaths.start(false);
		Data fallbackData = RailPathSnapshotTest.network(0);
		finders.clear();
		finders.add(new SidingPathFinder<>(fallbackData, start, end, 3));
		AtomicBoolean offThreadHandled = new AtomicBoolean(true);
		Thread other = new Thread(() -> offThreadHandled.set(ServerRailPaths.process(path, finders, () -> { }, (a, b) -> { })));
		other.start();
		other.join(5000);
		assertFalse(other.isAlive());
		assertFalse(offThreadHandled.get(), "Off-thread commits must be rejected before accessing data");
		finders.add(new SidingPathFinder<>(data, start, end, 3));
		assertFalse(ServerRailPaths.process(path, finders, () -> fail("Mixed data must fall back"), (a, b) -> fail("Mixed data must fall back")));
		finders.remove(1);
		path.clear();
		done.set(false);
		deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (!done.get() && System.nanoTime() < deadline) {
			ServerRailPaths.beginTick();
			SidingPathFinder.findPathTick(path, finders, 0, () -> {
				ServerRailPaths.stop();
				done.set(true);
			}, (a, b) -> fail("Callback shutdown fixture must be connected"));
			ServerRailPaths.endTick();
			Thread.sleep(1);
		}
		assertTrue(done.get());
		ServerRailPaths.stop();
	}
}
