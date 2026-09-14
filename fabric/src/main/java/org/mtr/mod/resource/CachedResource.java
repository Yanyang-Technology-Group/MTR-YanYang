package org.mtr.mod.resource;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.Iterator;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

public final class CachedResource<T> {

	@Nullable
	private T data;
	private long expiry;

	private final Supplier<T> dataSupplier;
	private final long lifespan;
	private final LongSupplier clock;

	private static boolean canFetchCache;
	private static final Set<CachedResource<?>> CACHED_RESOURCES = Collections.newSetFromMap(new WeakHashMap<>());

	public CachedResource(final Supplier<T> dataSupplier, final long lifespan) {
		this(dataSupplier, lifespan, System::currentTimeMillis);
	}

	CachedResource(Supplier<T> dataSupplier, long lifespan, LongSupplier clock) {
		this.dataSupplier = dataSupplier;
		this.lifespan = lifespan;
		this.clock = clock;
	}

	@Nullable
	public T getData(boolean force) {
		final long currentMillis = clock.getAsLong();
		// Hits must renew even after another model has used this tick's load budget.
		if (data != null && currentMillis <= expiry) {
			expiry = currentMillis + lifespan;
			return data;
		}
		if (force || canFetchCache) {
			data = dataSupplier.get();
			canFetchCache = false;
			expiry = clock.getAsLong() + lifespan;
			if (data != null) {
				CACHED_RESOURCES.add(this);
			}
		}
		return data;
	}

	public static void tick() {
		canFetchCache = true;
		final Iterator<CachedResource<?>> iterator = CACHED_RESOURCES.iterator();
		while (iterator.hasNext()) {
			final CachedResource<?> cachedResource = iterator.next();
			if (cachedResource.clock.getAsLong() > cachedResource.expiry) {
				cachedResource.data = null;
				iterator.remove();
			}
		}
	}
}
