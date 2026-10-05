package org.mtr.cache;

import java.util.Random;
import java.util.function.Supplier;

/**
 * Represents an object cache referenced by a key. When not fetched in a while or after a timeout, the object expires.
 *
 * @param <T> the object type
 */
public abstract class GenericCacheBase<T, U, V> {

        private static final Random RANDOM = new Random();

        private long lastChecked;
        private final V data;
        private final int approximateTimeout;
        private final boolean canExpireWhileFetching;

        public GenericCacheBase(V mapInstance, int approximateTimeout, boolean canExpireWhileFetching) {
                data = mapInstance;
                this.approximateTimeout = approximateTimeout;
                this.canExpireWhileFetching = canExpireWhileFetching;
        }

        public final T get(U key, Supplier<T> createInstance) {
                final long currentTime = System.currentTimeMillis();

                // Every 100 ms, check if the cache has expired data and clear it
                if (currentTime - lastChecked > 100) {
                        removeExpired(data, currentTime);
                        lastChecked = currentTime;
                }

                // Get cached data
                final DataHolder<T> dataHolder = get(data, key);
                final long newTimeout = currentTime + approximateTimeout + RANDOM.nextInt(approximateTimeout / 2);
                if (dataHolder == null) {
                        final T newData = createInstance.get();
                        put(data, key, new DataHolder<>(newTimeout, newData));
                        return newData;
                } else {
                        if (!canExpireWhileFetching) {
                                dataHolder.timeout = newTimeout;
                        }
                        return dataHolder.data;
                }
        }

        protected abstract DataHolder<T> get(V map, U key);

        protected abstract void put(V map, U key, DataHolder<T> newData);

        /**
         * Removes every entry whose timeout has passed. The previous implementation collected
         * the key currently being queried instead of the expired entries' keys, so expired
         * entries were never removed and freshly queried keys were dropped instead.
         */
        protected abstract void removeExpired(V map, long currentTime);

        protected abstract void remove(V map, U key);

        protected static class DataHolder<T> {

                long timeout;
                private final T data;

                private DataHolder(long timeout, T data) {
                        this.timeout = timeout;
                        this.data = data;
                }
        }
}
