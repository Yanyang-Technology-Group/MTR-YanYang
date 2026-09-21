package org.mtr.mod.render;

import java.util.function.UnaryOperator;

public final class RailIdNormalizationCache {
	private static final int CAPACITY = 2048;
	private final String[] keys = new String[CAPACITY];
	private final String[] values = new String[CAPACITY];

	// Normalization is a pure function of the ID; never cache live tilt settings.
	// Each calling thread owns its cache, with bounded memory and no locking.
	public String normalize(String id, UnaryOperator<String> original) {
		if (id == null || id.length() > 256) {
			return original.apply(id);
		}
		final int hash = id.hashCode();
		final int index = (hash ^ (hash >>> 16)) & (CAPACITY - 1);
		if (id.equals(keys[index])) {
			return values[index];
		}
		final String result = original.apply(id);
		keys[index] = id;
		values[index] = result;
		return result;
	}
}
