package org.mtr.mod.resource.nativeobj;

import java.nio.ByteBuffer;

/**
 * The buffers and draw records one native model build produced.
 *
 * <p>The two buffers are <em>direct</em> ByteBuffers pointing straight at the native
 * allocation, not copies. That is the point of the rewrite: the reference pipeline builds
 * an interleaved vertex buffer on the Java heap and hands it to {@code glBufferData}, which
 * means the whole model is copied twice and then becomes garbage. Here the buffer is handed
 * to {@code GL33.nglBufferData} by address and never enters the heap.</p>
 *
 * <p>The consequence is that this object owns native memory and must be released with
 * {@link #release()} once the GL upload has happened. {@link NativeObjModelBuilder} does that
 * in a finally block, so callers should not normally need to.</p>
 */
public final class NativeObjResult {

	/** Interleaved vertices, {@code vertexCount * 24} bytes. Read-only, native owned. */
	public final ByteBuffer vertexBuffer;

	/** Unsigned 32-bit triangle indices. Read-only, native owned. */
	public final ByteBuffer indexBuffer;

	/** One entry per material bucket, in accumulated draw order. */
	public final NativeObjDraw[] draws;

	/** Native pointer to the allocation backing the two buffers, for {@code nativeFreeResult}. */
	final long nativeHandle;

	/**
	 * How many bytes of Java heap the reference pipeline would have allocated to reach these
	 * buffers. Reported rather than asserted: it is an estimate, and it exists so the saving
	 * can be logged alongside the timings.
	 */
	public final long estimatedJavaHeapBytes;

	private boolean released;

	NativeObjResult(ByteBuffer vertexBuffer, ByteBuffer indexBuffer, NativeObjDraw[] draws,
			long nativeHandle, long estimatedJavaHeapBytes) {
		this.vertexBuffer = vertexBuffer;
		this.indexBuffer = indexBuffer;
		this.draws = draws;
		this.nativeHandle = nativeHandle;
		this.estimatedJavaHeapBytes = estimatedJavaHeapBytes;
	}

	/** Total vertex count across every bucket. */
	public int totalVertexCount() {
		int total = 0;
		for (final NativeObjDraw draw : draws) {
			total += draw.vertexCount;
		}
		return total;
	}

	/** Total triangle count across every bucket. */
	public int totalFaceCount() {
		int total = 0;
		for (final NativeObjDraw draw : draws) {
			total += draw.indexCount / 3;
		}
		return total;
	}

	/**
	 * Frees the native allocation. Idempotent, so a double release cannot corrupt the heap.
	 */
	public void release() {
		if (released) {
			return;
		}
		released = true;
		NativeObjPipeline.freeResult(nativeHandle);
	}
	@Override
	public String toString() {
		return String.format("NativeObjResult[%d bucket(s), %d vertices, %d triangles, %d KiB]",
				draws.length, totalVertexCount(), totalFaceCount(),
				(vertexBuffer == null ? 0 : vertexBuffer.capacity()) / 1024);
	}
}
