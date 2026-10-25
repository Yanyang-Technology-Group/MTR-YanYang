package org.mtr.mod.resource.nativeobj;

import org.junit.jupiter.api.Test;
import org.mtr.mapping.render.vertex.VertexAttributeMapping;
import org.mtr.mapping.render.vertex.VertexAttributeSource;
import org.mtr.mapping.render.vertex.VertexAttributeType;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the interleaved vertex layout the native pipeline packs against the layout the
 * Java backend declares.
 *
 * <h2>The hazard</h2>
 *
 * <p>{@code OptimizedModel.DEFAULT_MAPPING} is private, so {@link NativeObjModelBuilder}
 * rebuilds the same {@code VertexAttributeMapping} from the same declarations. That is safe
 * only while the two agree: if the mapping's source choices ever change, the native buffer
 * would keep its own order and the GPU would read the wrong bytes - position interpreted as
 * UV, a normal read out of the padding - which renders as garbage rather than as an error.</p>
 *
 * <h2>How the reference layout is obtained</h2>
 *
 * <p>{@code VertexAttributeMapping}'s stride arithmetic needs a GL context, but the layout is
 * fully determined by two things that do not: the order of
 * {@link VertexAttributeType}'s constants, and which of them
 * {@code OptimizedModel.DEFAULT_MAPPING} sources from the vertex buffer. The second is read
 * out of the class file with ASM, so this test fails if the mapping changes rather than
 * silently disagreeing with the native packer.</p>
 */
class NativeVertexLayoutCompatibilityTest {

	/** Must match {@code WROBJ_VERTEX_STRIDE} in native/include/wrobj/wrobj.hpp. */
	private static final int NATIVE_VERTEX_STRIDE = 24;

	/** Must match the field order {@code packVertices} writes in native/src/wrobj.cpp. */
	private static final VertexAttributeType[] NATIVE_FIELD_ORDER = {
			VertexAttributeType.POSITION,
			VertexAttributeType.UV_TEXTURE,
			VertexAttributeType.NORMAL
	};

	@Test
	void nativeStrideMatchesTheJavaMapping() throws IOException {
		final Map<VertexAttributeType, VertexAttributeSource> sources = readDefaultMappingSources();

		assertEquals(VertexAttributeSource.VERTEX_BUFFER, sources.get(VertexAttributeType.POSITION),
				"POSITION must come from the vertex buffer, or the native buffer is meaningless");
		assertEquals(VertexAttributeSource.VERTEX_BUFFER, sources.get(VertexAttributeType.UV_TEXTURE),
				"UV_TEXTURE must come from the vertex buffer");
		assertEquals(VertexAttributeSource.VERTEX_BUFFER, sources.get(VertexAttributeType.NORMAL),
				"the normal is packed as three bytes per vertex and must be a vertex buffer attribute");

		// Everything else is a GLOBAL attribute in this mapping, so it is not stored per
		// vertex at all. If one of them ever moves into the vertex buffer the native stride
		// changes and the packer has to follow.
		for (final VertexAttributeType type : VertexAttributeType.values()) {
			if (type == VertexAttributeType.POSITION
					|| type == VertexAttributeType.UV_TEXTURE
					|| type == VertexAttributeType.NORMAL) {
				continue;
			}
			assertEquals(VertexAttributeSource.GLOBAL, sources.get(type),
					type.name() + " must stay a global attribute");
		}

		assertEquals(NATIVE_VERTEX_STRIDE, computeStride(sources),
				"the Java mapping's stride must equal the native vertex stride");

		int offset = 0;
		for (final VertexAttributeType type : NATIVE_FIELD_ORDER) {
			assertEquals(offset, computeOffset(sources, type),
					type.name() + " sits at a different offset than the native packer writes");
			offset += type.byteSize;
		}
	}

