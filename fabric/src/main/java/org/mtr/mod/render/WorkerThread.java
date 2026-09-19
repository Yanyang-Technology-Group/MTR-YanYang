package org.mtr.mod.render;

import com.logisticscraft.occlusionculling.DataProvider;
import com.logisticscraft.occlusionculling.OcclusionCullingInstance;
import net.minecraft.util.math.BlockPos;
import net.minecraft.client.world.ClientWorld;
import org.mtr.mapping.holder.MinecraftClient;
import org.mtr.mapping.mapper.MinecraftClientHelper;
import org.mtr.mod.CustomThread;
import org.mtr.mod.Init;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * A background thread to perform intensive rendering tasks (e.g. Occlusion culling, generate dynamic textures etc.)
 */
public final class WorkerThread extends CustomThread {

	private static final int MAX_OCCLUSION_CHUNK_DISTANCE = 32;
	private int renderDistance;
	private OcclusionCullingInstance occlusionCullingInstance;
	// Visibility requests are complete snapshots. Only the latest pending frame is useful.
	private final AtomicReference<Consumer<OcclusionCullingInstance>> occlusionQueueVehicle = new AtomicReference<>();
	private final AtomicReference<Consumer<OcclusionCullingInstance>> occlusionQueueLift = new AtomicReference<>();
	private final AtomicReference<Consumer<OcclusionCullingInstance>> occlusionQueueMisc = new AtomicReference<>();
	private final AtomicReference<Consumer<OcclusionCullingInstance>> occlusionQueueRail = new AtomicReference<>();
	// A rail visibility pass can take seconds in a large network. Texture generation
	// must not wait for it, but must remain serial because the generators share state.
	private final ExecutorService dynamicTextureExecutor = new ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), runnable -> {
		final Thread thread = new Thread(runnable, "MTR Dynamic Textures");
		thread.setDaemon(true);
		thread.setPriority(Thread.NORM_PRIORITY);
		return thread;
	});

	@Override
	protected void runTick() {
		try {
			Thread.sleep(10); // Give the CPU a little break
		} catch (InterruptedException e) {
		}

		if (occlusionQueueVehicle.get() != null || occlusionQueueLift.get() != null || occlusionQueueMisc.get() != null || occlusionQueueRail.get() != null) {
			updateInstance();
			occlusionCullingInstance.resetCache();
			run(occlusionQueueVehicle, task -> task.accept(occlusionCullingInstance));
			run(occlusionQueueLift, task -> task.accept(occlusionCullingInstance));
			run(occlusionQueueRail, task -> task.accept(occlusionCullingInstance));
			run(occlusionQueueMisc, task -> task.accept(occlusionCullingInstance));
		}
	}

	@Override
	protected boolean isRunning() {
		return MinecraftClient.getInstance().isRunning();
	}

	public void scheduleVehicles(Consumer<OcclusionCullingInstance> consumer) {
		occlusionQueueVehicle.set(consumer);
	}

	public void scheduleLifts(Consumer<OcclusionCullingInstance> consumer) {
		occlusionQueueLift.set(consumer);
	}

	@Deprecated
	public void scheduleRails(Consumer<OcclusionCullingInstance> consumer) {
		occlusionQueueMisc.set(consumer);
	}

	public void scheduleMTRRails(Consumer<OcclusionCullingInstance> consumer) {
		occlusionQueueRail.set(consumer);
	}

	public void scheduleDynamicTextures(Runnable runnable) {
		dynamicTextureExecutor.execute(() -> {
			try {
				runnable.run();
			} catch (Exception e) {
				Init.LOGGER.error("Could not generate dynamic texture", e);
			}
		});
	}

	private void updateInstance() {
		final int newRenderDistance = MinecraftClientHelper.getRenderDistance();
		if (occlusionCullingInstance == null || renderDistance != newRenderDistance) {
			renderDistance = newRenderDistance;
			occlusionCullingInstance = new BoundedOcclusionCullingInstance(Math.min(renderDistance, MAX_OCCLUSION_CHUNK_DISTANCE) * 16, new CullingDataProvider());
		}
	}

	private static <T> void run(AtomicReference<T> queue, Consumer<T> consumer) {
		final T task = queue.getAndSet(null);
		if (task != null) {
			try {
				consumer.accept(task);
			} catch (Exception e) {
				Init.LOGGER.error("", e);
			}
		}
	}

	private static final class CullingDataProvider implements DataProvider {

		private final BlockPos.Mutable blockPos = new BlockPos.Mutable();
		private ClientWorld clientWorld = null;

		@Override
		public boolean prepareChunk(int chunkX, int chunkZ) {
			clientWorld = net.minecraft.client.MinecraftClient.getInstance().world;
			return clientWorld != null;
		}

		@Override
		public boolean isOpaqueFullCube(int x, int y, int z) {
			blockPos.set(x, y, z);
			return clientWorld != null && clientWorld.getBlockState(blockPos).isOpaqueFullCube(clientWorld, blockPos);
		}

		@Override
		public void cleanup() {
			clientWorld = null;
		}
	}
}
