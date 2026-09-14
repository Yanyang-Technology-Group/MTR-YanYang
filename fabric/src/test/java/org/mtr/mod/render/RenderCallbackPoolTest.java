package org.mtr.mod.render;

import org.junit.jupiter.api.Test;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RenderCallbackPoolTest {

	@Test
	void releasedListsKeepTheirStorageButReleaseAllCallbacks() throws Exception {
		final Object pool = pool();
		final ObjectArrayList<Object> list = acquire(pool);
		for (int i = 0; i < 20000; i++) list.add(new Object());
		final Object[] storage = list.elements();
		recycle(pool, list);
		assertTrue(list.isEmpty());
		for (Object value : storage) assertNull(value);
		assertSame(list, acquire(pool));
		assertSame(storage, list.elements());
		assertNotSame(list, acquire(pool));
	}

	@Test
	void largeAndNumerousTransientMaterialsCannotGrowThePoolWithoutBound() throws Exception {
		final Object pool = pool();
		final ObjectArrayList<Object> huge = new ObjectArrayList<>(100000);
		huge.add(new Object());
		recycle(pool, huge);
		assertNotSame(huge, acquire(pool));
		final List<ObjectArrayList<Object>> lists = new ArrayList<>();
		for (int i = 0; i < 300; i++) lists.add(new ObjectArrayList<>(4096));
		for (ObjectArrayList<Object> list : lists) recycle(pool, list);
		long retained = 0;
		for (int i = 0; i < 300; i++) retained += acquire(pool).elements().length;
		assertTrue(retained <= 262144, "pool retained " + retained + " element slots");
	}

	private static Object pool() throws Exception {
		return Class.forName("org.mtr.mod.render.RenderCallbackPool").getDeclaredConstructor().newInstance();
	}

	@SuppressWarnings("unchecked")
	private static ObjectArrayList<Object> acquire(Object pool) throws Exception {
		return (ObjectArrayList<Object>) pool.getClass().getDeclaredMethod("acquire").invoke(pool);
	}

	private static void recycle(Object pool, ObjectArrayList<Object> list) throws Exception {
		final Method method = pool.getClass().getDeclaredMethod("recycle", ObjectArrayList.class);
		method.invoke(pool, list);
	}
}
