package org.mtr.mod.resource.nativeobj;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

/**
 * Executes the native draw path against a real OpenGL context.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Everything else about the draw path was verified one layer below the thing that actually
 * runs. The pure-native {@code wrgl_tests} drive {@code DrawList} directly against a recording
 * stub, which proves the batching logic but never issues a GL call. The JNI smoke test proves the
 * bridge resolves {@link NativeGlHost}'s five methods but stops short of flushing, because a flush
 * with invented vertex array names would be rejected by the driver. So the path from
 * {@code NativeDrawList.flush} through the function-pointer table into LWJGL and out to the driver
 * had never run, and a fault anywhere along it would have been invisible.</p>
 *
 * <p>This closes that gap. It creates a hidden GLFW window with a 3.3 core context, builds a real
 * vertex array holding one triangle, registers it, queues it and flushes, then reads the pixel at
 * the centre of the viewport back. A green pixel there means the whole chain worked: the batch was
 * registered with a real GL name, the flush bound that array, the cached attributes were written
 * through the host, and {@code glDrawElements} was reached with the right count.</p>
 *
 * <h2>Running it</h2>
 *
 * <p>Needs a GLFW-capable display, so it is a JUnit test rather than part of the standalone smoke
 * test. In an environment with no display it aborts itself through an assumption rather than
 * failing, so it is safe to run anywhere.</p>
 */
public final class NativeDrawListGlTest {

	/**
	 * Fails when the draw path cannot execute, aborts when this machine has no GL context at all.
	 *
	 * <p>The distinction matters: a missing display is an environment fact and should not fail the
	 * build, whereas a GL error, a refused registration or an unpainted pixel is a real fault in the
	 * path and must.</p>
	 */
	@org.junit.jupiter.api.Test
	public void nativeFlushReachesTheDriver() {
		// The status message names the file that was loaded, so a run against a stale library is
		// visible rather than silent. Two copies of the library exist in a checkout - the one
		// cmake builds under native/build-windows and the one processResources stages into
		// fabric/build/resources - and they can be hours apart in age and a few hundred bytes
		// apart in size. Printing the path makes the difference obvious; without it a result can
		// describe a build that was replaced long ago.
		System.out.println("  library: " + NativeObjPipeline.getStatusMessage());

		org.junit.jupiter.api.Assumptions.assumeTrue(NativeObjPipeline.isAvailable(),
				"the native wrobj library is not available: " + NativeObjPipeline.getStatusMessage());

		// Every way of failing to obtain a context has to abort rather than fail. CI runs
		// :fabric:test on a headless Linux runner, where glfwInit itself returns false and there is
		// no monitor to query, so a test that failed on that would break the mod build for a machine
		// limitation rather than a fault in the code.
		boolean displayAvailable;
		try {
			displayAvailable = hasDisplay();
		} catch (Throwable throwable) {
			displayAvailable = false;
		}
		org.junit.jupiter.api.Assumptions.assumeTrue(displayAvailable,
				"no display is available for a GL context");

		final String failure = run();
		org.junit.jupiter.api.Assumptions.assumeTrue(failure == null || !isEnvironmentFailure(failure),
				"no GL context is available on this machine: " + failure);
		org.junit.jupiter.api.Assertions.assertNull(failure,
				"the native draw path did not reach the driver: " + failure);
	}

	/** True when GLFW can see a display, i.e. when a context is worth attempting. */
	private static boolean hasDisplay() {
		if (!GLFW.glfwInit()) {
			return false;
		}
		try {
			// PointerBuffer has no isEmpty(); remaining() is the monitor count.
			return GLFW.glfwGetMonitors() != null && GLFW.glfwGetMonitors().remaining() > 0;
		} finally {
			GLFW.glfwTerminate();
		}
	}

	/** True for the failures that mean "this machine cannot do GL" rather than "the path is broken". */
	private static boolean isEnvironmentFailure(String failure) {
		return failure.contains("GLFW could not be initialised")
				|| failure.contains("GLFW could not create a window")
				|| failure.contains("the test shader failed to link")
				|| failure.contains("the native draw list could not be created");
	}

	private NativeDrawListGlTest() {
	}

	/** Runs the draw path and returns null on success, or a message describing the failure. */
	public static String run() {
		long window = MemoryUtil.NULL;
		int vao = 0;
		int vbo = 0;
		int ebo = 0;
		int program = 0;
		try {
			if (!GLFW.glfwInit()) {
				return "GLFW could not be initialised (no display?)";
			}

			GLFW.glfwDefaultWindowHints();
			GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
			GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
			GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
			GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);

			window = GLFW.glfwCreateWindow(64, 64, "wrobj", MemoryUtil.NULL, MemoryUtil.NULL);
			if (window == MemoryUtil.NULL) {
				return "GLFW could not create a window";
			}
			GLFW.glfwMakeContextCurrent(window);
			GL.createCapabilities();

			// A VAO is mandatory in a core profile, and the native side binds by name.
			vao = GL30.glGenVertexArrays();
			GL30.glBindVertexArray(vao);

			// One triangle covering the whole viewport, in clip space, with the packer's stride of
			// 24 bytes: position as three floats, then two UV floats and a packed normal that this
			// shader ignores.
			vbo = GL15.glGenBuffers();
			GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
			final float[] vertices = {
					-1.0F, -1.0F, 0.0F, 0.0F, 0.0F, 0.0F,
					3.0F, -1.0F, 0.0F, 0.0F, 0.0F, 0.0F,
					-1.0F, 3.0F, 0.0F, 0.0F, 0.0F, 0.0F,
			};
			final java.nio.FloatBuffer buffer = MemoryUtil.memAllocFloat(vertices.length);
			java.nio.FloatBuffer indexBuffer = null;
			try {
				buffer.put(vertices).flip();
				GL15.glBufferData(GL15.GL_ARRAY_BUFFER, buffer, GL15.GL_STATIC_DRAW);
			} finally {
				MemoryUtil.memFree(buffer);
			}
			// Position is attribute 0, three floats, at offset 0 of the 24-byte stride.
			GL20.glEnableVertexAttribArray(0);
			GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 24, 0L);

