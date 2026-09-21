package org.mtr.mod.render;

import org.junit.jupiter.api.Test;
import org.joml.Matrix4f;
import org.mtr.mapping.render.tool.Utilities;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class MatrixUploadBufferTest {
	@Test
	void successiveModelMatricesReuseStorageAndOverwriteEveryComponent() {
		final FloatBuffer first = MatrixUploadBuffer.floats(MatrixUploadBuffer.bytes(64));
		Utilities.store(new org.mtr.mapping.holder.Matrix4f(new Matrix4f().translation(1, 2, 3)), first);
		first.position(8);
		final FloatBuffer second = MatrixUploadBuffer.floats(MatrixUploadBuffer.bytes(64));
		assertSame(first, second);
		assertEquals(0, second.position());
		final Matrix4f matrix = new Matrix4f().rotateXYZ(0.5F, 1, 2).translate(-8, 4, 11);
		Utilities.store(new org.mtr.mapping.holder.Matrix4f(matrix), second);
		final FloatBuffer expected = ByteBuffer.allocate(64).asFloatBuffer();
		Utilities.store(new org.mtr.mapping.holder.Matrix4f(matrix), expected);
		for (int i = 0; i < 16; i++) assertEquals(expected.get(i), second.get(i));
	}

	@Test
	void otherThreadsAndUnrelatedBuffersCannotOverwriteTheRenderBuffer() throws Exception {
		final FloatBuffer render = MatrixUploadBuffer.floats(MatrixUploadBuffer.bytes(64));
		render.put(0, 17);
		final AtomicReference<FloatBuffer> worker = new AtomicReference<>();
		final Thread thread = new Thread(() -> {
			worker.set(MatrixUploadBuffer.floats(MatrixUploadBuffer.bytes(64)));
			worker.get().put(0, 25);
		});
		thread.start();
		thread.join();
		assertNotSame(render, worker.get());
		assertEquals(17, render.get(0));
		final ByteBuffer unrelated = MatrixUploadBuffer.bytes(128);
		assertEquals(32, MatrixUploadBuffer.floats(unrelated).capacity());
		final ByteBuffer external = ByteBuffer.allocate(64);
		MatrixUploadBuffer.floats(external).put(0, 31);
		assertEquals(31, external.getFloat(0));
		assertEquals(17, render.get(0));
	}
}
