package org.mtr.render;

import net.minecraft.client.culling.Frustum;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Per-frame frustum data shared by block entity renderers.
 *
 * <p>MTR's block entity renderers return {@code true} from
 * {@code shouldRenderOffScreen} because their models (platform screen doors, route
 * maps) extend well beyond the source block. As a side effect, vanilla renders them
 * every frame regardless of view direction. This class captures the level frustum once
 * per frame (at the world render event, before vanilla renders block entities) so
 * renderers can cheaply test an inflated bounding box against it.</p>
 *
 * <p>Everything fails open: without a frame (unknown level, shadow passes, hand
 * rendering, or before the world render event) {@link #isVisible} returns {@code true}
 * and renderers behave exactly as before.</p>
 *
 * <p>Ported from the YanYang 4.0.5 optimisation series.</p>
 */
public final class BlockEntityRenderCulling {

	private static final ThreadLocal<@Nullable Frame> FRAME = new ThreadLocal<>();

	/**
	 * Starts a new frame. Called from the world render event on both loaders, after the
	 * frustum is set up and before vanilla renders block entities.
	 */
	public static void begin(ClientLevel world, @Nullable Frustum frustum) {
		FRAME.set(new Frame(world, frustum, new IdentityHashMap<>()));
	}

	/**
	 * Ends the frame. Called at the very end of level rendering so shader shadow passes
	 * (which render block entities against a different view volume) never observe a
	 * stale camera frustum.
	 */
	public static void end() {
		FRAME.remove();
	}

	/**
	 * @param world   the level the block entity is in
	 * @param pos     the block entity position
	 * @param padding extra blocks added around the one-block box on every side
	 * @return whether a block entity at the given position may be visible this frame
	 */
	public static boolean isVisible(ClientLevel world, BlockPos pos, double padding) {
		final Frame frame = FRAME.get();
		return frame == null || frame.world != world || frame.frustum == null || frame.frustum.isVisible(new AABB(
			pos.getX() - padding, pos.getY() - padding, pos.getZ() - padding,
			pos.getX() + 1 + padding, pos.getY() + 1 + padding, pos.getZ() + 1 + padding
		));
	}

	/**
	 * Returns the per-frame route map span cache for the given renderer, shared by every
	 * block entity using it, or {@code null} if no frame is active.
	 */
	@Nullable
	static RouteMapSpanCache routeSpans(ClientLevel world, Object renderer) {
		final Frame frame = FRAME.get();
		return frame == null || frame.world != world ? null : frame.spans.computeIfAbsent(renderer, ignored -> new RouteMapSpanCache());
	}

	private record Frame(ClientLevel world, @Nullable Frustum frustum, Map<Object, RouteMapSpanCache> spans) {
	}
}
