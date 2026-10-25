package org.mtr.mod.resource.nativeobj;

import org.lwjgl.opengl.GL33;

/**
 * The GL calls the native draw list makes, on behalf of the native side.
 *
 * <p>An instance of this class is handed to the native library once, and the native code
 * calls these methods only when a value actually needs to change. That is the whole point
 * of the arrangement: the reference draw loop sets the same handful of values once per
 * vertex array, and this host is what makes the native side able to say "only when it
 * changed".</p>
 *
 * <h2>Why the calls go through Java at all</h2>
 *
 * <p>A native library that linked OpenGL directly could not be portable here. On Windows
 * {@code opengl32.dll} exports only OpenGL 1.1 and everything newer needs
 * {@code wglGetProcAddress}, which requires a current context; the native library is loaded
 * before any context exists. macOS reaches GL only through a deprecated framework. Android
 * goes through GLES and a translation layer. Routing through the host means the host's
 * existing binding is reused, and the cost is bounded by the cache rather than by the
 * array count.</p>
 *
 * <h2>Why not {@code VertexArray.bind()} and friends</h2>
 *
 * <p>Those go through {@code GlStateTracker.assertProtected()} and object dereferences.
 * This class calls the LWJGL entry points directly, which is legal because the caller
 * brackets the whole pass in the tracker's capture and restore.</p>
 *
 * <h2>Method names are part of the ABI</h2>
 *
 * <p>The native bridge resolves these five methods by name and signature at registration
 * and rejects the host if any is missing, so renaming one is a build failure rather than a
 * silent no-op.</p>
 */
public final class NativeGlHost {

	/**
	 * Sets or creates a global float attribute.
	 *
	 * <p>Signature must stay {@code (IFFFF)V}: the bridge looks it up by that descriptor.</p>
	 */
	public void attrib4f(int location, float x, float y, float z, float w) {
		GL33.glVertexAttrib4f(location, x, y, z, w);
	}

	/**
	 * Sets or creates a global integer attribute.
	 *
	 * <p>Signature must stay {@code (III)V}.</p>
	 */
	public void attribI2i(int location, int x, int y) {
		GL33.glVertexAttribI2i(location, x, y);
	}

	/**
	 * The float fallback for {@link #attribI2i}, used when the driver does not implement
	 * integer vertex attributes.
	 *
	 * <p>This is what MTR does for gl4ES, which is the translation layer Android builds
	 * often use. The decision is made once, from {@code GlStateTracker.isGl4ES()}, and
	 * passed to the native side at registration.</p>
	 *
	 * <p>Signature must stay {@code (IFF)V}.</p>
	 */
	public void attrib2f(int location, float x, float y) {
		GL33.glVertexAttrib2f(location, x, y);
	}

	/** Binds a vertex array. Signature must stay {@code (I)V}. */
	public void bindVertexArray(int vao) {
		GL33.glBindVertexArray(vao);
	}

	/**
	 * Issues the indexed draw call for a triangle list.
	 *
	 * <p>Signature must stay {@code (I)V}. The parameters other than the count are constant
	 * here because every array on this path is a triangle list with 32-bit indices, which is
	 * what {@code IndexBuffer} declares with {@code 5125} and what
	 * {@code VertexArray.draw()} passes: {@code glDrawElements(4, count, 5125, 0)}.</p>
	 */
	public void drawElements(int count) {
		GL33.glDrawElements(GL33.GL_TRIANGLES, count, GL33.GL_UNSIGNED_INT, 0L);
	}
}
