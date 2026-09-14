package org.mtr.mod.packet;

import org.junit.jupiter.api.Test;
import org.mtr.core.data.ClientData;
import org.mtr.core.data.Lift;
import org.mtr.core.serializer.JsonReader;
import org.mtr.core.tool.Utilities;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArraySet;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PacketUpdateVehiclesLiftsTest {
	@Test
	void onlyUpdatedItemsAreCreatedAndOnlyMissingItemsAreDisposed() {
		final ClientData data = new ClientData();
		final Lift kept = new Lift(data);
		final Lift changed = new Lift(data);
		final Lift removed = new Lift(data);
		final Lift replacement = new Lift(new JsonReader(Utilities.getJsonObjectFromData(changed)), data);
		final ObjectArraySet<Lift> items = new ObjectArraySet<>();
		items.add(kept); items.add(changed); items.add(removed);
		final ObjectArrayList<Lift> disposed = new ObjectArrayList<>();
		final AtomicInteger created = new AtomicInteger();
		assertTrue(PacketUpdateVehiclesLifts.updateVehiclesOrLifts(items, consumer -> consumer.accept(kept.getId()), consumer -> consumer.accept(replacement), disposed::add, Lift::getId, lift -> {
			created.incrementAndGet();
			return lift;
		}));
		assertEquals(1, created.get());
		assertEquals(1, disposed.size());
		assertSame(removed, disposed.get(0));
		assertEquals(2, items.size());
		assertTrue(items.stream().anyMatch(lift -> lift == kept));
		assertTrue(items.stream().anyMatch(lift -> lift == replacement));
		assertFalse(PacketUpdateVehiclesLifts.updateVehiclesOrLifts(items, consumer -> items.forEach(lift -> consumer.accept(lift.getId())), consumer -> {}, disposed::add, Lift::getId, lift -> lift));
	}
}
