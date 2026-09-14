package org.mtr.mod.resource;

import org.junit.jupiter.api.Test;

import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class CachedResourceTest {
	@Test
	void expiryOnlyVisitsLoadedResourcesAndDropsExpiredOnes() {
		final AtomicInteger clockReads = new AtomicInteger();
		final AtomicLong clock = new AtomicLong(100);
		final java.util.List<CachedResource<Object>> resources = new java.util.ArrayList<>();
		for (int i = 0; i < 10000; i++) {
			resources.add(new CachedResource<>(Object::new, 10, () -> {
				clockReads.incrementAndGet();
				return clock.get();
			}));
		}
		CachedResource.tick();
		assertEquals(0, clockReads.get(), "Unloaded resource definitions need no expiry work");
		resources.get(0).getData(true);
		clockReads.set(0);
		CachedResource.tick();
		assertEquals(1, clockReads.get());
		clock.set(111);
		CachedResource.tick();
		clockReads.set(0);
		CachedResource.tick();
		assertEquals(0, clockReads.get(), "Expired definitions leave the active set");
		assertEquals(10000, resources.size());
	}
	@Test
	void cacheHitsRenewAfterAnotherResourceUsesTheLoadBudget() {
		final AtomicLong clock = new AtomicLong(100);
		final AtomicInteger loads = new AtomicInteger();
		final CachedResource<Object> resource = new CachedResource<>(() -> {
			loads.incrementAndGet();
			return new Object();
		}, 10, clock::get);
		final Object original = resource.getData(true);
		clock.set(108);
		CachedResource.tick();
		new CachedResource<>(Object::new, 10, clock::get).getData(false);
		assertSame(original, resource.getData(false));
		clock.set(112);
		CachedResource.tick();
		assertSame(original, resource.getData(false));
		assertEquals(1, loads.get());
	}

	@Test
	void expiryReleasesDataAndOnlyOneTopLevelMissLoadsPerTick() {
		final AtomicLong clock = new AtomicLong(100);
		final CachedResource<Object> first = new CachedResource<>(Object::new, 10, clock::get);
		final CachedResource<Object> second = new CachedResource<>(Object::new, 10, clock::get);
		CachedResource.tick();
		final Object original = first.getData(false);
		assertNotNull(original);
		assertNull(second.getData(false));
		clock.set(111);
		CachedResource.tick();
		assertNotSame(original, first.getData(false));
		assertNotNull(second.getData(true));
	}

	@Test
	void nestedSuppliersCanFinishInTheSameTick() {
		final AtomicLong clock = new AtomicLong(100);
		final CachedResource<Object> child = new CachedResource<>(Object::new, 10, clock::get);
		final CachedResource<Object> parent = new CachedResource<>(() -> child.getData(false), 10, clock::get);
		CachedResource.tick();
		assertNotNull(parent.getData(false));
		assertSame(parent.getData(false), child.getData(false));
	}

	@Test
	void registryDoesNotRetainAbandonedCachesOrTheirSuppliers() throws InterruptedException {
		final WeakReference<?>[] references = abandonedCache();
		for (int i = 0; i < 100 && (references[0].get() != null || references[1].get() != null); i++) {
			System.gc();
			Thread.sleep(10);
		}
		assertNull(references[0].get(), "The registry must not own the cache");
		assertNull(references[1].get(), "Captured model data must be collectible with its cache");
		CachedResource.tick();
	}

	private static WeakReference<?>[] abandonedCache() {
		final Object capturedModel = new Object();
		final CachedResource<Object> resource = new CachedResource<>(() -> capturedModel, 1000);
		resource.getData(true);
		return new WeakReference<?>[]{new WeakReference<>(resource), new WeakReference<>(capturedModel)};
	}
}
