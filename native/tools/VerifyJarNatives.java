import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

/**
 * Loads the native library out of a built mod jar, exactly the way the mod does at runtime.
 *
 * <p>Run with the mod jar on the class path and the jar path as the argument. It mirrors
 * {@code NativeObjPipeline.loadFromJar}: find the entry for this platform, copy it to a temporary
 * directory, and {@code System.load} the copy.</p>
 *
 * <p>Loading is not a formality. {@code JNI_OnLoad} runs during the load and registers the native
 * methods through {@code JNIEnv::FindClass}, so a successful load proves three things at once: the
 * jar entry name matches what the loader looks for, the binary was built for this architecture and
 * its dependencies resolve, and the JNI bridge can find and bind the Java classes it declares. Any
 * of those failing throws {@code UnsatisfiedLinkError} from this call.</p>
 */
public final class VerifyJarNatives {

	public static void main(String[] args) throws Exception {
		final Path jarPath = Path.of(args[0]);
		final String platform = args[1];
		final String fileName = args[2];
		final String entryName = "native/" + platform + "/" + fileName;

		try (final JarFile jar = new JarFile(jarPath.toFile())) {
			final ZipEntry entry = jar.getEntry(entryName);
			if (entry == null) {
				System.out.println("RESULT FAIL entry not found: " + entryName);
				return;
			}

			final Path directory = Files.createTempDirectory("wrobj-jar-verify");
			final Path target = directory.resolve(fileName);
			try (final InputStream input = jar.getInputStream(entry)) {
				Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
			}

			System.out.println("entry   " + entryName + " (" + entry.getSize() + " bytes)");
			System.out.println("extract " + target);

			try {
				System.load(target.toAbsolutePath().toString());
				System.out.println("RESULT OK loaded and JNI_OnLoad completed");
			} catch (Throwable throwable) {
				System.out.println("RESULT FAIL " + throwable.getClass().getName() + ": " + throwable.getMessage());
			}
		}
	}
}
