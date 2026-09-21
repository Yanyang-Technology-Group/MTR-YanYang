package org.mtr.mod.render;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

public final class MatrixUploadBuffer {
	private static final ThreadLocal<MatrixUploadBuffer> LOCAL = ThreadLocal.withInitial(MatrixUploadBuffer::new);
	private final ByteBuffer bytes = ByteBuffer.allocate(64);
	private final FloatBuffer floats = bytes.asFloatBuffer();

	private MatrixUploadBuffer() {
	}

	public static ByteBuffer bytes(int capacity) {
		return capacity == 64 ? LOCAL.get().bytes.clear() : ByteBuffer.allocate(capacity);
	}

	public static FloatBuffer floats(ByteBuffer bytes) {
		final MatrixUploadBuffer buffer = LOCAL.get();
		return bytes == buffer.bytes ? buffer.floats.clear() : bytes.asFloatBuffer();
	}
}
