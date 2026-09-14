package org.mtr.mod.data;

import org.junit.jupiter.api.Test;
import org.mtr.core.operation.ArrivalResponse;
import org.mtr.core.serializer.JsonReader;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongAVLTreeSet;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectList;

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ArrivalsCacheTest {

	@Test
	void selectedArrivalsKeepResponseOrderAcrossInterleavedPlatforms() {
		final TestArrivalsCache cache = new TestArrivalsCache();
		cache.respond(new ObjectArrayList<>(new ArrivalResponse[]{arrival(1, 10), arrival(2, 20), arrival(1, 30), arrival(2, 40), arrival(3, 50)}));

		final ObjectArrayList<ArrivalResponse> arrivals = cache.requestArrivals(LongArrayList.of(2, 1, 2));

		assertEquals(LongArrayList.of(10, 20, 30, 40), departureIndexes(arrivals));
	}

	@Test
	void cachedSelectionsAreReturnedAsMutableCallerOwnedLists() {
		final TestArrivalsCache cache = new TestArrivalsCache();
		cache.respond(new ObjectArrayList<>(new ArrivalResponse[]{arrival(1, 10), arrival(1, 20)}));

		final ObjectArrayList<ArrivalResponse> firstResult = cache.requestArrivals(LongArrayList.of(1));
		firstResult.clear();

		final ObjectArrayList<ArrivalResponse> secondResult = cache.requestArrivals(LongArrayList.of(1));
		assertEquals(LongArrayList.of(10, 20), departureIndexes(secondResult));
	}

	@Test
	void aNewResponseInvalidatesPreviouslyDerivedSelections() {
		final TestArrivalsCache cache = new TestArrivalsCache();
		cache.respond(new ObjectArrayList<>(new ArrivalResponse[]{arrival(1, 10), arrival(2, 20)}));
		assertEquals(LongArrayList.of(10, 20), departureIndexes(cache.requestArrivals(LongArrayList.of(1, 2))));

		cache.respond(new ObjectArrayList<>(new ArrivalResponse[]{arrival(2, 200), arrival(1, 100)}));

		assertEquals(LongArrayList.of(200, 100), departureIndexes(cache.requestArrivals(LongArrayList.of(1, 2))));
	}

	private static ArrivalResponse arrival(long platformId, long departureIndex) {
		return new ArrivalResponse(JsonReader.parse(String.format("{\"departureIndex\":%d,\"circularState\":\"NONE\",\"platformId\":%d}", departureIndex, platformId)));
	}

	private static LongArrayList departureIndexes(ObjectArrayList<ArrivalResponse> arrivals) {
		final LongArrayList departureIndexes = new LongArrayList();
		arrivals.forEach(arrival -> departureIndexes.add(arrival.getDepartureIndex()));
		return departureIndexes;
	}

	private static final class TestArrivalsCache extends ArrivalsCache {

		private Consumer<ObjectList<ArrivalResponse>> callback;

		private TestArrivalsCache() {
			super(0);
		}

		@Override
		public long getMillisOffset() {
			return 0;
		}

		@Override
		protected void requestArrivalsFromServer(LongAVLTreeSet platformIds, Consumer<ObjectList<ArrivalResponse>> callback) {
			this.callback = callback;
		}

		private void respond(ObjectList<ArrivalResponse> arrivals) {
			requestArrivals(LongArrayList.of(1));
			try {
				Thread.sleep(110);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new AssertionError(e);
			}
			tick();
			callback.accept(arrivals);
		}
	}
}
