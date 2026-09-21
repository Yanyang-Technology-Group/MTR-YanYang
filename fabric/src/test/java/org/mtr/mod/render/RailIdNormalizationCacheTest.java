package org.mtr.mod.render;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.*;

class RailIdNormalizationCacheTest {
	@Test
	void repeatedRailSegmentsNormalizeEqualIdsOnlyOnce() {
		final RailIdNormalizationCache cache = new RailIdNormalizationCache();
		final AtomicInteger calls = new AtomicInteger();
		final UnaryOperator<String> normalize = id -> {
			calls.incrementAndGet();
			return "1-2-3-4-5-6";
		};
		for (int i = 0; i < 1000; i++) {
			assertEquals("1-2-3-4-5-6", cache.normalize(new String("4-5-6-1-2-3"), normalize));
		}
		assertEquals(1, calls.get());
	}

	@Test
	void collidingIdsNeverReuseAnotherRailsResult() {
		final RailIdNormalizationCache cache = new RailIdNormalizationCache();
		assertEquals("Aa".hashCode(), "BB".hashCode());
		for (int i = 0; i < 20; i++) {
			assertEquals("Aa-result", cache.normalize("Aa", id -> id + "-result"));
			assertEquals("BB-result", cache.normalize("BB", id -> id + "-result"));
		}
	}

	@Test
	void nullEmptyAndInvalidIdsKeepTheOriginalBehavior() {
		final RailIdNormalizationCache cache = new RailIdNormalizationCache();
		final UnaryOperator<String> original = id -> id == null ? "" : id;
		assertEquals("", cache.normalize(null, original));
		assertEquals("", cache.normalize("", original));
		assertEquals("not-a-rail", cache.normalize("not-a-rail", original));
		assertEquals("not-a-rail", cache.normalize("not-a-rail", original));
	}

	@Test
	void failedNormalizationCanBeRetried() {
		final RailIdNormalizationCache cache = new RailIdNormalizationCache();
		assertThrows(IllegalArgumentException.class, () -> cache.normalize("rail", id -> { throw new IllegalArgumentException(); }));
		assertEquals("recovered", cache.normalize("rail", id -> "recovered"));
	}
}
