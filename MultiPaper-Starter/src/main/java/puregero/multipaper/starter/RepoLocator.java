package puregero.multipaper.starter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Finds the MultiPaper repository checkout. The starter (and its runtime build)
 * must run from inside the repository because it needs the Gradle wrapper and
 * the patch sets.
 */
final class RepoLocator {
    private RepoLocator() {
    }

    static boolean isRepoRoot(Path dir) {
        return Files.isRegularFile(dir.resolve("gradlew"))
                && Files.isRegularFile(dir.resolve("purpur-versions.properties"));
    }

    static Path locate() {
        String home = System.getenv("MULTIPAPER_HOME");
        if (home != null && !home.isBlank()) {
            Path dir = Paths.get(home).toAbsolutePath().normalize();
            if (!isRepoRoot(dir)) {
                throw new StarterException("MULTIPAPER_HOME is set to " + dir
                        + " but that directory does not contain both gradlew and purpur-versions.properties");
            }
            return dir;
        }

        Path start = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        Path cursor = start;
        while (cursor != null) {
            if (isRepoRoot(cursor)) {
                return cursor;
            }
            cursor = cursor.getParent();
        }
        throw new StarterException("Could not locate the MultiPaper repository root. Searched upward from "
                + start + " for a directory containing both gradlew and purpur-versions.properties. "
                + "Set MULTIPAPER_HOME to the repository root.");
    }
}
