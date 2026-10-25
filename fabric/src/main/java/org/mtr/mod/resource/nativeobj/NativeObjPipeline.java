package org.mtr.mod.resource.nativeobj;

import org.mtr.mod.Init;

import javax.annotation.Nullable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Entry point to the native OBJ model pipeline.
 *
 * <p>The native library replaces MTR's CPU-side model pipeline: OBJ and MTL parsing, group
 * and material-group splitting, the per-vertex renderable conversion, every model and
 * placement transform, normal generation, vertex de-duplication, and the interleaved vertex
 * buffer plus index buffer that gets uploaded to OpenGL.</p>
 *
 * <p>Everything here is optional. The library is loaded lazily and every failure path leaves
 * {@link #isAvailable()} false, in which case the caller keeps using the original Java
 * pipeline. There is deliberately no configuration switch that can make a missing or
 * mismatched library fatal: a resource pack must never be able to stop the game from
 * starting.</p>
 *
 * <h2>Why the ABI is checked</h2>
 *
 * <p>A stale library that agrees on method names but not on semantics would produce subtly
 * wrong geometry rather than an error, which is far worse than falling back. So the native
 * side reports an ABI integer and the load is rejected unless it matches exactly.</p>
 */
public final class NativeObjPipeline {

	/** Must match {@code WROBJ_ABI_VERSION} in native/include/wrobj/wrobj.hpp. */
	public static final int EXPECTED_ABI = 1;

	private static final String LIBRARY_NAME = "wrobj";

	/** Set once loading has been attempted, successfully or not. */
	private static boolean loadAttempted;

	private static boolean available;

	private static String statusMessage = "not loaded yet";

	/** Diagnostics, so a player report can say which path was taken and why. */
	private static long buildCount;
	private static long placementCount;
	private static long totalVertices;
	private static long estimatedHeapBytesAvoided;

	private NativeObjPipeline() {
	}

	/**
	 * Loads the native library on first use.
	 *
	 * @return true when the native pipeline can be used
	 */
	public static synchronized boolean isAvailable() {
		if (!loadAttempted) {
			loadAttempted = true;
			available = tryLoad();
		}
		return available;
	}

	/** A human-readable description of the load outcome, for logs. */
	public static synchronized String getStatusMessage() {
		return statusMessage;
	}

	/** A one-line summary of the work the native pipeline has performed. */
	public static synchronized String getStatistics() {
		return String.format(
				"native OBJ pipeline: %d model build(s), %d placement(s), %d vertices, "
						+ "~%.1f MiB of Java heap avoided",
				buildCount, placementCount, totalVertices, estimatedHeapBytesAvoided / 1048576.0);
	}

	static synchronized void recordBuild(int placements, long vertices, long heapBytes) {
		buildCount++;
		placementCount += placements;
		totalVertices += vertices;
		estimatedHeapBytesAvoided += heapBytes;
	}

	// -----------------------------------------------------------------------
	// Library loading
	// -----------------------------------------------------------------------

	private static boolean tryLoad() {
		// The packaged layout first: the mod jar ships the library next to the classes, and
		// the loader extracts it to a temporary directory before loadLibrary can find it.
		if (loadFromModDirectory()) {
			statusMessage = "loaded from the mod's native directory, ABI " + nativeAbiVersion();
			return true;
		}
		// A developer build tree, so a local `cmake --build` is usable without repackaging.
		for (final Path candidate : developmentCandidates()) {
			if (loadFromPath(candidate)) {
				statusMessage = "loaded from " + candidate + ", ABI " + nativeAbiVersion();
				return true;
			}
		}
		// Finally the plain library path, which covers a library installed system wide and
		// an explicit -Djava.library.path for testing.
		try {
			System.loadLibrary(LIBRARY_NAME);
			if (nativeAbiVersion() == EXPECTED_ABI) {
				statusMessage = "loaded from java.library.path, ABI " + EXPECTED_ABI;
				return true;
			}
			statusMessage = "ABI mismatch on java.library.path: expected " + EXPECTED_ABI
					+ ", found " + nativeAbiVersion();
			return false;
		} catch (UnsatisfiedLinkError error) {
			statusMessage = "not available (" + error.getMessage() + "); the Java OBJ pipeline will be used";
			Init.LOGGER.info("[wrobj] {}", statusMessage);
			return false;
		}
	}

	private static boolean loadFromModDirectory() {
		// This class lives inside the mod jar, so its own code source locates the jar. When
		// running from a directory (a dev run) there is no jar to inspect and this returns
		// false, leaving the development candidates to do the work.
		try {
			final var source = NativeObjPipeline.class.getProtectionDomain().getCodeSource();
			if (source == null || source.getLocation() == null) {
				return false;
			}
			final Path location = Paths.get(source.getLocation().toURI());
			if (!Files.isRegularFile(location)) {
				return false;
			}
			return loadFromJar(location);
		} catch (Throwable throwable) {
			return false;
		}
	}

	/**
	 * Extracts the platform library from a jar and loads it.
	 *
	 * <p>The library is written to a temporary directory keyed by its own length so repeated
	 * loads of the same build reuse one file instead of filling the temp directory. A pack
	 * cannot influence the path, which matters because the file is executed.</p>
	 */
	private static boolean loadFromJar(Path jarPath) {
		try (final var jar = new java.util.jar.JarFile(jarPath.toFile())) {
			// Every candidate is tried, not just the detected one. A device the heuristics label as
			// the other Apple mobile family still finds the library, which is what makes the
			// iOS/iPadOS distinction not matter.
			for (final String directory : candidatePlatformDirectories(platformDirectory())) {
				final String fileName = resolveLibraryFileName(directory);
				final String entryName = "native/" + directory + "/" + fileName;
				final var entry = jar.getEntry(entryName);
				if (entry == null) {
					continue;
				}
				final Path directoryPath = Paths.get(System.getProperty("java.io.tmpdir"), "mtr-wrobj");
				Files.createDirectories(directoryPath);
				final Path target = directoryPath.resolve(entry.getSize() + "-" + fileName);
				if (!Files.isRegularFile(target) || Files.size(target) != entry.getSize()) {
					try (final var input = jar.getInputStream(entry)) {
						Files.copy(input, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
					}
				}
				if (loadFromPath(target)) {
					return true;
				}
			}
			return false;
		} catch (Throwable throwable) {
			return false;
		}
	}

	private static boolean loadFromPath(Path path) {
		if (!Files.isRegularFile(path)) {
			return false;
		}
		try {
			System.load(path.toAbsolutePath().toString());
			if (nativeAbiVersion() != EXPECTED_ABI) {
				statusMessage = "ABI mismatch at " + path + ": expected " + EXPECTED_ABI
						+ ", found " + nativeAbiVersion();
				Init.LOGGER.warn("[wrobj] {}", statusMessage);
				return false;
			}
			return true;
		} catch (UnsatisfiedLinkError error) {
			Init.LOGGER.warn("[wrobj] {} could not be loaded: {}", path, error.getMessage());
			return false;
		}
	}

	/** Candidate paths in a source checkout, so a local native build is picked up. */
	private static Iterable<Path> developmentCandidates() {
		final String workingDirectory = System.getProperty("user.dir", ".");
		final String fileName = resolveLibraryFileName(platformDirectory());
		// build-windows and build are kept for the layouts that already exist, and the per-platform
		// build-<directory> names cover an iOS or Linux build tree in a checkout.
		final java.util.List<Path> paths = new java.util.ArrayList<>(6);
		for (final String directory : candidatePlatformDirectories(platformDirectory())) {
			final String name = resolveLibraryFileName(directory);
			paths.add(Paths.get(workingDirectory, "native", "build-" + directory, name));
			paths.add(Paths.get(workingDirectory, "..", "native", "build-" + directory, name).normalize());
		}
		paths.add(Paths.get(workingDirectory, "native", "build-windows", fileName));
		paths.add(Paths.get(workingDirectory, "native", "build", fileName));
		paths.add(Paths.get(workingDirectory, "..", "native", "build-windows", fileName).normalize());
		paths.add(Paths.get(workingDirectory, "..", "native", "build", fileName).normalize());
		return paths;
	}

	/**
	 * Resolves the jar directory holding this JVM's library from observable facts alone.
	 *
	 * <p>Deliberately a pure function: the platform cases that matter most here are the ones this
	 * machine cannot run - an iPhone, an iPad, an Intel Mac - so they are encoded as inputs and
	 * covered by tests rather than left to be discovered on a device.</p>
	 *
	 * <p>The order of the checks is the whole point. {@code os.name} is <em>not</em> a reliable way
	 * to tell iOS from macOS: iOS and iPadOS are Darwin, and a JVM on them commonly reports
	 * {@code "Mac OS X"}. Reading {@code os.name} first therefore classifies an iPhone as a Mac and
	 * asks for {@code macos-arm64}, which is the wrong directory, and the result is a silent
	 * fallback to the Java pipeline. Apple mobile is decided from the sandbox layout and the
	 * presence of UIKit before macOS is considered at all.</p>
	 *
	 * @param osName        {@code os.name}, which lies about iOS
	 * @param osArch        {@code os.arch}
	 * @param userHome      {@code user.home}, which is inside the app sandbox on iOS
	 * @param appleSandbox  whether the iOS/iPadOS sandbox signals were seen on disk
	 * @param uikitPresent  whether UIKit is installed and Finder is not, i.e. a mobile layout
	 * @return a directory name of the form {@code <os>-<arch>}
	 */
	static String resolvePlatformDirectory(
			String osName, String osArch, String userHome, boolean appleSandbox, boolean uikitPresent) {
		final String os = osName == null ? "" : osName.toLowerCase(java.util.Locale.ROOT);
		final String arch = osArch == null ? "" : osArch.toLowerCase(java.util.Locale.ROOT);
		final String home = userHome == null ? "" : userHome;
		final String archName = arch.contains("aarch64") || arch.contains("arm64") ? "arm64" : "x64";

		// "windows", not "win": Darwin contains the letters w-i-n, so a JVM on macOS reporting
		// os.name as "Darwin" matched a contains("win") test and was classified as Windows - which
		// then asked for wrobj.dll. Every os.name Windows actually reports contains "windows".
		if (os.contains("windows")) {
			return "windows-" + archName;
		}

		// Before macOS, and not from os.name. iOS and iPadOS share one build: Apple compiles iPad
		// apps against the iOS platform, so a single iphoneos binary is what both families load.
		// The canonical directory is ios-; candidatePlatformDirectories adds the ipados- sibling.
		if (appleSandbox || uikitPresent
				|| home.startsWith("/var/mobile")
				|| home.contains("/Containers/Data/Application")) {
			return "ios-" + archName;
		}

		if (os.contains("mac") || os.contains("darwin")) {
			return "macos-" + archName;
		}

		return "linux-" + archName;
	}

	/**
	 * The directories to try, most likely first.
	 *
	 * <p>A list rather than a single name because a device the detection labels wrongly can still
	 * find a compatible build. iOS and iPadOS cannot be told apart from Java - both are Darwin,
	 * both report the same architecture, and neither exposes the user-interface idiom - so a
	 * single guess would be a coin toss on a real iPad. Since the two families share one binary,
	 * staging it under both names and trying both removes the guess instead of gambling on it.</p>
	 */
	static java.util.List<String> candidatePlatformDirectories(String primary) {
		final java.util.List<String> directories = new java.util.ArrayList<>(2);
		directories.add(primary);
		if (primary.startsWith("ios-")) {
			directories.add("ipados-" + primary.substring("ios-".length()));
		} else if (primary.startsWith("ipados-")) {
			directories.add("ios-" + primary.substring("ipados-".length()));
		}
		return directories;
	}

	/** The file name CMake produces for a directory, given {@code PREFIX ""}. */
	static String resolveLibraryFileName(String platformDirectory) {
		if (platformDirectory.startsWith("windows")) {
			return LIBRARY_NAME + ".dll";
		}
		// No "lib" prefix: CMakeLists sets PREFIX "" on the wrobj target, so the artefacts are
		// wrobj.so and wrobj.dylib. Requiring the prefix would make the mod look for a file the
		// build never produces and fall back silently.
		if (platformDirectory.startsWith("macos")
				|| platformDirectory.startsWith("ios")
				|| platformDirectory.startsWith("ipados")) {
			return LIBRARY_NAME + ".dylib";
		}
		return LIBRARY_NAME + ".so";
	}

	/**
	 * An explicit override, for a launcher that knows better than the heuristics.
	 *
	 * <p>Set {@code -Dmtr.wrobj.platform=ios-arm64} or the {@code MTR_WROBJ_PLATFORM} environment
	 * variable. Without this, a launcher running on an unusual configuration has no way to correct
	 * the detection, and the only symptom is the Java pipeline being used instead.</p>
	 */
	private static String platformOverride() {
		final String property = System.getProperty("mtr.wrobj.platform", "").trim();
		if (!property.isEmpty()) {
			return property;
		}
		final String environment = System.getenv("MTR_WROBJ_PLATFORM");
		return environment == null ? "" : environment.trim();
	}

	private static String platformDirectory() {
		final String override = platformOverride();
		if (!override.isEmpty()) {
			return override;
		}
		return resolvePlatformDirectory(
				System.getProperty("os.name"),
				System.getProperty("os.arch"),
				firstNonEmpty(System.getProperty("user.home"), System.getenv("HOME")),
				hasAppleMobileSandbox(),
				hasMobileFrameworksWithoutFinder());
	}

	/**
	 * True when the filesystem looks like an iOS app sandbox.
	 *
	 * <p>{@code user.home} is checked by the caller; this looks at the disk, because a launcher can
	 * set the property to anything while it cannot invent {@code /var/mobile} on a Mac.</p>
	 */
	private static boolean hasAppleMobileSandbox() {
		try {
			return Files.isDirectory(Paths.get("/var/mobile"));
		} catch (Throwable throwable) {
			return false;
		}
	}

	/**
	 * True when UIKit is installed and Finder is not.
	 *
	 * <p>UIKit alone is not enough: Mac Catalyst installs it on macOS. The absence of Finder is
	 * what separates a phone or tablet from a desktop.</p>
	 */
	private static boolean hasMobileFrameworksWithoutFinder() {
		try {
			final boolean mobile = Files.isDirectory(Paths.get("/System/Library/Frameworks/UIKit.framework"));
			final boolean desktop = Files.isDirectory(
					Paths.get("/System/Library/CoreServices/Finder.app"))
					|| Files.isDirectory(Paths.get("/System/Applications/Finder.app"));
			return mobile && !desktop;
		} catch (Throwable throwable) {
			return false;
		}
	}

	private static String firstNonEmpty(String first, String second) {
		if (first != null && !first.isEmpty()) {
			return first;
		}
		return second == null ? "" : second;
	}

	// -----------------------------------------------------------------------
	// Native declarations
	// -----------------------------------------------------------------------

	/**
	 * Parses an OBJ document and its MTL, reporting the material group keys the reference
	 * loader would produce, in the same order.
	 *
	 * @return the keys, or null when the document is structurally unreadable
	 */
	@Nullable
	public static native NativeObjKey[] nativeLoadKeys(byte[] objContent, byte[] mtlContent, boolean flipTextureV);

	/**
	 * Creates a builder for one model instance.
	 *
	 * <p>Each vehicle part needs its own builder. The reference implementation deep-copies
	 * the model per part, and sharing a builder would accumulate every part's geometry into
	 * one buffer - the cross-condition rendering bug this branch already had to fix.</p>
	 *
	 * @param materialGroupTextures false to take the loader's "no materials" branch, where
	 *                              the material group name doubles as the texture string
	 * @param textureResolver       resolves each key's texture string to a handle
	 * @return a native handle, or 0 on failure
	 */
	public static native long nativeCreateBuilder(byte[] objContent, byte[] mtlContent,
			boolean materialGroupTextures, boolean flipTextureV, NativeTextureResolver textureResolver);

	/** Releases a builder. Safe to call with 0. */
	public static native void nativeFreeBuilder(long handle);

	/**
	 * Applies a model-level transform to the shared source meshes, matching
	 * {@code RawMesh.applyTranslation / applyRotation / applyScale / applyMirror}.
	 *
	 * @param operation 0 translation, 1 rotation in degrees per axis, 2 scale, 3 mirror flags
	 */
	public static native void nativeApplyTransform(long handle, int operation, double a, double b, double c);

	/**
	 * Appends a placed copy of every source mesh, matching
	 * {@code ObjModel.addTransformation(shaderType, x, y, z, flipped)}.
	 */
	public static native void nativeAddTransform(long handle, int shaderType, double x, double y, double z, boolean flipped);

	/** Bounding box of the source meshes, reflecting every transform applied so far. */
	@Nullable
	public static native float[] nativeGetBounds(long handle);

	/** Runs normal generation, de-duplication and packing. Returns null for an empty model. */
	@Nullable
	public static native NativeObjResult nativeBuild(long handle);

	/** Releases a build result. Safe to call with 0. */
	public static native void nativeFreeResult(long handle);

	/** The native library's ABI version. */
	public static native int nativeAbiVersion();

	/** The native library's version string, for logs. */
	public static native String nativeVersionString();

	// -----------------------------------------------------------------------
	// Guarded wrappers
	// -----------------------------------------------------------------------

	/**
	 * Parses an OBJ document and reports its material group keys, or null when the native
	 * pipeline is unavailable or the document is unreadable.
	 */
	@Nullable
	public static NativeObjKey[] loadKeys(byte[] objContent, @Nullable byte[] mtlContent, boolean flipTextureV) {
		if (!isAvailable() || objContent == null || objContent.length == 0) {
			return null;
		}
		try {
			final NativeObjKey[] keys = nativeLoadKeys(objContent, mtlContent, flipTextureV);
			return keys == null || keys.length == 0 ? null : keys;
		} catch (Throwable throwable) {
			// A malformed model is a resource pack problem, not a mod failure. Log it and let
			// the caller fall back rather than propagating out of model loading.
			Init.LOGGER.warn("[wrobj] could not parse an OBJ document: {}", throwable.toString());
			return null;
		}
	}

	/**
	 * Creates a builder, or returns 0 when the native pipeline is unavailable or the
	 * document is unreadable.
	 */
	public static long createBuilder(byte[] objContent, @Nullable byte[] mtlContent,
			boolean materialGroupTextures, boolean flipTextureV, @Nullable NativeTextureResolver textureResolver) {
		if (!isAvailable() || objContent == null || objContent.length == 0) {
			return 0L;
		}
		try {
			return nativeCreateBuilder(objContent, mtlContent, materialGroupTextures, flipTextureV,
					textureResolver == null ? NativeTextureResolver.unresolved() : textureResolver);
		} catch (Throwable throwable) {
			Init.LOGGER.warn("[wrobj] could not build a native model: {}", throwable.toString());
			return 0L;
		}
	}

	/** Creates a builder from an OBJ document that has no MTL at all. */
	public static long createBuilder(byte[] objContent, boolean flipTextureV,
			@Nullable NativeTextureResolver textureResolver) {
		return createBuilder(objContent, null, false, flipTextureV, textureResolver);
	}

	static void freeBuilder(long handle) {
		if (handle == 0L) {
			return;
		}
		try {
			nativeFreeBuilder(handle);
		} catch (Throwable throwable) {
			Init.LOGGER.warn("[wrobj] could not release a native builder: {}", throwable.toString());
		}
	}

	/**
	 * Releases a builder created through the native entry points.
	 *
	 * <p>Public so the equivalence harness can drive {@code nativeCreateBuilder} directly and
	 * still clean up. Normal code goes through {@link NativeObjModel}, which owns its handle.</p>
	 */
	public static void freeNativeBuilder(long handle) {
		freeBuilder(handle);
	}

	static void freeResult(long handle) {
		if (handle == 0L) {
			return;
		}
		try {
			nativeFreeResult(handle);
		} catch (Throwable throwable) {
			Init.LOGGER.warn("[wrobj] could not release a native result: {}", throwable.toString());
		}
	}

	@Nullable
	static float[] getBounds(long handle) {
		if (handle == 0L) {
			return null;
		}
		try {
			return nativeGetBounds(handle);
		} catch (Throwable throwable) {
			Init.LOGGER.warn("[wrobj] could not read model bounds: {}", throwable.toString());
			return null;
		}
	}

	static void applyTransform(long handle, int operation, double a, double b, double c) {
		if (handle == 0L) {
			return;
		}
		try {
			nativeApplyTransform(handle, operation, a, b, c);
		} catch (Throwable throwable) {
			Init.LOGGER.warn("[wrobj] could not apply a model transform: {}", throwable.toString());
		}
	}

	static void addTransform(long handle, int shaderType, double x, double y, double z, boolean flipped) {
		if (handle == 0L) {
			return;
		}
		try {
			nativeAddTransform(handle, shaderType, x, y, z, flipped);
		} catch (Throwable throwable) {
			Init.LOGGER.warn("[wrobj] could not add a model placement: {}", throwable.toString());
		}
	}

	@Nullable
	static NativeObjResult build(long handle) {
		if (handle == 0L) {
			return null;
		}
		try {
			return nativeBuild(handle);
		} catch (Throwable throwable) {
			Init.LOGGER.warn("[wrobj] could not finish a native model: {}", throwable.toString());
			return null;
		}
	}

	/** The native library's own description, or null when it is not loaded. */
	@Nullable
	public static String getNativeVersion() {
		if (!isAvailable()) {
			return null;
		}
		try {
			return nativeVersionString();
		} catch (Throwable throwable) {
			return null;
		}
	}

	/** Exposed for diagnostics: the library file name on this platform. */
	public static String getLibraryFileName() {
		return resolveLibraryFileName(platformDirectory());
	}

	/** Exposed for diagnostics: the packaged directory name on this platform. */
	public static String getPlatformDirectory() {
		return platformDirectory();
	}
}
