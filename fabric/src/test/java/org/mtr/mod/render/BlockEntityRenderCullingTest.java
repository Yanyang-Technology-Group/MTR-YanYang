package org.mtr.mod.render;

import net.minecraft.client.render.Frustum;
import net.minecraft.util.math.BlockPos;
import org.joml.Matrix4f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BlockEntityRenderCullingTest {
	private final Object world = new Object();

	@AfterEach
	void clearFrame() {
		BlockEntityRenderCulling.end();
	}

	@Test
	void rejectsBlocksBehindCameraAndKeepsVisibleAndIntersectingDoors() {
		final Frustum frustum = frustum(0, 0, 0);
		BlockEntityRenderCulling.begin(world, frustum);
		assertTrue(BlockEntityRenderCulling.visible(world, new BlockPos(0, 0, -10), 2));
		assertFalse(BlockEntityRenderCulling.visible(world, new BlockPos(0, 0, 10), 2));
		assertFalse(BlockEntityRenderCulling.visible(world, new BlockPos(100, 0, -10), 2));
		assertFalse(BlockEntityRenderCulling.visible(world, new BlockPos(12, 0, -10), 0));
		assertTrue(BlockEntityRenderCulling.visible(world, new BlockPos(12, 0, -10), 2));
	}

	@Test
	void worldChangesMissingFrustumsAndFrameEndFailOpen() {
		final BlockPos hidden = new BlockPos(100, 0, -10);
		assertTrue(BlockEntityRenderCulling.visible(world, hidden, 2));
		BlockEntityRenderCulling.begin(world, frustum(0, 0, 0));
		assertFalse(BlockEntityRenderCulling.visible(world, hidden, 2));
		assertTrue(BlockEntityRenderCulling.visible(new Object(), hidden, 2));
		BlockEntityRenderCulling.end();
		assertTrue(BlockEntityRenderCulling.visible(world, hidden, 2));
		BlockEntityRenderCulling.begin(world, null);
		assertTrue(BlockEntityRenderCulling.visible(world, hidden, 2));
	}

	@Test
	void usesCameraRelativeCoordinatesAtLargeWorldPositions() {
		BlockEntityRenderCulling.begin(world, frustum(1000000, 80, -1000000));
		assertTrue(BlockEntityRenderCulling.visible(world, new BlockPos(1000000, 80, -1000010), 2));
		assertFalse(BlockEntityRenderCulling.visible(world, new BlockPos(1000000, 80, -999990), 2));
	}

	private static Frustum frustum(double x, double y, double z) {
		final Frustum frustum = new Frustum(new Matrix4f(), new Matrix4f().perspective((float) Math.toRadians(90), 1, 0.1F, 256));
		frustum.setPosition(x, y, z);
		return frustum;
	}
}
