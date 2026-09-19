package org.mtr.mod.render;

import com.logisticscraft.occlusionculling.DataProvider;
import com.logisticscraft.occlusionculling.OcclusionCullingInstance;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
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
import java.util.function.Consumer;

/**
 * A background thread to perform intensive rendering tasks (e.g. Occlusion culling, generate dynamic textures etc.)
 */
public final class WorkerThread extends CustomThread {

	private static final int MAX_OCCLUSION_CHUNK_DISTANCE = 32;
	private static final int MAX_QUEUE_SIZE = 2;
	private int renderDistance;
	private OcclusionCullingInstance occlusionCullingInstance;
	private final ObjectArrayList<Consumer<OcclusionCullingInstance>> occlusionQueueVehicle = new ObjectArrayList<>();
	private final ObjectArrayList<Consumer<OcclusionCullingInstance>> occlusionQueueLift = new ObjectArrayList<>();
	private final ObjectArrayList<Consumer<OcclusionCullingInstance>> occlusionQueueMisc = new ObjectArrayList<>();
	private final ObjectArrayList<Consumer<OcclusionCullingInstance>> occlusionQueueRail = new ObjectArrayList<>();
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

		if (!occlusionQueueVehicle.isEmpty() || !occlusionQueueLift.isEmpty() || !occlusionQueueMisc.isEmpty() || !occlusionQueueRail.isEmpty()) {
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
		if (occlusionQueueVehicle.size() < MAX_QUEUE_SIZE) {
			occlusionQueueVehicle.add(consumer);
		}
	}

	public void scheduleLifts(Consumer<OcclusionCullingInstance> consumer) {
		if (occlusionQueueLift.size() < MAX_QUEUE_SIZE) {
			occlusionQueueLift.add(consumer);
		}
	}

	@Deprecated
	public void scheduleRails(Consumer<OcclusionCullingInstance> consumer) {
		if (occlusionQueueMisc.size() < MAX_QUEUE_SIZE) {
			occlusionQueueMisc.add(consumer);
		}
	}

	public void scheduleMTRRails(Consumer<OcclusionCullingInstance> consumer) {
		if (occlusionQueueRail.size() < MAX_QUEUE_SIZE) {
			occlusionQueueRail.add(consumer);
		}
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
			occlusionCullingInstance = new OcclusionCullingInstance(Math.min(renderDistance, MAX_OCCLUSION_CHUNK_DISTANCE) * 16, new CullingDataProvider());
		}
	}

	private static <T> void run(ObjectArrayList<T> queue, Consumer<T> consumer) {
		if (!queue.isEmpty()) {
			try {
				final T task = queue.remove(0);
				if (task != null) {
					consumer.accept(task);
				}
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
