package org.mtr.mod.resource.nativeobj;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Checks how the mod decides which bundled library to load.
 *
 * <h2>Why this is a unit test and not a device test</h2>
 *
 * <p>The platforms that decide this logic are the ones this machine cannot run: an iPhone, an
 * iPad, an Intel Mac. The decision is therefore written as a pure function of observable facts and
 * covered here, so the cases that would otherwise be discovered by a user getting a silent
 * fallback to the Java pipeline are instead checked on every build.</p>
 *
 * <h2>The specific mistake being guarded against</h2>
 *
 * <p>{@code System.getProperty("os.name")} is not a way to tell iOS from macOS. iOS and iPadOS are
 * Darwin, and a JVM on them commonly reports {@code "Mac OS X"}. Reading that first classifies an
 * iPhone as a Mac, looks for {@code native/macos-arm64/wrobj.dylib}, finds nothing because the jar
 * carries {@code ios-arm64}, and falls back to the Java pipeline without an error. Nothing about
 * that failure points at the platform check.</p>
 */
public final class NativePlatformResolutionTest {

	private static final String IOS_HOME = "/var/mobile/Containers/Data/Application/8A3F1C0E-0000-0000-0000-000000000000";

	@Test
	public void iOSIsNotMisreadAsMacOS() {
		// os.name is exactly what a JVM on iOS reports, and it is a lie for this purpose.
		final String resolved = NativeObjPipeline.resolvePlatformDirectory(
				"Mac OS X", "aarch64", IOS_HOME, true, false);
		Assertions.assertEquals("ios-arm64", resolved,
				"an iPhone must not be classified as macOS: os.name says Mac OS X on Darwin");
	}

	@Test
	public void iPadIsNotMisreadAsMacOS() {
		// An iPad reports the same os.name and the same architecture as an iPhone, so this asserts
		// the family rather than a separate directory.
		final String resolved = NativeObjPipeline.resolvePlatformDirectory(
				"Darwin", "aarch64", IOS_HOME, true, false);
		Assertions.assertEquals("ios-arm64", resolved);
	}

	@Test
	public void theUIKitSignalAloneIdentifiesAppleMobile() {
		// A launcher can set user.home to anything, so the sandbox home is not trusted on its own.
		final String resolved = NativeObjPipeline.resolvePlatformDirectory(
				"Mac OS X", "aarch64", "/Users/somebody", false, true);
		Assertions.assertEquals("ios-arm64", resolved,
				"UIKit without Finder is a mobile layout even when the home directory looks like a Mac's");
	}

	@Test
	public void aDesktopMacStaysMacOS() {
		Assertions.assertEquals("macos-arm64", NativeObjPipeline.resolvePlatformDirectory(
				"Mac OS X", "aarch64", "/Users/somebody", false, false));
		Assertions.assertEquals("macos-x64", NativeObjPipeline.resolvePlatformDirectory(
				"Mac OS X", "x86_64", "/Users/somebody", false, false));
	}

	@Test
	public void DarwinWithoutTheAppleNameStaysMacOS() {
		// Some JVMs report Darwin rather than Mac OS X. That also means macOS.
		Assertions.assertEquals("macos-arm64", NativeObjPipeline.resolvePlatformDirectory(
				"Darwin", "aarch64", "/Users/somebody", false, false));
	}

	@Test
	public void windowsAndLinuxAreUnaffected() {
		Assertions.assertEquals("windows-x64", NativeObjPipeline.resolvePlatformDirectory(
				"Windows 11", "amd64", "C:\\Users\\somebody", false, false));
		Assertions.assertEquals("windows-arm64", NativeObjPipeline.resolvePlatformDirectory(
				"Windows 11", "aarch64", "C:\\Users\\somebody", false, false));
		Assertions.assertEquals("linux-x64", NativeObjPipeline.resolvePlatformDirectory(
				"Linux", "amd64", "/home/somebody", false, false));
		Assertions.assertEquals("linux-arm64", NativeObjPipeline.resolvePlatformDirectory(
				"Linux", "aarch64", "/home/somebody", false, false));
	}

	@Test
	public void anAppleMobileHomeIsItselfASignal() {
		// iOS puts app data under /var/mobile/Containers/...; no desktop uses that prefix, so the
		// home directory is a legitimate signal even when the disk probes see nothing.
		Assertions.assertEquals("ios-arm64", NativeObjPipeline.resolvePlatformDirectory(
				"Mac OS X", "aarch64", "/var/mobile/somewhere", false, false));

		// And a normal Mac home stays a Mac, which is the case that must not regress.
		Assertions.assertEquals("macos-arm64", NativeObjPipeline.resolvePlatformDirectory(
				"Mac OS X", "aarch64", "/Users/somebody", false, false));
	}

	@Test
	public void iOSAndIPadOSShareOneBinarySoBothDirectoriesAreTried() {
		// Apple compiles iPad apps against the iOS platform, so one iphoneos binary is what both
		// families load. The detection cannot tell them apart from Java, so both names are tried
		// and neither guess can be wrong.
		final List<String> fromIos = NativeObjPipeline.candidatePlatformDirectories("ios-arm64");
		Assertions.assertEquals(List.of("ios-arm64", "ipados-arm64"), fromIos);

		final List<String> fromIpad = NativeObjPipeline.candidatePlatformDirectories("ipados-arm64");
		Assertions.assertEquals(List.of("ipados-arm64", "ios-arm64"), fromIpad);
	}

	@Test
	public void otherPlatformsHaveNoSiblingToTry() {
		Assertions.assertEquals(List.of("macos-arm64"),
				NativeObjPipeline.candidatePlatformDirectories("macos-arm64"));
		Assertions.assertEquals(List.of("windows-x64"),
				NativeObjPipeline.candidatePlatformDirectories("windows-x64"));
	}

	@Test
	public void theFileNameMatchesWhatCMakeProduces() {
		// CMakeLists sets PREFIX "", so there is no lib prefix on any platform. A prefix here makes
		// the mod look for a file the build never produces.
		Assertions.assertEquals("wrobj.dll", NativeObjPipeline.resolveLibraryFileName("windows-x64"));
		Assertions.assertEquals("wrobj.so", NativeObjPipeline.resolveLibraryFileName("linux-arm64"));
		Assertions.assertEquals("wrobj.dylib", NativeObjPipeline.resolveLibraryFileName("macos-arm64"));
		Assertions.assertEquals("wrobj.dylib", NativeObjPipeline.resolveLibraryFileName("ios-arm64"));
		Assertions.assertEquals("wrobj.dylib", NativeObjPipeline.resolveLibraryFileName("ipados-arm64"));
	}

	@Test
	public void everyResolvedPlatformHasAConsistentFileName() {
		for (final String platform : List.of("windows-x64", "linux-x64", "macos-arm64", "ios-arm64", "ipados-arm64")) {
			final String name = NativeObjPipeline.resolveLibraryFileName(platform);
			Assertions.assertFalse(name.startsWith("lib"),
					platform + " resolved to " + name + ", which carries a lib prefix the build does not produce");
		}
	}
}
