package org.mtr.mod.client;

import org.junit.jupiter.api.Test;
import org.mtr.core.data.*;
import org.mtr.core.tool.Angle;
import org.mtr.libraries.it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class RailGeometryCacheTest {
	@Test
	void samplesMatchDirectRenderingForDifferentShapesAndOffsets() {
		final RailGeometryCache cache = new RailGeometryCache();
		for (final Rail.Shape shape : Rail.Shape.values()) {
			final Rail rail = Rail.newPlatformRail(new Position(-50, 10, -30), Angle.E, new Position(200, 30, 100), Angle.SE, shape, 5, new ObjectArrayList<>(), TransportMode.TRAIN);
			for (final double interval : new double[]{0.5, 1, 3.5}) {
				final DoubleArrayList expected = new DoubleArrayList();
				rail.railMath.render(collect(expected), interval, -0.5F, 0.5F);
				for (int repeat = 0; repeat < 3; repeat++) {
					final DoubleArrayList actual = new DoubleArrayList();
					cache.render(rail.railMath, collect(actual), interval, -0.5F, 0.5F);
					assertArrayEquals(expected.toDoubleArray(), actual.toDoubleArray());
				}
			}
		}
	}

	@Test
	void sampleMemoryIsBoundedAcrossManyRailsAndVeryLongRailsStillRender() {
		final RailGeometryCache cache = new RailGeometryCache();
		for (int i = 0; i < 200; i++) {
			final Rail rail = Rail.newPlatformRail(new Position(0, 0, i), Angle.E, new Position(600, 0, i), Angle.E, Rail.Shape.QUADRATIC, 0, new ObjectArrayList<>(), TransportMode.TRAIN);
			cache.render(rail.railMath, (x1, z1, x2, z2, x3, z3, x4, z4, y1, y2) -> {}, 0.5, 0, 0);
			assertTrue(cache.getCachedDoubleCount() <= 2 * 1024 * 1024);
		}
		final Rail longRail = Rail.newPlatformRail(new Position(0, 0, 0), Angle.E, new Position(9000, 0, 0), Angle.E, Rail.Shape.QUADRATIC, 0, new ObjectArrayList<>(), TransportMode.TRAIN);
		final RailGeometryCache longCache = new RailGeometryCache();
		final DoubleArrayList expected = new DoubleArrayList();
		longRail.railMath.render(collect(expected), 1, 0, 0);
		for (int i = 0; i < 2; i++) {
			final DoubleArrayList actual = new DoubleArrayList();
			longCache.render(longRail.railMath, collect(actual), 1, 0, 0);
			assertArrayEquals(expected.toDoubleArray(), actual.toDoubleArray());
			assertEquals(0, longCache.getCachedDoubleCount());
		}
	}

	@Test
	void railReplacementInvalidatesOldSamples() {
		final RailGeometryCache cache = new RailGeometryCache();
		final Rail original = Rail.newPlatformRail(new Position(0, 0, 0), Angle.E, new Position(100, 0, 0), Angle.E, Rail.Shape.QUADRATIC, 0, new ObjectArrayList<>(), TransportMode.TRAIN);
		final Rail replacement = Rail.copy(original, Rail.Shape.CABLE, 10);
		cache.render(original.railMath, collect(new DoubleArrayList()), 1, 0, 0);
		assertTrue(cache.getCachedDoubleCount() > 0);
		cache.retainRails(Collections.singleton(replacement));
		assertEquals(0, cache.getCachedDoubleCount());
		final DoubleArrayList expected = new DoubleArrayList();
		final DoubleArrayList actual = new DoubleArrayList();
		replacement.railMath.render(collect(expected), 1, 0, 0);
		cache.render(replacement.railMath, collect(actual), 1, 0, 0);
		assertArrayEquals(expected.toDoubleArray(), actual.toDoubleArray());
	}

	private static RailMath.RenderRail collect(DoubleArrayList values) {
		return (x1, z1, x2, z2, x3, z3, x4, z4, y1, y2) -> {
			values.add(x1); values.add(z1); values.add(x2); values.add(z2);
			values.add(x3); values.add(z3); values.add(x4); values.add(z4);
			values.add(y1); values.add(y2);
		};
	}
}
