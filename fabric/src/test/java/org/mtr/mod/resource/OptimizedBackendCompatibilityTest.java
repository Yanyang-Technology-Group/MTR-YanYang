package org.mtr.mod.resource;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Paths;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.*;

class OptimizedBackendCompatibilityTest {
	@Test
	void mixinTargetsMatchTheBundledFabric1201Backend() throws IOException {
		try (final JarFile jar = new JarFile(Paths.get("../libs/Minecraft-Mappings-fabric-1.20.1-0.0.1-dev.jar").toFile())) {
			final ClassNode shaders = readClass(jar, "org/mtr/mapping/render/shader/ShaderManager");
			final ClassNode batches = readClass(jar, "org/mtr/mapping/render/batch/BatchManager");
			assertNotNull(shaders);
			assertNotNull(batches);
			assertTrue(shaders.fields.stream().anyMatch(field -> field.name.equals("shaders") && field.desc.equals("Ljava/util/Map;")));
			assertTrue(shaders.methods.stream().anyMatch(method -> method.name.equals("reloadShaders") && method.desc.equals("()V")));
			assertTrue(batches.fields.stream().anyMatch(field -> field.name.equals("translucentBatches") && field.desc.equals("Ljava/util/Map;")));
			assertTrue(batches.methods.stream().anyMatch(method -> method.name.equals("drawAll") && method.desc.equals("(Lorg/mtr/mapping/render/shader/ShaderManager;Z)V")));
		}
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