	/**
	 * The stride {@code VertexAttributeMapping} would derive, computed the same way its
	 * constructor does: walk the constant order, sum the byte sizes of the vertex buffer
	 * attributes, then round up to an even number.
	 */
	private static int computeStride(Map<VertexAttributeType, VertexAttributeSource> sources) {
		int stride = 0;
		for (final VertexAttributeType type : VertexAttributeType.values()) {
			if (sources.get(type) == VertexAttributeSource.VERTEX_BUFFER) {
				stride += type.byteSize;
			}
		}
		return stride % 2 != 0 ? stride + 1 : stride;
	}

	/** The offset {@code VertexAttributeMapping} would record for one attribute. */
	private static int computeOffset(Map<VertexAttributeType, VertexAttributeSource> sources, VertexAttributeType wanted) {
		int offset = 0;
		for (final VertexAttributeType type : VertexAttributeType.values()) {
			if (sources.get(type) != VertexAttributeSource.VERTEX_BUFFER) {
				continue;
			}
			if (type == wanted) {
				return offset;
			}
			offset += type.byteSize;
		}
		return -1;
	}

	/**
	 * Reads the {@code VertexAttributeSource} values {@code OptimizedModel}'s static
	 * initialiser passes to {@code VertexAttributeMapping.Builder.set}, in order.
	 *
	 * <p>The initialiser is a chain of {@code Builder.set(TYPE, SOURCE)} calls, so the class
	 * file contains pairs of {@code GETSTATIC} instructions: the type constant, then the source
	 * constant. The declared order of the first three pairs is position, colour, UV; that order
	 * is not what matters, only the type-to-source association.</p>
	 */
	private static Map<VertexAttributeType, VertexAttributeSource> readDefaultMappingSources() throws IOException {
		final ClassNode node = readClass(
				Paths.get("../libs/Minecraft-Mappings-fabric-1.20.1-0.0.1-dev.jar"),
				"org/mtr/mapping/mapper/OptimizedModel");
		assertNotNull(node, "OptimizedModel should be present in the bundled backend jar");

		final MethodNode initialiser = node.methods.stream()
				.filter(method -> method.name.equals("<clinit>"))
				.findFirst()
				.orElse(null);
		assertNotNull(initialiser, "OptimizedModel should have a static initialiser");

		// Walk the instruction list collecting GETSTATIC pairs, then keep the ones that name a
		// VertexAttributeType followed by a VertexAttributeSource.
		final Map<VertexAttributeType, VertexAttributeSource> sources =
				new EnumMap<>(VertexAttributeType.class);
		final List<String> pendingConstants = new ArrayList<>();

		for (int i = 0; i < initialiser.instructions.size(); i++) {
			if (!(initialiser.instructions.get(i) instanceof FieldInsnNode)) {
				continue;
			}
			final FieldInsnNode field = (FieldInsnNode) initialiser.instructions.get(i);
			if (field.getOpcode() != org.objectweb.asm.Opcodes.GETSTATIC) {
				continue;
			}
			pendingConstants.add(field.owner + "#" + field.name);
			if (pendingConstants.size() < 2) {
				continue;
			}

			final String typeName = pendingConstants.get(pendingConstants.size() - 2);
			final String sourceName = pendingConstants.get(pendingConstants.size() - 1);
			if (typeName.startsWith("org/mtr/mapping/render/vertex/VertexAttributeType")
					&& sourceName.startsWith("org/mtr/mapping/render/vertex/VertexAttributeSource")) {
				sources.put(
						VertexAttributeType.valueOf(typeName.substring(typeName.indexOf('#') + 1)),
						VertexAttributeSource.valueOf(sourceName.substring(sourceName.indexOf('#') + 1)));
			}
		}

		assertTrue(sources.size() >= 7,
				"expected the default mapping to declare every attribute, found " + sources);
		return sources;
	}

	private static ClassNode readClass(java.nio.file.Path jarPath, String name) throws IOException {
		try (final JarFile jar = new JarFile(jarPath.toFile())) {
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
}
