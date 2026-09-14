package org.mtr.mod.render;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.render.Frustum;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import org.mtr.mapping.holder.World;
import org.mtr.mapping.mapper.OptimizedRenderer;
import java.util.IdentityHashMap;
import java.util.Map;

public final class BlockEntityRenderCulling {
	private static final ThreadLocal<Frame> FRAME = new ThreadLocal<>();

	public static void init() {
		WorldRenderEvents.START.register(context -> end());
		WorldRenderEvents.AFTER_SETUP.register(context -> begin(context.world(), context.frustum()));
		WorldRenderEvents.END.register(context -> end());
	}

	public static boolean isVisible(World world, org.mtr.mapping.holder.BlockPos pos, double padding) {
		// Shadow passes have a different view volume and must not use the camera frustum.
		return OptimizedRenderer.renderingShadows() || visible(world.data, pos.data, padding);
	}

	static void begin(Object world, Frustum frustum) {
		FRAME.set(new Frame(world, frustum, new IdentityHashMap<>()));
	}

	static RouteMapSpanCache routeSpans(World world, Object renderer) {
		return OptimizedRenderer.renderingShadows() ? null : routeSpans(world.data, renderer);
	}

	static RouteMapSpanCache routeSpans(Object world, Object renderer) {
		final Frame frame = FRAME.get();
		return frame == null || frame.world != world ? null : frame.spans.computeIfAbsent(renderer, ignored -> new RouteMapSpanCache());
	}

	static void end() {
		FRAME.remove();
	}

	static boolean visible(Object world, BlockPos pos, double padding) {
		final Frame frame = FRAME.get();
		return frame == null || frame.world != world || frame.frustum == null || frame.frustum.isVisible(new Box(
				pos.getX() - padding, pos.getY() - padding, pos.getZ() - padding,
				pos.getX() + 1 + padding, pos.getY() + 1 + padding, pos.getZ() + 1 + padding
		));
	}

	private record Frame(Object world, Frustum frustum, Map<Object, RouteMapSpanCache> spans) {
	}
}
