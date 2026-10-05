package org.mtr.cache;

import org.mtr.libraries.it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

/**
 * Long-keyed cache with automatic expiration.
 */
public final class GenericLongCache<T> extends GenericCacheBase<T, Long, Long2ObjectOpenHashMap<GenericCacheBase.DataHolder<T>>> {

        public GenericLongCache(int approximateTimeout, boolean canExpireWhileFetching) {
                super(new Long2ObjectOpenHashMap<>(), approximateTimeout, canExpireWhileFetching);
        }

        @Override
        protected DataHolder<T> get(Long2ObjectOpenHashMap<DataHolder<T>> map, Long key) {
                return map.get((long) key);
        }

        @Override
        protected void put(Long2ObjectOpenHashMap<DataHolder<T>> map, Long key, DataHolder<T> newData) {
                map.put((long) key, newData);
        }

        @Override
        protected void removeExpired(Long2ObjectOpenHashMap<DataHolder<T>> map, long currentTime) {
                map.values().removeIf(dataHolder -> dataHolder.timeout < currentTime);
        }

        @Override
        protected void remove(Long2ObjectOpenHashMap<DataHolder<T>> map, Long key) {
                map.remove((long) key);
        }
}
