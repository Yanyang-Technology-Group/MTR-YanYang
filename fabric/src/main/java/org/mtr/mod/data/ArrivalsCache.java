package org.mtr.mod.data;

import org.mtr.core.operation.ArrivalResponse;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.Long2IntAVLTreeMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.Long2ObjectAVLTreeMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongAVLTreeSet;
import org.mtr.libraries.it.unimi.dsi.fastutil.ints.IntArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongCollection;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectList;

import java.util.Arrays;
import java.util.function.Consumer;

public abstract class ArrivalsCache {

	private long nextRequest;
	private final Long2IntAVLTreeMap queuedPlatformIdsWithAge = new Long2IntAVLTreeMap();
	private final ObjectArrayList<ArrivalResponse> arrivalResponseCache = new ObjectArrayList<>();
	private final Long2ObjectAVLTreeMap<IntArrayList> responseIndexesByPlatform = new Long2ObjectAVLTreeMap<>();
	private final Object2ObjectLinkedOpenHashMap<PlatformSelection, ObjectArrayList<ArrivalResponse>> selectionCache = new Object2ObjectLinkedOpenHashMap<>();
	private final int cachedMillis;
	private static final int PERSISTENT_AGE = 5;
	private static final int MAX_SELECTION_CACHE_ENTRIES = 128;
	private static final int MAX_SELECTION_CACHE_PLATFORMS = 64;

	protected ArrivalsCache(int cachedMillis) {
		this.cachedMillis = cachedMillis;
	}

	public final ObjectArrayList<ArrivalResponse> requestArrivals(LongCollection platformIds) {
		if (queuedPlatformIdsWithAge.isEmpty() && canSendRequest()) {
			nextRequest = System.currentTimeMillis() + 100;
		}

		platformIds.forEach(platformId -> queuedPlatformIdsWithAge.put(platformId, 0));

		final PlatformSelection selection = PlatformSelection.from(platformIds);
		final ObjectArrayList<ArrivalResponse> cachedSelection = selectionCache.getAndMoveToFirst(selection);
		if (cachedSelection != null) {
			return new ObjectArrayList<>(cachedSelection);
		}

		final IntArrayList responseIndexes = new IntArrayList();
		for (final long platformId : selection.platformIds) {
			final IntArrayList platformIndexes = responseIndexesByPlatform.get(platformId);
			if (platformIndexes != null) {
				platformIndexes.forEach(responseIndexes::add);
			}
		}
		responseIndexes.sort((first, second) -> Integer.compare(first, second));

		final ObjectArrayList<ArrivalResponse> arrivals = new ObjectArrayList<>(responseIndexes.size());
		responseIndexes.forEach(responseIndex -> arrivals.add(arrivalResponseCache.get(responseIndex)));
		if (selection.platformIds.length <= MAX_SELECTION_CACHE_PLATFORMS) {
			if (selectionCache.size() >= MAX_SELECTION_CACHE_ENTRIES) {
				selectionCache.removeLast();
			}
			selectionCache.putAndMoveToFirst(selection, arrivals);
		}
		return new ObjectArrayList<>(arrivals);
	}

	public final void tick() {
		if (!queuedPlatformIdsWithAge.isEmpty() && canSendRequest()) {
			final LongAVLTreeSet platformIds = new LongAVLTreeSet(queuedPlatformIdsWithAge.keySet());

			requestArrivalsFromServer(platformIds, arrivalResponseList -> {
				arrivalResponseCache.clear();
				arrivalResponseCache.addAll(arrivalResponseList);
				responseIndexesByPlatform.clear();
				selectionCache.clear();
				for (int index = 0; index < arrivalResponseCache.size(); index++) {
					final ArrivalResponse arrivalResponse = arrivalResponseCache.get(index);
					responseIndexesByPlatform.computeIfAbsent(arrivalResponse.getPlatformId(), ignored -> new IntArrayList()).add(index);
				}
			});

			platformIds.forEach(platformId -> queuedPlatformIdsWithAge.compute(platformId, (key, age) -> age > PERSISTENT_AGE ? null : age + 1));
			nextRequest = System.currentTimeMillis() + cachedMillis;
		}
	}

	private boolean canSendRequest() {
		return System.currentTimeMillis() >= nextRequest;
	}

	public abstract long getMillisOffset();

	protected abstract void requestArrivalsFromServer(LongAVLTreeSet platformIds, Consumer<ObjectList<ArrivalResponse>> callback);

	private static final class PlatformSelection {

		private final long[] platformIds;

		private PlatformSelection(long[] platformIds) {
			this.platformIds = platformIds;
		}

		private static PlatformSelection from(LongCollection platformIds) {
			final long[] sortedPlatformIds = platformIds.toLongArray();
			Arrays.sort(sortedPlatformIds);
			int size = 0;
			for (final long platformId : sortedPlatformIds) {
				if (size == 0 || sortedPlatformIds[size - 1] != platformId) {
					sortedPlatformIds[size++] = platformId;
				}
			}
			return new PlatformSelection(size == sortedPlatformIds.length ? sortedPlatformIds : Arrays.copyOf(sortedPlatformIds, size));
		}

		@Override
		public boolean equals(Object object) {
			return object instanceof PlatformSelection && Arrays.equals(platformIds, ((PlatformSelection) object).platformIds);
		}

		@Override
		public int hashCode() {
			return Arrays.hashCode(platformIds);
		}
	}
}
