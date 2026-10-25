package org.mtr.mod.client;

import org.junit.jupiter.api.Test;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.data.TransportMode;
import org.mtr.core.tool.Angle;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectCollection;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import static org.junit.jupiter.api.Assertions.*;

class MinecraftClientDataBinaryCompatibilityTest {

	@Test
	void legacyAddonBytecodeReadsLiveRailWrapperValues() throws Throwable {
		final MinecraftClientData data = new MinecraftClientData();
		final Rail rail = Rail.newPlatformRail(new Position(0, 0, 0), Angle.E, new Position(40, 0, 0), Angle.E, Rail.Shape.QUADRATIC, 0, new ObjectArrayList<>(), TransportMode.TRAIN);
		data.rails.add(rail);
		data.sync();
		final ObjectCollection<?> values = legacyValues(data);
		final MinecraftClientData.RailWrapper wrapper = data.railWrapperList.get(rail.getHexId());
		assertEquals(1, values.size());
		assertSame(wrapper, values.iterator().next());
		assertSame(rail, wrapper.getRail());
		data.rails.clear();
		data.sync();
		assertTrue(values.isEmpty());
		final Rail replacement = Rail.copy(rail, Rail.Shape.CABLE, 10);
		data.rails.add(replacement);
		data.sync();
		final MinecraftClientData.RailWrapper replacementWrapper = data.railWrapperList.get(replacement.getHexId());
		assertNotSame(wrapper, replacementWrapper);
		assertSame(replacementWrapper, values.iterator().next());
		assertSame(replacement, replacementWrapper.getRail());
		assertTrue(values.remove(replacementWrapper));
		assertFalse(data.railWrapperList.containsKey(replacement.getHexId()));
		data.sync();
		assertSame(data.railWrapperList.get(replacement.getHexId()), values.iterator().next());
	}

	private static ObjectCollection<?> legacyValues(MinecraftClientData data) throws Throwable {
		final String owner = "org/mtr/mod/client/MinecraftClientData";
		final String legacyMap = "org/mtr/libraries/it/unimi/dsi/fastutil/objects/Object2ObjectArrayMap";
		final String collection = "org/mtr/libraries/it/unimi/dsi/fastutil/objects/ObjectCollection";
		final String accessor = "org/mtr/mod/client/LegacyRailWrapperReader";
		final ClassWriter writer = new ClassWriter(0);
		writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, accessor, null, "java/lang/Object", null);
		final MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "values", "(L" + owner + ";)L" + collection + ";", null, null);
		method.visitCode();
		method.visitVarInsn(Opcodes.ALOAD, 0);
		method.visitFieldInsn(Opcodes.GETFIELD, owner, "railWrapperList", "L" + legacyMap + ";");
		method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, legacyMap, "values", "()L" + collection + ";", false);
		method.visitInsn(Opcodes.ARETURN);
		method.visitMaxs(1, 1);
		method.visitEnd();
		writer.visitEnd();
		final Class<?> reader = new LegacyAddonClassLoader().define(accessor.replace('/', '.'), writer.toByteArray());
		return (ObjectCollection<?>) MethodHandles.publicLookup().findStatic(reader, "values", MethodType.methodType(ObjectCollection.class, MinecraftClientData.class)).invokeExact(data);
	}

	private static final class LegacyAddonClassLoader extends ClassLoader {

		private LegacyAddonClassLoader() {
			super(MinecraftClientData.class.getClassLoader());
		}

		private Class<?> define(String name, byte[] bytecode) {
			return defineClass(name, bytecode, 0, bytecode.length);
		}
	}
}
