package puregero.multipaper.starter;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Owns the per-version build cache under {@code ~/.multipaper/<version>/}.
 *
 * <p>A build is considered up to date when the recorded Purpur ref and the
 * recorded hash of the patch set + root build files still match the working
 * tree, and the cached Paperclip jar is present.
 */
final class CacheManager {
    private final Path versionDir;
    private final Path markerFile;

    CacheManager(Path cacheRoot, String version) {
        this.versionDir = cacheRoot.resolve(version);
        this.markerFile = versionDir.resolve("build.properties");
    }

    static Path defaultCacheRoot() {
        return Paths.get(System.getProperty("user.home", ".")).resolve(".multipaper");
    }

    Path versionDir() {
        return versionDir;
    }

    Path cachedPaperclipJar(String version) {
        return versionDir.resolve("multipaper-" + version + "-paperclip.jar");
    }

    Map<String, String> readMarker() throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        if (!Files.isRegularFile(markerFile)) {
            return values;
        }
        for (String line : Files.readAllLines(markerFile, StandardCharsets.UTF_8)) {
            int separator = line.indexOf('=');
            if (separator > 0) {
                values.put(line.substring(0, separator).trim(), line.substring(separator + 1).trim());
            }
        }
        return values;
    }

    void writeMarker(String version, String mcVersion, String ref, String patchesHash, String jarName)
            throws IOException {
        Files.createDirectories(versionDir);
        List<String> lines = new ArrayList<>();
        lines.add("# MultiPaper starter build marker. Regenerated automatically.");
        lines.add("version=" + version);
        lines.add("mcVersion=" + mcVersion);
        lines.add("ref=" + ref);
        lines.add("patchesHash=" + patchesHash);
        lines.add("jar=" + jarName);
        lines.add("builtAt=" + Instant.now());
        Files.write(markerFile, lines, StandardCharsets.UTF_8);
    }

    /**
     * Hashes every file under {@code patches/<version>/} plus the root
     * {@code build.gradle.kts}, {@code settings.gradle.kts} and
     * {@code gradle.properties}. The result changes whenever the patch set or
     * the build configuration changes, which is exactly when a rebuild is
     * needed.
     */
    static String hashInputs(Path repo, String version) throws IOException {
        MessageDigest digest = newDigest();

        List<Path> files = new ArrayList<>();
        Path patchDir = repo.resolve("patches").resolve(version);
        if (Files.isDirectory(patchDir)) {
            try (Stream<Path> stream = Files.walk(patchDir)) {
                stream.filter(Files::isRegularFile).forEach(files::add);
            }
        }
        files.add(repo.resolve("build.gradle.kts"));
        files.add(repo.resolve("settings.gradle.kts"));
        files.add(repo.resolve("gradle.properties"));
        files.sort(Comparator.comparing(path -> repo.relativize(path).toString()));

        for (Path file : files) {
            if (!Files.isRegularFile(file)) {
                continue;
            }
            digest.update(repo.relativize(file).toString().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            digest.update((byte) 0);
        }
        return toHex(digest.digest());
    }

    static void copy(Path source, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            builder.append(Character.forDigit((b >> 4) & 0xF, 16));
            builder.append(Character.forDigit(b & 0xF, 16));
        }
        return builder.toString();
    }
}