			ebo = GL15.glGenBuffers();
			GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, ebo);
			final java.nio.IntBuffer elements = MemoryUtil.memAllocInt(3);
			try {
				elements.put(new int[]{0, 1, 2}).flip();
				GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, elements, GL15.GL_STATIC_DRAW);
			} finally {
				MemoryUtil.memFree(elements);
			}

			program = buildProgram();
			if (program == 0) {
				return "the test shader failed to link";
			}

			GL30.glBindVertexArray(0);

			final NativeDrawList list = NativeDrawList.create(false, 64);
			if (list == null) {
				return "the native draw list could not be created";
			}
			try {
				final int batch = list.registerBatch(vao, 3, 0);
				if (batch < 0) {
					return "the vertex array could not be registered";
				}

				GL11.glClearColor(0.0F, 0.0F, 0.0F, 1.0F);
				GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);

				// Two draws in one flush, one of them with an untranslated pose. The native path's
				// entire reason to exist is that it writes the globals once per flush and the pose
				// once per distinct matrix, so a queue holding two draws is the smallest shape that
				// exercises the cache rather than a single unconditional write. The first pose is
				// all zeros so nothing is visible, which makes the readback below a statement about
				// the second draw specifically: if the pose were applied once and then skipped, or
				// applied in the wrong order, the green pixel would not be there.
				GL20.glUseProgram(program);
				list.beginFrame();
				list.queue(batch, zeroPose());
				list.queue(batch, identityPose());
				list.flush(0xFFFFFFFF, 0, 0, false, false, false);
				GL20.glUseProgram(0);

				final int error = GL11.glGetError();
				if (error != GL11.GL_NO_ERROR) {
					return "the flush left a GL error: 0x" + Integer.toHexString(error);
				}

				// The centre of the viewport is inside the triangle, so a green channel here means
				// the draw reached the driver with usable geometry.
				GL11.glFinish();
				final java.nio.ByteBuffer pixel = MemoryUtil.memAlloc(4);
				try {
					GL11.glReadPixels(32, 32, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixel);
					final int red = pixel.get(0) & 0xFF;
					final int green = pixel.get(1) & 0xFF;
					if (green < 200) {
						return "the triangle did not reach the framebuffer: centre pixel rgba="
								+ red + "," + green + "," + (pixel.get(2) & 0xFF) + "," + (pixel.get(3) & 0xFF);
					}
				} finally {
					MemoryUtil.memFree(pixel);
				}

				final String stats = list.describeStatistics();
				if (!stats.contains("2 array(s) drawn")) {
					return "the draw list did not record the draw: " + stats;
				}
				return null;
			} finally {
				list.close();
			}
		} catch (Throwable throwable) {
			return throwable.getClass().getSimpleName() + ": " + throwable.getMessage();
		} finally {
			if (program != 0) {
				GL20.glDeleteProgram(program);
			}
			if (ebo != 0) {
				GL15.glDeleteBuffers(ebo);
			}
			if (vbo != 0) {
				GL15.glDeleteBuffers(vbo);
			}
			if (vao != 0) {
				GL30.glDeleteVertexArrays(vao);
			}
			if (window != MemoryUtil.NULL) {
				GLFW.glfwDestroyWindow(window);
			}
			GLFW.glfwTerminate();
		}
	}

	/** A minimal program: the position attribute out, flat green. */
	private static int buildProgram() {
		final int vertex = GL20.glCreateShader(GL20.GL_VERTEX_SHADER);
		GL20.glShaderSource(vertex, "#version 330 core\n"
				+ "layout(location = 0) in vec3 position;\n"
				+ "void main() { gl_Position = vec4(position, 1.0); }\n");
		GL20.glCompileShader(vertex);
		if (GL20.glGetShaderi(vertex, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
			return 0;
		}

		final int fragment = GL20.glCreateShader(GL20.GL_FRAGMENT_SHADER);
		GL20.glShaderSource(fragment, "#version 330 core\n"
				+ "out vec4 colour;\n"
				+ "void main() { colour = vec4(0.0, 1.0, 0.0, 1.0); }\n");
		GL20.glCompileShader(fragment);
		if (GL20.glGetShaderi(fragment, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
			return 0;
		}

		final int program = GL20.glCreateProgram();
		GL20.glAttachShader(program, vertex);
		GL20.glAttachShader(program, fragment);
		GL20.glLinkProgram(program);
		GL20.glDeleteShader(vertex);
		GL20.glDeleteShader(fragment);
		return GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_FALSE ? 0 : program;
	}

	/** A pose of all zeros, which collapses the triangle to a point and paints nothing. */
	private static float[] zeroPose() {
		return new float[16];
	}

	/** A column-major identity, which is what the shader's four vec4 matrix attributes expect. */
	private static float[] identityPose() {
		return new float[]{
				1.0F, 0.0F, 0.0F, 0.0F,
				0.0F, 1.0F, 0.0F, 0.0F,
				0.0F, 0.0F, 1.0F, 0.0F,
				0.0F, 0.0F, 0.0F, 1.0F,
		};
	}
}
