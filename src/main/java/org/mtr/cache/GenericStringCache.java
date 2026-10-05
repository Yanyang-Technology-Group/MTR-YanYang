package org.mtr.cache;

import org.mtr.libraries.it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;

public final class GenericStringCache<T> extends GenericCacheBase<T, String, Object2ObjectOpenHashMap<String, GenericCacheBase.DataHolder<T>>> {

        public GenericStringCache(int approximateTimeout, boolean canExpireWhileFetching) {
                super(new Object2ObjectOpenHashMap<>(), approximateTimeout, canExpireWhileFetching);
        }

        @Override
        protected DataHolder<T> get(Object2ObjectOpenHashMap<String, DataHolder<T>> map, String key) {
                return map.get(key);
        }

        @Override
        protected void put(Object2ObjectOpenHashMap<String, DataHolder<T>> map, String key, DataHolder<T> newData) {
                map.put(key, newData);
        }

        @Override
        protected void removeExpired(Object2ObjectOpenHashMap<String, DataHolder<T>> map, long currentTime) {
                map.values().removeIf(dataHolder -> dataHolder.timeout < currentTime);
        }

        @Override
        protected void remove(Object2ObjectOpenHashMap<String, DataHolder<T>> map, String key) {
                map.remove(key);
        }
}
