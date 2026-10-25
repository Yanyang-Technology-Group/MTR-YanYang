import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

import java.io.PrintWriter;

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
import static org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder.request;

/**
 * Drives the JUnit suites directly.
 *
 * <p>Gradle's test worker cannot load these classes in this environment: they compile to the right
 * directory and are provably on the worker's classpath, yet the worker reports
 * ClassNotFoundException for them. The launcher is therefore invoked by hand against the same
 * classpath.</p>
 *
 * <p>Run from the {@code fabric} directory, because the suites resolve their fixtures relative to
 * it and the library loader resolves {@code ../native/build-windows} from there.</p>
 *
 * <p>This file lives in {@code native/tools} rather than in the build tree on purpose. It used to
 * live under {@code native/build-windows}, which {@code build-native.ps1 -Clean} deletes, and
 * because a missing source makes the JVM write nothing at all to a redirected output file, several
 * measurements in this session were taken from an empty file and read as negative results rather
 * than as errors. Outside the build tree it cannot be deleted by a build.</p>
 */
public class RunTests {
	public static void main(String[] args) {
		final LauncherDiscoveryRequest req = request().selectors(
				selectClass("org.mtr.mod.resource.nativeobj.NativeObjEquivalenceTest"),
				selectClass("org.mtr.mod.resource.nativeobj.NativeVertexLayoutCompatibilityTest"),
				selectClass("org.mtr.mod.resource.nativeobj.NativeDrawListGlTest"),
				selectClass("org.mtr.mod.resource.nativeobj.NativePlatformResolutionTest")).build();
		final Launcher launcher = LauncherFactory.create();
		final SummaryGeneratingListener listener = new SummaryGeneratingListener();
		launcher.execute(req, listener);
		final TestExecutionSummary s = listener.getSummary();
		final PrintWriter out = new PrintWriter(System.out, true);
		s.printTo(out);
		s.printFailuresTo(out, 12);
		System.out.println("RESULT tests=" + s.getTestsFoundCount()
				+ " succeeded=" + s.getTestsSucceededCount()
				+ " failed=" + s.getTestsFailedCount()
				+ " aborted=" + s.getTestsAbortedCount());
	}
}
