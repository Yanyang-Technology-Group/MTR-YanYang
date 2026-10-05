package org.mtr.client;

import org.mtr.core.data.Rail;
import org.mtr.core.data.RailMath;
import org.mtr.libraries.it.unimi.dsi.fastutil.doubles.DoubleArrayList;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Caches the geometry samples produced by {@link RailMath#render}.
 *
 * <p>Rails are re-rendered every frame and every visible rail re-walked its curve,
 * re-allocating several {@code Vector} objects per segment in the core rail math. The
 * samples only depend on the rail instance and the render parameters, so they are cached
 * here and replayed instead. Entries are evicted when the underlying rail disappears
 * from the client data (see {@link #retainRails}) or when the size bounds are hit.</p>
 *
 * <p>Ported from the YanYang 4.0.5 optimisation series, adapted to the 4.1.0-beta-2
 * 13-value segment layout.</p>
 */
public final class RailGeometryCache {

	private static final int DOUBLES_PER_SEGMENT = 13;
	private static final int MAX_DOUBLES = 2 * 1024 * 1024;
	private static final int MAX_ENTRY_DOUBLES = 64 * 1024;
	private static final int MAX_ENTRIES = 1024;
	private static final double[] UNCACHED = new double[0];
	private final LinkedHashMap<Key, double[]> samples = new LinkedHashMap<>(16, 0.75F, true);
	private int cachedDoubleCount;

	/**
	 * Renders a rail through the cache, replaying stored samples on a hit.
	 *
	 * @param railMath       the rail math to sample
	 * @param cacheable      whether the rail is persistent client data (ghost / preview rails
	 *                       churn every frame and are not worth caching)
	 * @param callback       the segment consumer
	 * @param interval       the sample interval, part of the cache key
	 * @param offsetRadius1  first lateral offset, part of the cache key
	 * @param offsetRadius2  second lateral offset, part of the cache key
	 */
	public void render(RailMath railMath, boolean cacheable, RailMath.RenderRail callback, double interval, float offsetRadius1, float offsetRadius2) {
		if (!cacheable) {
			railMath.render(callback, interval, offsetRadius1, offsetRadius2);
			return;
		}

		final Key key = new Key(railMath, interval, offsetRadius1, offsetRadius2);
		final double[] cached = samples.get(key);
		if (cached == UNCACHED) {
			railMath.render(callback, interval, offsetRadius1, offsetRadius2);
			return;
		}
		if (cached != null) {
			for (int i = 0; i < cached.length; i += DOUBLES_PER_SEGMENT) {
				callback.renderRail(
					cached[i], cached[i + 1], cached[i + 2], cached[i + 3], cached[i + 4], cached[i + 5],
					cached[i + 6], cached[i + 7], cached[i + 8], cached[i + 9], cached[i + 10], cached[i + 11],
					cached[i + 12]
				);
			}
			return;
		}

		final DoubleArrayList values = new DoubleArrayList();
		railMath.render((x1, y1, z1, x2, y2, z2, x3, y3, z3, x4, y4, z4, tiltAngle) -> {
			callback.renderRail(x1, y1, z1, x2, y2, z2, x3, y3, z3, x4, y4, z4, tiltAngle);
			if (values.size() <= MAX_ENTRY_DOUBLES) {
				values.add(x1);
				values.add(y1);
				values.add(z1);
				values.add(x2);
				values.add(y2);
				values.add(z2);
				values.add(x3);
				values.add(y3);
				values.add(z3);
				values.add(x4);
				values.add(y4);
				values.add(z4);
				values.add(tiltAngle);
			}
		}, interval, offsetRadius1, offsetRadius2);
		final double[] result = values.size() <= MAX_ENTRY_DOUBLES ? values.toDoubleArray() : UNCACHED;
		samples.put(key, result);
		cachedDoubleCount += result.length;
		final Iterator<double[]> iterator = samples.values().iterator();
		while (cachedDoubleCount > MAX_DOUBLES || samples.size() > MAX_ENTRIES) {
			cachedDoubleCount -= iterator.next().length;
			iterator.remove();
		}
	}

	/**
	 * Drops entries whose rail is no longer part of the given rail set. Called from
	 * {@link MinecraftClientData#sync()}.
	 */
	public void retainRails(Iterable<Rail> rails) {
		final Set<RailMath> retained = Collections.newSetFromMap(new IdentityHashMap<>());
		rails.forEach(rail -> retained.add(rail.railMath));
		final Iterator<Map.Entry<Key, double[]>> iterator = samples.entrySet().iterator();
		while (iterator.hasNext()) {
			final Map.Entry<Key, double[]> entry = iterator.next();
			if (!retained.contains(entry.getKey().railMath)) {
				cachedDoubleCount -= entry.getValue().length;
				iterator.remove();
			}
		}
	}

	private static final class Key {

		private final RailMath railMath;
		private final double interval;
		private final float offsetRadius1;
		private final float offsetRadius2;

		private Key(RailMath railMath, double interval, float offsetRadius1, float offsetRadius2) {
			this.railMath = railMath;
			this.interval = interval;
			this.offsetRadius1 = offsetRadius1;
			this.offsetRadius2 = offsetRadius2;
		}

		@Override
		public boolean equals(Object other) {
			if (!(other instanceof Key)) {
				return false;
			}
			final Key key = (Key) other;
			return railMath == key.railMath && Double.compare(interval, key.interval) == 0 && Float.compare(offsetRadius1, key.offsetRadius1) == 0 && Float.compare(offsetRadius2, key.offsetRadius2) == 0;
		}

		@Override
		public int hashCode() {
			return 31 * (31 * (31 * System.identityHashCode(railMath) + Double.hashCode(interval)) + Float.hashCode(offsetRadius1)) + Float.hashCode(offsetRadius2);
		}
	}
}
