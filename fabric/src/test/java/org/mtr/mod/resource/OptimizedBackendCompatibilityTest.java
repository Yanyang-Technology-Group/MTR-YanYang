package org.mtr.mod.resource;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Paths;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.*;

class OptimizedBackendCompatibilityTest {
	@Test
	void mixinTargetsMatchBothBundledFabricBackends() throws IOException {
		for (final String version : new String[]{"1.20.1", "1.20.4"}) {
			try (final JarFile jar = new JarFile(Paths.get("../libs/Minecraft-Mappings-fabric-" + version + "-0.0.1-dev.jar").toFile())) {
				final ClassNode shaders = readClass(jar, "org/mtr/mapping/render/shader/ShaderManager");
				final ClassNode batches = readClass(jar, "org/mtr/mapping/render/batch/BatchManager");
				assertNotNull(shaders);
				assertNotNull(batches);
				assertTrue(shaders.fields.stream().anyMatch(field -> field.name.equals("shaders") && field.desc.equals("Ljava/util/Map;")));
				assertTrue(shaders.methods.stream().anyMatch(method -> method.name.equals("reloadShaders") && method.desc.equals("()V")));
				assertTrue(batches.fields.stream().anyMatch(field -> field.name.equals("translucentBatches") && field.desc.equals("Ljava/util/Map;")));
				assertTrue(batches.methods.stream().anyMatch(method -> method.name.equals("drawAll") && method.desc.equals("(Lorg/mtr/mapping/render/shader/ShaderManager;Z)V")));
				final MethodNode queue = method(batches, "queue", "(Lorg/mtr/mapping/render/object/VertexArray;Lorg/mtr/mapping/render/vertex/VertexAttributeState;)V");
				assertEquals(1, mapCalls(queue, "computeIfAbsent", "(Ljava/lang/Object;Ljava/util/function/Function;)Ljava/lang/Object;"), version);
				final MethodNode drawBatch = method(batches, "drawBatch", "(Ljava/util/Map;Lorg/mtr/mapping/render/shader/ShaderManager;)V");
				assertNotEquals(0, drawBatch.access & Opcodes.ACC_STATIC, version);
				assertEquals(1, mapCalls(drawBatch, "clear", "()V"), version);
			}
		}
	}

	private static MethodNode method(ClassNode node, String name, String descriptor) {
		return node.methods.stream().filter(method -> method.name.equals(name) && method.desc.equals(descriptor)).findFirst().orElseThrow(() -> new AssertionError("Missing " + name + descriptor));
	}

	private static int mapCalls(MethodNode method, String name, String descriptor) {
		int count = 0;
		for (final AbstractInsnNode instruction : method.instructions) {
			if (instruction instanceof MethodInsnNode) {
				final MethodInsnNode call = (MethodInsnNode) instruction;
				if (call.owner.equals("java/util/Map") && call.name.equals(name) && call.desc.equals(descriptor)) count++;
			}
		}
		return count;
	}

	private static ClassNode readClass(JarFile jar, String name) throws IOException {
		final JarEntry entry = jar.getJarEntry(name + ".class");
		if (entry == null) {
			return null;
		}
		try (final InputStream input = jar.getInputStream(entry)) {
			final ClassNode node = new ClassNode();
			new ClassReader(input).accept(node, 0);
			return node;
		}
	}
}
