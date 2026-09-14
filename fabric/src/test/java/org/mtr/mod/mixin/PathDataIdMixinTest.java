package org.mtr.mod.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.junit.jupiter.api.Test;
import org.mtr.core.data.Position;
import org.mtr.core.data.TwoPositionsBase;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class PathDataIdMixinTest {
	@Test
	void computesEachDirectionOnceAndKeepsDifferentPathsIndependent() throws Exception {
		final Class<?> type = Class.forName("org.mtr.mod.mixin.PathDataIdMixin");
		final Object first = type.getDeclaredConstructor().newInstance();
		final Object second = type.getDeclaredConstructor().newInstance();
		final Method get = type.getDeclaredMethod("mtr$cachedHexId", Position.class, Position.class, Operation.class, boolean.class);
		get.setAccessible(true);
		final Position from = new Position(Long.MIN_VALUE, -1, 30000000), to = new Position(Long.MAX_VALUE, 0, -30000000);
		final String forward = TwoPositionsBase.getHexIdRaw(from, to), reverse = TwoPositionsBase.getHexIdRaw(to, from);
		final AtomicInteger calls = new AtomicInteger();
		final Operation<String> original = args -> {
			calls.incrementAndGet();
			return TwoPositionsBase.getHexIdRaw((Position) args[0], (Position) args[1]);
		};
		for (int i = 0; i < 100; i++) {
			assertEquals(forward, get.invoke(first, from, to, original, false));
			assertEquals(reverse, get.invoke(first, to, from, original, true));
		}
		assertEquals(2, calls.get());
		assertSame(get.invoke(first, from, to, original, false), get.invoke(first, from, to, original, false));
		assertEquals(reverse, get.invoke(second, to, from, original, false));
		assertEquals(3, calls.get());
	}
}
