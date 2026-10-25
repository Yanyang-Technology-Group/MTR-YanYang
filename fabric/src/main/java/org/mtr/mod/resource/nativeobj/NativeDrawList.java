package org.mtr.mod.resource.nativeobj;

import org.mtr.mod.Init;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The native draw list: static geometry registered once, draws queued as integers, and a
 * single flush per frame that emits only the GL calls whose value actually changed.
 *
 * <p>This is the counterpart of MTR's {@code BatchManager}, and it replaces the part of the
 * draw path that was still per-array Java work: a {@code HashMap} keyed on boxed material
 * properties, an {@code ArrayList} of {@code RenderCall} objects allocated every frame, and
 * a {@code VertexAttributeState.apply()} per vertex array. The native side keeps a flat
 * registry, the queue is a preallocated array, and the attribute cache suppresses writes
 * that would repeat the previous value.</p>
 *
 * <h2>Lifetime</h2>
 *
 * <p>A draw list owns one native allocation and one global reference to the
 * {@link NativeGlHost}. Both are released by {@link #close()}, which is idempotent. The
 * renderer that owns this list lives for as long as the client does, so in practice the
 * release happens at shutdown; getting it wrong would pin one object per list.</p>
 *
 * <h2>Failure</h2>
 *
 * <p>Every entry point is inert when the native library is unavailable, and
 * {@link #isAvailable()} reports which. The caller checks it once and keeps using MTR's own
 * batch manager when it is false, so a missing library costs nothing but the check.</p>
 */
public final class NativeDrawList implements AutoCloseable {

	/** Size of the statistics array the bridge fills. */
	private static final int STAT_COUNT = 8;

	/** Index into the statistics array, matching the native fill order. */
	private static final int STAT_FLUSHES = 0;
	private static final int STAT_ARRAYS_DRAWN = 1;
	private static final int STAT_CALLS_SKIPPED = 2;
	private static final int STAT_CALLS_ISSUED = 3;
	private static final int STAT_DRAW_CALLS = 4;
	private static final int STAT_LIVE_BATCHES = 5;
	private static final int STAT_QUEUED = 6;

	/** Reused for the statistics array, so reporting does not allocate. */
	private final long[] statsBuffer = new long[STAT_COUNT];

	/**
	 * A 16-float direct buffer holding one pose matrix per queue slot.
	 *
	 * <p>Room for a generous number of entries is allocated once. Passing a direct buffer
	 * rather than a {@code float[]} is what avoids a Java array allocation per part per
	 * frame, which was itself one of the per-frame costs. When the queue overflows the
	 * buffer, the flush happens early rather than growing: a partial flush is correct
	 * because the globals are the same for the whole pass.</p>
	 */
	private ByteBuffer matrixBuffer;

	/** How many pose matrices fit in {@link #matrixBuffer}. */
	private int matrixCapacity;

	/** How many are staged right now. */
	private int matrixCount;

	private long handle;

	@Nullable
	private final NativeGlHost glHost;

	private boolean closed;

	private NativeDrawList(long handle, @Nullable NativeGlHost glHost, int capacity) {
		this.handle = handle;
		this.glHost = glHost;
		this.matrixCapacity = capacity;
		this.matrixBuffer = ByteBuffer.allocateDirect(capacity * 16 * Float.BYTES).order(ByteOrder.nativeOrder());
	}

	/**
	 * Creates a draw list, or returns null when the native library cannot provide one.
	 *
	 * @param useFloatAttributeFallback true when the driver lacks integer vertex attributes,
	 *                                  which is the gl4ES case MTR already detects
	 */
	@Nullable
	public static NativeDrawList create(boolean useFloatAttributeFallback, int matrixCapacity) {
		if (!NativeObjPipeline.isAvailable()) {
			return null;
		}
		final long handle;
		try {
			handle = nativeCreate();
		} catch (Throwable throwable) {
			Init.LOGGER.warn("[wrgl] could not create a native draw list: {}", throwable.toString());
			return null;
		}
		if (handle == 0L) {
			return null;
		}

		final NativeDrawList list = new NativeDrawList(handle, new NativeGlHost(), Math.max(64, matrixCapacity));
		try {
			if (!nativeSetGlHost(handle, list.glHost, useFloatAttributeFallback)) {
				// The bridge could not resolve the host's methods, which means this class and
				// the native library disagree. Refusing the list is better than emitting
				// nothing while claiming to render.
				Init.LOGGER.error("[wrgl] the native bridge rejected the GL host; "
						+ "the Java batch manager will be used instead");
				list.close();
				return null;
			}
		} catch (Throwable throwable) {
			Init.LOGGER.warn("[wrgl] could not install the GL host: {}", throwable.toString());
			list.close();
			return null;
		}
		return list;
	}

	/** True when the native side has a usable GL table. */
	public boolean isAvailable() {
		if (closed || handle == 0L) {
			return false;
		}
		try {
			return nativeIsReady(handle);
		} catch (Throwable throwable) {
			return false;
		}
	}

	// -----------------------------------------------------------------------
	// Registration
	// -----------------------------------------------------------------------

	/**
	 * Registers a vertex array and returns its handle, or -1 when it cannot be registered.
	 *
	 * <p>Called once per array when a model is built, not per frame.</p>
	 */
	public int registerBatch(int vao, int indexCount, int shaderType) {
		if (closed || handle == 0L || vao == 0 || indexCount <= 0) {
			return -1;
		}
		try {
			return nativeRegisterBatch(handle, vao, indexCount, shaderType);
		} catch (Throwable throwable) {
			Init.LOGGER.warn("[wrgl] could not register a vertex array: {}", throwable.toString());
			return -1;
		}
	}

	/** Marks a handle free. The GL objects themselves belong to the caller. */
	public void unregisterBatch(int batch) {
		if (closed || handle == 0L || batch < 0) {
			return;
		}
		try {
			nativeUnregisterBatch(handle, batch);
		} catch (Throwable throwable) {
			Init.LOGGER.warn("[wrgl] could not unregister a vertex array: {}", throwable.toString());
		}
	}

	// -----------------------------------------------------------------------
	// Per frame
	// -----------------------------------------------------------------------

	/**
	 * Clears the queue and forgets the cached GL state.
	 *
	 * <p>Must be called before the frame's queue calls. The cache invalidation matters
	 * because MTR's {@code GlStateTracker.restore()} puts the previous attribute values back
	 * at the end of every pass, so nothing the cache remembered is still true.</p>
	 */
	public void beginFrame() {
		if (closed || handle == 0L) {
			return;
		}
		matrixCount = 0;
		try {
			nativeBeginFrame(handle);
		} catch (Throwable throwable) {
			Init.LOGGER.warn("[wrgl] could not begin a frame: {}", throwable.toString());
		}
	}

	/**
	 * Queues one draw of a registered array with the given pose matrix.
	 *
	 * <p>The matrix is written into the shared direct buffer at the next free slot, and only
	 * its index crosses the boundary. When the buffer is full the queue is flushed with the
	 * globals from the previous call, which is correct because every queue call in one pass
	 * shares them.</p>
	 *
	 * @param matrix a column-major 4x4 pose, as {@code MatrixStack.Entry.getPositionMatrix()}
	 *               produces
	 */
	public void queue(int batch, float[] matrix) {
		queue(batch, matrix, 0);
	}

	/**
	 * Queues one draw, reading the pose from an offset into a matrix array.
	 *
	 * <p>The offset form lets a caller keep many poses in one array and avoids a copy at the
	 * call site.</p>
	 */
	public void queue(int batch, float[] matrix, int offset) {
		if (closed || handle == 0L || batch < 0) {
			return;
		}
		if (matrixCount >= matrixCapacity) {
			// The caller is responsible for flushing between passes; reaching this means a
			// single pass queued more parts than the buffer holds. Dropping the draw would be
			// a silent hole in the render, so the flush is forced instead and the caller's
			// next globals call re-establishes the state.
			flushStaged();
		}
		if (matrixCount >= matrixCapacity) {
			return;
		}

		final int base = matrixCount * 16;
		matrixBuffer.asFloatBuffer().put(base, matrix, offset, 16);
		matrixCount++;

		try {
			nativeQueue(handle, batch, matrixBuffer, base);
		} catch (Throwable throwable) {
			Init.LOGGER.warn("[wrgl] could not queue a draw: {}", throwable.toString());
		}
	}

	/**
	 * Draws everything queued.
	 *
	 * <p>The globals are applied once for the whole flush rather than once per array, which
	 * is where most of the removed GL traffic comes from. They are uniform by construction:
	 * they come from Minecraft's global render state and MTR passes its queue arguments
	 * straight through from it, so every array queued in one pass carries the same colour,
	 * lightmap and overlay.</p>
	 *
	 * @param color     packed colour, ignored unless {@code hasColor}
	 * @param lightmap  packed lightmap, ignored unless {@code hasLightmap}
	 * @param overlay   packed overlay, ignored unless {@code hasOverlay}
	 */
	public void flush(int color, int lightmap, int overlay,
			boolean hasColor, boolean hasLightmap, boolean hasOverlay) {
		if (closed || handle == 0L) {
			return;
		}
		matrixCount = 0;
		try {
			nativeFlush(handle, color, lightmap, overlay, hasColor, hasLightmap, hasOverlay);
		} catch (Throwable throwable) {
			Init.LOGGER.warn("[wrgl] could not flush the draw list: {}", throwable.toString());
		}
	}

	/** Flushes with no globals, which leaves the attribute slots to whatever set them last. */
	private void flushStaged() {
		flush(0, 0, 0, false, false, false);
	}

	// -----------------------------------------------------------------------
	// Reporting
	// -----------------------------------------------------------------------

	/**
	 * A one-line summary of the work the draw list has done.
	 *
	 * <p>Reports the ratio of skipped to issued GL calls, which is the number this rewrite
	 * exists to move. Nothing else reports it, so it is the only way to see the effect
	 * without a GPU profiler.</p>
	 */
	public String describeStatistics() {
		if (closed || handle == 0L) {
			return "native draw list: not available";
		}
		try {
			nativeStats(handle, statsBuffer);
		} catch (Throwable throwable) {
			return "native draw list: statistics unavailable";
		}

		final long issued = statsBuffer[STAT_CALLS_ISSUED];
		final long skipped = statsBuffer[STAT_CALLS_SKIPPED];
		final long total = issued + skipped;
		final double skippedPercent = total == 0 ? 0.0 : skipped * 100.0 / total;

		return String.format(
				"native draw list: %d flush(es), %d array(s) drawn, %d draw call(s), "
						+ "%d vertex state call(s) issued and %d skipped (%.1f%%), %d batch(es) live",
				statsBuffer[STAT_FLUSHES], statsBuffer[STAT_ARRAYS_DRAWN], statsBuffer[STAT_DRAW_CALLS],
				issued, skipped, skippedPercent, statsBuffer[STAT_LIVE_BATCHES]);
	}

	/** The raw statistics counters, in the order documented here. */
	public long[] rawStatistics() {
		if (closed || handle == 0L) {
			return new long[STAT_COUNT];
		}
		try {
			nativeStats(handle, statsBuffer);
		} catch (Throwable throwable) {
			return new long[STAT_COUNT];
		}
		return statsBuffer.clone();
	}

	/** Zeroes the counters, so a measurement can be scoped to a window. */
	public void resetStatistics() {
		if (closed || handle == 0L) {
			return;
		}
		try {
			nativeResetStats(handle);
		} catch (Throwable throwable) {
			Init.LOGGER.warn("[wrgl] could not reset the draw list counters: {}", throwable.toString());
		}
	}

	/** How many batches are registered, for diagnostics. */
	public int liveBatchCount() {
		if (closed || handle == 0L) {
			return 0;
		}
		try {
			nativeStats(handle, statsBuffer);
			return (int) statsBuffer[STAT_LIVE_BATCHES];
		} catch (Throwable throwable) {
			return 0;
		}
	}

	/** Releases the native list and the global reference it holds. Idempotent. */
	@Override
	public void close() {
		if (closed) {
			return;
		}
		closed = true;
		final long current = handle;
		handle = 0L;
		matrixCount = 0;
		if (current != 0L) {
			try {
				nativeFree(current);
			} catch (Throwable throwable) {
				Init.LOGGER.warn("[wrgl] could not release the native draw list: {}", throwable.toString());
			}
		}
		matrixBuffer = ByteBuffer.allocateDirect(0);
		matrixCapacity = 0;
	}

	// -----------------------------------------------------------------------
	// Native declarations
	// -----------------------------------------------------------------------

	private static native long nativeCreate();

	private static native void nativeFree(long handle);

	private static native boolean nativeSetGlHost(long handle, Object glHost, boolean useFloatAttributeFallback);

	private static native int nativeRegisterBatch(long handle, int vao, int indexCount, int shaderType);

	private static native void nativeUnregisterBatch(long handle, int batch);

	private static native void nativeBeginFrame(long handle);

	private static native void nativeQueue(long handle, int batch, Object matrixBuffer, int matrixOffset);

	private static native void nativeFlush(long handle, int color, int lightmap, int overlay,
			boolean hasColor, boolean hasLightmap, boolean hasOverlay);

	private static native void nativeStats(long handle, long[] out);

	private static native void nativeResetStats(long handle);

	private static native boolean nativeIsReady(long handle);
}
