package org.mtr.mod.client;

import org.junit.jupiter.api.Test;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.Object2ObjectArrayMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.Object2ObjectFunction;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class HashBackedArrayMapTest {

	@Test
	void legacyMapTypeKeepsHashLookupComplexity() {
		final Object2ObjectArrayMap<CountingKey, Integer> map = new HashBackedArrayMap<>();
		for (int index = 0; index < 10000; index++) map.put(new CountingKey(index), index);
		CountingKey.comparisons = 0;
		for (int index = 0; index < 10000; index++) assertEquals(Integer.valueOf(index), map.get(new CountingKey(index)));
		assertTrue(CountingKey.comparisons <= 20000, "Lookups used " + CountingKey.comparisons + " equality comparisons");
	}

	@Test
	void collectionViewsStayLiveAndMutationsReachTheSameBackingMap() {
		final Object2ObjectArrayMap<String, Integer> map = new HashBackedArrayMap<>();
		final var keys = map.keySet();
		final var values = map.values();
		final var entries = map.object2ObjectEntrySet();
		map.put("first", 1);
		map.put("second", null);
		map.put(null, 3);
		assertEquals(3, keys.size());
		assertEquals(3, values.size());
		assertEquals(3, entries.size());
		assertTrue(keys.remove("first"));
		assertFalse(map.containsKey("first"));
		assertTrue(values.remove(Integer.valueOf(3)));
		assertFalse(map.containsKey(null));
		entries.iterator().next().setValue(4);
		assertEquals(Integer.valueOf(4), map.get("second"));
		final var iterator = values.iterator();
		assertEquals(Integer.valueOf(4), iterator.next());
		iterator.remove();
		assertTrue(map.isEmpty());
		map.put("third", 5);
		assertTrue(keys.contains("third"));
		assertTrue(values.removeIf(value -> value == 5));
		assertTrue(entries.isEmpty());
		map.put("fourth", 6);
		keys.clear();
		assertTrue(map.isEmpty());
		map.put("fifth", 7);
		map.entrySet().clear();
		assertTrue(values.isEmpty());
	}

	@Test
	void mapDefaultsAndDefaultReturnValueObserveAllWrites() {
		final Object2ObjectArrayMap<String, Integer> map = new HashBackedArrayMap<>();
		map.defaultReturnValue(-1);
		assertEquals(Integer.valueOf(-1), map.get("missing"));
		assertEquals(Integer.valueOf(-1), map.put("first", 1));
		assertEquals(Integer.valueOf(1), map.putIfAbsent("first", 7));
		assertEquals(Integer.valueOf(-1), map.putIfAbsent("second", 2));
		assertEquals(Integer.valueOf(4), map.compute("first", (key, value) -> value + 3));
		assertEquals(Integer.valueOf(12), map.merge("second", 10, Integer::sum));
		assertEquals(Integer.valueOf(20), map.computeIfAbsent("third", (Object2ObjectFunction<String, Integer>) key -> 20));
		assertTrue(map.replace("third", 20, 30));
		assertEquals(Integer.valueOf(30), map.remove("third"));
		assertEquals(Integer.valueOf(-1), map.remove("missing"));
		map.put(null, null);
		assertTrue(map.containsKey(null));
		assertTrue(map.containsValue(null));
		assertNull(map.getOrDefault(null, 100));
		assertEquals(Integer.valueOf(100), map.getOrDefault("missing", 100));
		map.remove(null);
		map.replaceAll((key, value) -> value + 1);
		assertEquals(Map.of("first", 5, "second", 13), map);
		final Map<String, Integer> observed = new HashMap<>();
		map.forEach(observed::put);
		assertEquals(map, observed);
		map.clear();
		assertTrue(map.isEmpty());
		assertEquals(Integer.valueOf(-1), map.get("missing"));
		map.defaultReturnValue(null);
		assertEquals(Integer.valueOf(20), ((Map<String, Integer>) map).computeIfAbsent("third", key -> 20));
	}

	@Test
	void nullValueRemappingKeepsOpenHashMapSemantics() {
		final Object2ObjectArrayMap<String, Integer> map = new HashBackedArrayMap<>();
		for (final Integer defaultValue : new Integer[]{null, -1}) {
			map.defaultReturnValue(defaultValue);
			for (final String key : new String[]{"nullable", null}) {
				map.put(key, null);
				assertEquals(defaultValue, map.computeIfPresent(key, (presentKey, value) -> fail("Null values must not be remapped")));
				assertTrue(map.containsKey(key));
				assertNull(map.get(key));
				assertEquals(Integer.valueOf(3), map.merge(key, 3, (previous, next) -> fail("Null values must be replaced without remapping")));
				assertEquals(Integer.valueOf(4), map.computeIfPresent(key, (presentKey, value) -> value + 1));
				assertEquals(Integer.valueOf(6), map.merge(key, 2, Integer::sum));
				assertEquals(defaultValue, map.computeIfPresent(key, (presentKey, value) -> null));
				assertFalse(map.containsKey(key));
			}
		}
	}

	@Test
	void cloningDoesNotShareStorageOrCollectionViews() {
		final HashBackedArrayMap<String, Integer> original = new HashBackedArrayMap<>();
		original.defaultReturnValue(-1);
		original.put("first", 1);
		final var originalValues = original.values();
		final HashBackedArrayMap<String, Integer> copy = original.clone();
		assertEquals(original, copy);
		assertEquals(Integer.valueOf(-1), copy.defaultReturnValue());
		copy.put("first", 2);
		copy.put("second", 3);
		copy.defaultReturnValue(-2);
		assertEquals(Integer.valueOf(1), original.get("first"));
		assertFalse(original.containsKey("second"));
		assertEquals(Integer.valueOf(-1), original.get("missing"));
		assertEquals(Integer.valueOf(-2), copy.get("missing"));
		copy.values().clear();
		assertEquals(1, originalValues.size());
		assertEquals(Integer.valueOf(1), originalValues.iterator().next());
	}

	@Test
	@SuppressWarnings("unchecked")
	void serializationPreservesContentsDefaultReturnValueAndIndependentStorage() throws Exception {
		final HashBackedArrayMap<String, Integer> original = new HashBackedArrayMap<>();
		original.defaultReturnValue(-1);
		original.put("first", 1);
		original.put("second", null);
		original.put(null, 3);
		final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		try (final ObjectOutputStream output = new ObjectOutputStream(buffer)) {
			output.writeObject(original);
		}
		final HashBackedArrayMap<String, Integer> restored;
		try (final ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(buffer.toByteArray()))) {
			restored = (HashBackedArrayMap<String, Integer>) input.readObject();
		}
		assertEquals(original, restored);
		assertEquals(original.hashCode(), restored.hashCode());
		assertEquals(Integer.valueOf(-1), restored.defaultReturnValue());
		assertEquals(Integer.valueOf(-1), restored.get("missing"));
		assertTrue(restored.containsKey("second"));
		assertNull(restored.get("second"));
		restored.values().remove(Integer.valueOf(3));
		assertFalse(restored.containsKey(null));
		assertTrue(original.containsKey(null));
		restored.put("third", 4);
		assertFalse(original.containsKey("third"));
	}

	@Test
	void ordinaryMapOperationsMatchHashMapAcrossUpdatesAndNulls() {
		final Object2ObjectArrayMap<String, Integer> actual = new HashBackedArrayMap<>();
		final Map<String, Integer> expected = new HashMap<>();
		final Random random = new Random(20261023);
		for (int operation = 0; operation < 2000; operation++) {
			final String key = random.nextInt(16) == 0 ? null : "rail-" + random.nextInt(32);
			final Integer value = random.nextInt(8) == 0 ? null : random.nextInt(100);
			switch (random.nextInt(5)) {
				case 0, 1 -> assertEquals(expected.put(key, value), actual.put(key, value));
				case 2 -> assertEquals(expected.remove(key), actual.remove(key));
				case 3 -> assertEquals(expected.keySet().remove(key), actual.keySet().remove(key));
				default -> assertEquals(expected.get(key), actual.get(key));
			}
			assertEquals(expected, actual);
			assertEquals(actual, expected);
			assertEquals(expected.hashCode(), actual.hashCode());
			assertEquals(expected.size(), actual.size());
		}
	}

	private static final class CountingKey {

		private static int comparisons;
		private final int identifier;

		private CountingKey(int identifier) {
			this.identifier = identifier;
		}

		@Override
		public boolean equals(Object other) {
			comparisons++;
			return other instanceof CountingKey && identifier == ((CountingKey) other).identifier;
		}

		@Override
		public int hashCode() {
			return identifier;
		}
	}
}
