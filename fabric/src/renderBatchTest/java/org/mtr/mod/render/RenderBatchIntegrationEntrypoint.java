package org.mtr.mod.render;

import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.mapping.mapper.OptimizedModel;
import org.mtr.mapping.render.batch.BatchManager;
import org.mtr.mapping.render.batch.MaterialProperties;
import org.mtr.mapping.render.object.VertexArray;
import org.mtr.mapping.render.shader.ShaderManager;
import org.mtr.mapping.render.vertex.VertexAttributeState;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

public final class RenderBatchIntegrationEntrypoint implements PreLaunchEntrypoint {

	@Override
	public void onPreLaunch() {
		int status = 1;
		try {
			checkSkippedTranslucentFrames();
			checkDrawBatchRecyclesAfterIteration();
			Files.writeString(Path.of("render-batch-test.ok"), "MTR_RENDER_BATCH_INTEGRATION_OK");
			System.out.println("MTR_RENDER_BATCH_INTEGRATION_OK: real transformed BatchManager queue, skipped translucency and post-draw recycling; GPU calls not exercised");
			status = 0;
		} catch (Throwable failure) {
			failure.printStackTrace();
		} finally {
			System.exit(status);
		}
	}

	private static void checkSkippedTranslucentFrames() throws Exception {
		final BatchManager manager = new BatchManager();
		final BatchManager otherManager = new BatchManager();
		final VertexArray firstArray = array(OptimizedModel.ShaderType.TRANSLUCENT, 0xFF010203);
		final VertexArray equivalentArray = array(OptimizedModel.ShaderType.TRANSLUCENT, 0xFF010203);
		final VertexArray otherMaterialArray = array(OptimizedModel.ShaderType.TRANSLUCENT, 0xFF040506);
		Map<List<?>, Object[]> previous = new IdentityHashMap<>();
		for (int frame = 0; frame < 8; frame++) {
			final VertexAttributeState firstState = new VertexAttributeState(Integer.valueOf(frame), Integer.valueOf(0x00F000F0));
			final VertexAttributeState secondState = new VertexAttributeState(Integer.valueOf(frame + 100), Integer.valueOf(0));
			manager.queue(List.of(firstArray, equivalentArray), firstState);
			manager.queue(firstArray, secondState);
			final Map<?, List<?>> batches = batches(manager, "translucentBatches");
			check(batches.size() == 1, "Equal material properties no longer share one bucket");
			final List<?> calls = batches.values().iterator().next();
			check(calls instanceof ObjectArrayList, "The real queue did not use the pooled factory");
			check(calls.size() == 3, "A frame retained stale render calls");
			checkCall(calls.get(0), firstArray, firstState);
			checkCall(calls.get(1), equivalentArray, firstState);
			checkCall(calls.get(2), firstArray, secondState);
			if (!previous.isEmpty()) {
				check(previous.containsKey(calls), "The next frame did not reuse a cleared material list");
				check(((ObjectArrayList<?>) calls).elements() == previous.get(calls), "The next frame allocated a replacement backing array");
			}
			manager.queue(otherMaterialArray, secondState);
			otherManager.queue(firstArray, firstState);
			check(batches.size() == 2, "Distinct materials were merged");
			check(batches.get(otherMaterialArray.materialProperties) != calls, "Two active buckets share storage");
			check(batches(otherManager, "translucentBatches").values().iterator().next() != calls, "Two active renderers share storage");
			final Map<List<?>, Object[]> completed = new IdentityHashMap<>();
			for (final List<?> batch : batches.values()) completed.put(batch, ((ObjectArrayList<?>) batch).elements());
			for (final List<?> batch : batches(otherManager, "translucentBatches").values()) completed.put(batch, ((ObjectArrayList<?>) batch).elements());
			manager.drawAll(null, false);
			otherManager.drawAll(null, false);
			check(batches.isEmpty(), "Skipped transparent calls accumulated between frames");
			for (final Map.Entry<List<?>, Object[]> batch : completed.entrySet()) {
				check(batch.getKey().isEmpty(), "Recycled calls were not cleared");
				for (final Object call : batch.getValue()) check(call == null, "A recycled array retains a render call or matrix snapshot");
			}
			previous = completed;
		}
	}

	private static void checkDrawBatchRecyclesAfterIteration() throws Exception {
		final Method drawBatch = BatchManager.class.getDeclaredMethod("drawBatch", Map.class, ShaderManager.class);
		drawBatch.setAccessible(true);
		for (final OptimizedModel.ShaderType type : OptimizedModel.ShaderType.values()) {
			final BatchManager manager = new BatchManager();
			final VertexArray vertexArray = array(type, 0xFF010203);
			manager.queue(vertexArray, new VertexAttributeState(Integer.valueOf(123), null));
			final String bucket = vertexArray.materialProperties.translucent ? "translucentBatches" : vertexArray.materialProperties.cutoutHack ? "cutoutBatches" : "opaqueBatches";
			final Map<?, List<?>> original = batches(manager, bucket);
			check(original.size() == 1, "A shader type was queued into the wrong render phase");
			final List<?> calls = original.values().iterator().next();
			final Object[] storage = ((ObjectArrayList<?>) calls).elements();
			original.clear();
			final HeadlessBatches headless = new HeadlessBatches();
			headless.put(vertexArray.materialProperties, calls);
			drawBatch.invoke(null, headless, null);
			check(headless.iterated && headless.isEmpty(), "drawBatch no longer clears after its draw iteration");
			check(calls.isEmpty(), "The real drawBatch clear call did not recycle its list");
			for (final Object call : storage) check(call == null, "The post-draw clear retained a render call");
		}
	}

	private static VertexArray array(OptimizedModel.ShaderType type, int color) throws Exception {
		final Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
		unsafeField.setAccessible(true);
		final Unsafe unsafe = (Unsafe) unsafeField.get(null);
		final VertexArray vertexArray = (VertexArray) unsafe.allocateInstance(VertexArray.class);
		final Field material = VertexArray.class.getDeclaredField("materialProperties");
		material.setAccessible(true);
		material.set(vertexArray, new MaterialProperties(type, null, color));
		return vertexArray;
	}

	@SuppressWarnings("unchecked")
	private static Map<?, List<?>> batches(BatchManager manager, String name) throws Exception {
		final Field field = BatchManager.class.getDeclaredField(name);
		field.setAccessible(true);
		return (Map<?, List<?>>) field.get(manager);
	}

	private static void checkCall(Object call, VertexArray vertexArray, VertexAttributeState state) throws Exception {
		final Field arrayField = call.getClass().getDeclaredField("vertexArray");
		final Field stateField = call.getClass().getDeclaredField("vertexAttributeState");
		arrayField.setAccessible(true);
		stateField.setAccessible(true);
		check(arrayField.get(call) == vertexArray && stateField.get(call) == state, "Render call order or per-instance state changed");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}

	private static final class HeadlessBatches extends HashMap<Object, List<?>> {

		private boolean iterated;

		@Override
		public void forEach(BiConsumer<? super Object, ? super List<?>> gpuDraw) {
			for (final List<?> calls : values()) check(!calls.isEmpty(), "A list was recycled before draw iteration");
			iterated = true;
		}

		@Override
		public void clear() {
			check(iterated, "Batches were cleared before draw iteration");
			super.clear();
		}
	}
}
