package org.mtr.mod.client;

import org.mtr.libraries.it.unimi.dsi.fastutil.objects.Object2ObjectArrayMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectCollection;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectSet;

import java.util.function.BiFunction;

final class HashBackedArrayMap<K, V> extends Object2ObjectArrayMap<K, V> {

	private static final long serialVersionUID = 1L;
	private final Object2ObjectOpenHashMap<K, V> backing = new Object2ObjectOpenHashMap<>();

	@Override
	public V get(Object key) {
		return backing.get(key);
	}

	@Override
	public V put(K key, V value) {
		return backing.put(key, value);
	}

	@Override
	public V remove(Object key) {
		return backing.remove(key);
	}

	@Override
	public V computeIfPresent(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
		return backing.computeIfPresent(key, remappingFunction);
	}

	@Override
	public V merge(K key, V value, BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
		return backing.merge(key, value, remappingFunction);
	}

	@Override
	public boolean containsKey(Object key) {
		return backing.containsKey(key);
	}

	@Override
	public boolean containsValue(Object value) {
		return backing.containsValue(value);
	}

	@Override
	public int size() {
		return backing.size();
	}

	@Override
	public boolean isEmpty() {
		return backing.isEmpty();
	}

	@Override
	public void clear() {
		backing.clear();
	}

	@Override
	public void defaultReturnValue(V value) {
		super.defaultReturnValue(value);
		backing.defaultReturnValue(value);
	}

	@Override
	public Object2ObjectMap.FastEntrySet<K, V> object2ObjectEntrySet() {
		return backing.object2ObjectEntrySet();
	}

	@Override
	public ObjectSet<K> keySet() {
		return backing.keySet();
	}

	@Override
	public ObjectCollection<V> values() {
		return backing.values();
	}

	@Override
	public HashBackedArrayMap<K, V> clone() {
		final HashBackedArrayMap<K, V> copy = new HashBackedArrayMap<>();
		copy.defaultReturnValue(defaultReturnValue());
		copy.putAll(this);
		return copy;
	}
}
