package org.mtr.mod.render;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.ToIntFunction;
import static org.junit.jupiter.api.Assertions.*;

class RouteMapSpanCacheTest {
	@Test
	void frameWorldAndSearchSideChangesCannotReuseOldSpans() {
		final Object firstWorld = new Object(), owner = new Object(), otherSide = new Object();
		// Use the frame API directly; public render access separately checks mapped world identity.
		BlockEntityRenderCulling.begin(firstWorld, null);
		assertNotNull(BlockEntityRenderCulling.routeSpans(firstWorld, owner));
		final RouteMapSpanCache first = BlockEntityRenderCulling.routeSpans(firstWorld, owner);
		assertSame(first, BlockEntityRenderCulling.routeSpans(firstWorld, owner));
		assertNotSame(first, BlockEntityRenderCulling.routeSpans(firstWorld, otherSide));
		assertNull(BlockEntityRenderCulling.routeSpans(new Object(), owner));
		BlockEntityRenderCulling.end();
		assertNull(BlockEntityRenderCulling.routeSpans(firstWorld, owner));
		BlockEntityRenderCulling.begin(firstWorld, null);
		assertNotSame(first, BlockEntityRenderCulling.routeSpans(firstWorld, owner));
		BlockEntityRenderCulling.end();
	}

	@Test
	void longRunsKeepCorrectDistancePastTheRetentionLimit() {
		final RouteMapSpanCache cache = new RouteMapSpanCache();
		final ToIntFunction<BlockPos> states = pos -> pos.getX() >= 70000 ? 0 : 1;
		assertEquals(69999, cache.distance(BlockPos.ORIGIN, Direction.EAST, states));
		assertEquals(69998, cache.distance(new BlockPos(1, 0, 0), Direction.EAST, states));
	}

	@Test
	void longPlatformQueriesShareTheDirectionalWalk() throws Exception {
		final Class<?> type = Class.forName("org.mtr.mod.render.RouteMapSpanCache");
		final Object cache = type.getConstructor().newInstance();
		final var get = type.getMethod("distance", BlockPos.class, Direction.class, ToIntFunction.class);
		final AtomicInteger reads = new AtomicInteger();
		final ToIntFunction<BlockPos> states = pos -> { reads.incrementAndGet(); return pos.getX() < 0 || pos.getX() >= 256 ? 0 : pos.getX() == 255 ? 3 : pos.getX() == 0 ? 5 : 1; };
		for (int x = 0; x < 256; x++) assertEquals(255 - x, get.invoke(cache, new BlockPos(x, 70, 0), Direction.EAST, states));
		assertTrue(reads.get() <= 512, "every block rescanned the platform: " + reads);
	}

	@Test
	void boundariesBreaksAndReverseDirectionsMatchOriginalWalks() throws Exception {
		final Class<?> type = Class.forName("org.mtr.mod.render.RouteMapSpanCache");
		final Object cache = type.getConstructor().newInstance();
		final var get = type.getMethod("distance", BlockPos.class, Direction.class, ToIntFunction.class);
		final int[] kinds = {5, 1, 3, 5, 3, 0, 1, 1, 0, 5, 1, 1, 3};
		for (int direction : new int[]{1, -1}) {
			final ToIntFunction<BlockPos> states = pos -> {
				final int x = pos.getX();
				if (x < 0 || x >= kinds.length) return 0;
				final int kind = kinds[x];
				return direction > 0 || kind < 3 ? kind : kind == 3 ? 5 : 3;
			};
			for (int x : new int[]{7, 11, 3, 1, 0, 2, 4, 6, 9, 10, 12}) {
				int length = 0;
				while (true) {
					final int kind = states.applyAsInt(new BlockPos(x + direction * length, 70, 0));
					if (kind == 0 || length > 0 && (kind & 4) != 0) break;
					length++;
					if ((kind & 2) != 0) break;
				}
				assertEquals(length - 1, get.invoke(cache, new BlockPos(x, 70, 0), direction > 0 ? Direction.EAST : Direction.WEST, states));
			}
		}
	}
}
