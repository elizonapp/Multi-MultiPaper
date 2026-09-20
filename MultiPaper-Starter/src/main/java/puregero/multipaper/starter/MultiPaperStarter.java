package puregero.multipaper.starter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * Version-independent MultiPaper launcher.
 *
 * <p>Resolves a Purpur version, applies the matching MultiPaper patches by
 * building the Paperclip jar on demand (caching the result under
 * {@code ~/.multipaper/<version>/}) and finally launches the patched server.
 * The starter itself contains no Minecraft code.
 */
public final class MultiPaperStarter {

    // These are root-project tasks registered by the paperweight patcher. The
    // Paperclip/Bundler tasks are deliberately created on the root project and
    // are named "create<Classifier>PaperclipJar".
    private static final String APPLY_PATCHES_TASK = "applyPatches";
    private static final String PAPERCLIP_TASK = "createReobfPaperclipJar";
    private static final String PATCHES_DIR = "patches";
    private static final String SERVER_PATCH_DIR = "server";

    private MultiPaperStarter() {
    }

    public static void main(String[] args) {
        try {
            run(args);
        } catch (StarterException e) {
            System.err.println("error: " + e.getMessage());
            System.exit(1);
        } catch (IOException e) {
            System.err.println("error: " + e);
            System.exit(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("error: interrupted");
            System.exit(130);
        }
    }

    private static void run(String[] args) throws IOException, InterruptedException {
        Options options = Options.parse(args);

        if (options.help) {
            printHelp();
            return;
        }

        Path repo = RepoLocator.locate();
        Path registryFile = repo.resolve("purpur-versions.properties");
        VersionRegistry registry = VersionRegistry.load(registryFile);

        if (options.listVersions) {
            printVersions(registry);
            return;
        }

        String version = resolveVersion(options, repo, registry);
        if (!registry.contains(version)) {
            throw new StarterException("Unknown Purpur version \"" + version + "\". Available: " + registry.availableList());
        }
        VersionRegistry.Entry entry = registry.get(version);

        if (options.showVersion) {
            System.out.println("purpurVersion=" + entry.id);
            System.out.println("mcVersion=" + entry.mcVersion);
            System.out.println("branch=" + entry.branch);
            System.out.println("ref=" + entry.ref);
            System.out.println("channel=" + entry.channel);
            return;
        }

        Path serverPatches = repo.resolve(PATCHES_DIR).resolve(version).resolve(SERVER_PATCH_DIR);
        if (isEffectivelyEmpty(serverPatches)) {
            System.err.println("[multipaper] WARNING: " + repo.relativize(serverPatches)
                    + " is empty; MultiPaper has no server patches for " + version + " yet.");
        }

        Path cacheRoot = CacheManager.defaultCacheRoot();
        CacheManager cache = new CacheManager(cacheRoot, version);
        Path cachedJar = cache.cachedPaperclipJar(version);
        String patchesHash = CacheManager.hashInputs(repo, version);

        if (options.printCommand) {
            System.out.println(displayGradleCommand(APPLY_PATCHES_TASK, version, entry.mcVersion, options.gradleArgs));
            System.out.println(displayGradleCommand(PAPERCLIP_TASK, version, entry.mcVersion, options.gradleArgs));
            if (!options.noLaunch) {
                System.out.println(displayLaunchCommand(cachedJar, options.serverArgs));
            }
            return;
        }

        Map<String, String> marker = cache.readMarker();
        boolean upToDate = !options.rebuild
                && Files.isRegularFile(cachedJar)
                && entry.ref.equals(marker.get("ref"))
                && patchesHash.equals(marker.get("patchesHash"));

        if (options.noBuild) {
            if (!Files.isRegularFile(cachedJar)) {
                throw new StarterException("--no-build was given but no cached jar exists at " + cachedJar
                        + ". Run once without --no-build to prepare it.");
            }
            System.out.println("[multipaper] Using cached jar " + cachedJar);
        } else if (upToDate) {
            System.out.println("[multipaper] Cached build for Purpur " + version + " is up to date; skipping build.");
        } else {
            System.out.println("[multipaper] Preparing MultiPaper for Purpur " + version
                    + " (Minecraft " + entry.mcVersion + "). This can take a while...");
            build(repo, version, entry, cache, cachedJar, patchesHash, options.gradleArgs);
        }

        if (options.noLaunch) {
            System.out.println("[multipaper] --no-launch set; not starting the server.");
            System.out.println("[multipaper] Paperclip jar: " + cachedJar);
            return;
        }

        launch(cachedJar, options.serverArgs);
    }

    // ------------------------------------------------------------------
    // Version resolution
    // ------------------------------------------------------------------

    private static String resolveVersion(Options options, Path repo, VersionRegistry registry) throws IOException {
        if (options.purpurVersion != null && !options.purpurVersion.isBlank()) {
            return options.purpurVersion.trim();
        }

        String property = System.getProperty("multipaper.purpurVersion");
        if (property != null && !property.isBlank()) {
            return property.trim();
        }

        String env = System.getenv("MULTIPAPER_PURPUR_VERSION");
        if (env != null && !env.isBlank()) {
            return env.trim();
        }

        for (Path candidate : propertiesCandidates(repo)) {
            if (!Files.isRegularFile(candidate)) {
                continue;
            }
            Properties properties = new Properties();
            try (var in = Files.newInputStream(candidate)) {
                properties.load(in);
            }
            String value = properties.getProperty("purpurVersion");
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }

        var console = System.console();
        if (console == null) {
            throw new StarterException("No Purpur version specified and stdin is not a terminal. "
                    + "Use --purpur-version=<v>, -Dmultipaper.purpurVersion=<v>, MULTIPAPER_PURPUR_VERSION, "
                    + "or create multipaper.properties with purpurVersion=<v>.");
        }
        return promptVersion(console, registry);
    }

    private static List<Path> propertiesCandidates(Path repo) {
        List<Path> candidates = new ArrayList<>();
        Path repoFile = repo.resolve("multipaper.properties");
        candidates.add(repoFile);
        Path cwdFile = Paths.get("multipaper.properties").toAbsolutePath().normalize();
        if (!cwdFile.equals(repoFile)) {
            candidates.add(cwdFile);
        }
        return candidates;
    }

    private static String promptVersion(java.io.Console console, VersionRegistry registry) {
        System.out.println("Select the Purpur version MultiPaper should be built against:");
        List<String> ids = registry.ids();
        for (int i = 0; i < ids.size(); i++) {
            VersionRegistry.Entry entry = registry.get(ids.get(i));
            System.out.printf("  %d) %-8s  Minecraft %-9s  %s%n",
                    i + 1, entry.id, entry.mcVersion, entry.channel);
        }
        while (true) {
            String input = console.readLine("Enter a number (1-%d): ", ids.size());
            if (input == null) {
                throw new StarterException("No input received; aborting.");
            }
            input = input.trim();
            try {
                int choice = Integer.parseInt(input);
                if (choice >= 1 && choice <= ids.size()) {
                    return ids.get(choice - 1);
                }
            } catch (NumberFormatException ignored) {
                // fall through to the error below
            }
            System.err.println("Invalid choice. Try again.");
        }
    }

    // ------------------------------------------------------------------
    // Build / launch
    // ------------------------------------------------------------------

    private static void build(Path repo, String version, VersionRegistry.Entry entry, CacheManager cache,
            Path cachedJar, String patchesHash, List<String> gradleArgs) throws IOException, InterruptedException {
        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome != null && !javaHome.isBlank()) {
            System.out.println("[multipaper] JAVA_HOME=" + javaHome);
        }

        // Patch application and jar creation must run as separate Gradle
        // invocations; putting both in one graph trips Gradle's implicit
        // dependency validation (applyApiPatches output is consumed by the
        // server project).
        runGradle(repo, APPLY_PATCHES_TASK, version, entry.mcVersion, gradleArgs);
        runGradle(repo, PAPERCLIP_TASK, version, entry.mcVersion, gradleArgs);

        Path built = findPaperclipJar(repo.resolve("build").resolve("libs"));
        if (built == null) {
            built = findPaperclipJar(repo.resolve("MultiPaper-Server").resolve("build").resolve("libs"));
        }
        if (built == null) {
            throw new StarterException("Build completed but no Paperclip jar was found under "
                    + repo.resolve("build").resolve("libs"));
        }

        CacheManager.copy(built, cachedJar);
        cache.writeMarker(version, entry.mcVersion, entry.ref, patchesHash, cachedJar.getFileName().toString());
        System.out.println("[multipaper] Cached Paperclip jar: " + cachedJar);
    }

    private static void launch(Path cachedJar, List<String> serverArgs) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(javaBinary().toString());
        command.add("-jar");
        command.add(cachedJar.toString());
        command.addAll(serverArgs);

        System.out.println("[multipaper] Launching: " + String.join(" ", command));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.inheritIO();
        Process process = builder.start();
        int exitCode = process.waitFor();
        System.exit(exitCode);
    }

    private static void runGradle(Path repo, String task, String version, String mcVersion, List<String> gradleArgs)
            throws IOException, InterruptedException {
        List<String> command = gradleCommand(repo, task, version, mcVersion, gradleArgs);
        System.out.println("[multipaper] Running: " + displayGradleCommand(task, version, mcVersion, gradleArgs));

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(repo.toFile());
        builder.inheritIO();
        Process process = builder.start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new StarterException("Gradle task " + task + " failed with exit code " + exitCode);
        }
    }

    private static List<String> gradleCommand(Path repo, String task, String version, String mcVersion,
            List<String> gradleArgs) {
        List<String> command = new ArrayList<>();
        command.add(repo.resolve(isWindows() ? "gradlew.bat" : "gradlew").toString());
        command.add(task);
        command.add("-PpurpurVersion=" + version);
        // The generated MultiPaper-Server build reads mcVersion as a Gradle
        // property; the root build only exposes it as an extra, so pass it too.
        command.add("-PmcVersion=" + mcVersion);
        command.add("--console=plain");
        command.addAll(gradleArgs);
        return command;
    }

    private static String displayGradleCommand(String task, String version, String mcVersion, List<String> gradleArgs) {
        StringBuilder builder = new StringBuilder("./gradlew ")
                .append(task)
                .append(" -PpurpurVersion=").append(version)
                .append(" -PmcVersion=").append(mcVersion)
                .append(" --console=plain");
        for (String arg : gradleArgs) {
            builder.append(' ').append(arg.contains(" ") ? "\"" + arg + "\"" : arg);
        }
        return builder.toString();
    }

    private static String displayLaunchCommand(Path cachedJar, List<String> serverArgs) {
        StringBuilder builder = new StringBuilder("java -jar ").append(cachedJar);
        for (String arg : serverArgs) {
            builder.append(' ').append(arg);
        }
        return builder.toString();
    }

    static Path findPaperclipJar(Path libs) throws IOException {
        if (!Files.isDirectory(libs)) {
            return null;
        }
        List<Path> jars = new ArrayList<>();
        try (Stream<Path> stream = Files.list(libs)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        return name.endsWith(".jar") && !name.endsWith("-sources.jar") && !name.endsWith("-javadoc.jar");
                    })
                    .forEach(jars::add);
        }
        if (jars.isEmpty()) {
            return null;
        }

        for (Path jar : jars) {
            String name = jar.getFileName().toString().toLowerCase(Locale.ROOT);
            if (name.contains("paperclip") && name.contains("reobf")) {
                return jar;
            }
        }
        for (Path jar : jars) {
            String name = jar.getFileName().toString().toLowerCase(Locale.ROOT);
            if (name.contains("paperclip")) {
                return jar;
            }
        }
        if (jars.size() == 1) {
            return jars.get(0);
        }

        Path newest = null;
        FileTime newestTime = null;
        for (Path jar : jars) {
            FileTime time = Files.getLastModifiedTime(jar);
            if (newestTime == null || time.compareTo(newestTime) > 0) {
                newestTime = time;
                newest = jar;
            }
        }
        return newest;
    }

    private static Path javaBinary() {
        return Paths.get(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /**
     * True if the directory is missing or contains no non-hidden entries. The
     * empty per-version patch folders are kept in git via a {@code .gitkeep}
     * file, which must not count as an actual patch.
     */
    static boolean isEffectivelyEmpty(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return true;
        }
        try (Stream<Path> stream = Files.list(directory)) {
            return stream.noneMatch(path -> !path.getFileName().toString().startsWith("."));
        }
    }

    // ------------------------------------------------------------------
    // CLI
    // ------------------------------------------------------------------

    private static final class Options {
        String purpurVersion;
        boolean listVersions;
        boolean showVersion;
        boolean noBuild;
        boolean rebuild;
        boolean noLaunch;
        boolean printCommand;
        boolean help;
        final List<String> gradleArgs = new ArrayList<>();
        final List<String> serverArgs = new ArrayList<>();

        static Options parse(String[] args) {
            Options options = new Options();
            String extraGradleArgs = System.getenv("MULTIPAPER_GRADLE_ARGS");
            if (extraGradleArgs != null && !extraGradleArgs.isBlank()) {
                for (String line : extraGradleArgs.split("\\R")) {
                    if (!line.isBlank()) {
                        options.gradleArgs.add(line.trim());
                    }
                }
            }
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if (arg.equals("--")) {
                    for (int j = i + 1; j < args.length; j++) {
                        options.serverArgs.add(args[j]);
                    }
                    break;
                } else if (arg.equals("--help") || arg.equals("-h")) {
                    options.help = true;
                } else if (arg.equals("--list-versions") || arg.equals("--list")) {
                    options.listVersions = true;
                } else if (arg.equals("--show-version")) {
                    options.showVersion = true;
                } else if (arg.equals("--no-build")) {
                    options.noBuild = true;
                } else if (arg.equals("--rebuild")) {
                    options.rebuild = true;
                } else if (arg.equals("--no-launch") || arg.equals("--prepare-only")) {
                    options.noLaunch = true;
                } else if (arg.equals("--print-command")) {
                    options.printCommand = true;
                } else if (arg.equals("--purpur-version")) {
                    if (i + 1 >= args.length) {
                        throw new StarterException("--purpur-version requires a value, e.g. --purpur-version=1.20.6");
                    }
                    options.purpurVersion = args[++i];
                } else if (arg.equals("--gradle-arg")) {
                    if (i + 1 >= args.length) {
                        throw new StarterException("--gradle-arg requires a value");
                    }
                    options.gradleArgs.add(args[++i]);
                } else if (arg.startsWith("--gradle-arg=")) {
                    options.gradleArgs.add(arg.substring("--gradle-arg=".length()));
                } else if (arg.startsWith("--purpur-version=")) {
                    options.purpurVersion = arg.substring("--purpur-version=".length());
                } else if (arg.startsWith("-Dmultipaper.purpurVersion=")) {
                    // Accept the system-property form as a CLI argument too, so
                    // it keeps working when the starter is invoked through the
                    // ./multipaper launcher (which cannot inject -D flags).
                    options.purpurVersion = arg.substring("-Dmultipaper.purpurVersion=".length());
                } else {
                    options.serverArgs.add(arg);
                }
            }
            return options;
        }
    }

    private static void printVersions(VersionRegistry registry) {
        System.out.println("Available Purpur versions:");
        for (String id : registry.ids()) {
            VersionRegistry.Entry entry = registry.get(id);
            System.out.printf("  %-8s  Minecraft %-9s  %-10s  %s%n",
                    entry.id, entry.mcVersion, entry.channel, entry.branch);
        }
    }

    private static void printHelp() {
        System.out.println("Usage: ./multipaper [starter options] [-- server options]");
        System.out.println();
        System.out.println("A version-independent starter for MultiPaper. It selects a Purpur");
        System.out.println("version, builds the matching patched server into ~/.multipaper/");
        System.out.println("<version>/ and launches it.");
        System.out.println();
        System.out.println("Starter options:");
        System.out.println("  --purpur-version=<v>   Purpur version to build and run (highest priority)");
        System.out.println("  --list-versions        List the supported Purpur versions and exit");
        System.out.println("  --show-version         Show the resolved version and its pinned ref");
        System.out.println("  --no-build             Do not build; use the cached jar (fail if missing)");
        System.out.println("  --rebuild              Force a rebuild even if the cache is up to date");
        System.out.println("  --no-launch            Prepare the jar but do not start the server");
        System.out.println("  --prepare-only         Alias for --no-launch");
        System.out.println("  --print-command        Print the commands that would run, then exit");
        System.out.println("  --gradle-arg=<arg>     Extra argument passed to the Gradle build (repeatable,");
        System.out.println("                         also read from $MULTIPAPER_GRADLE_ARGS, one per line)");
        System.out.println("  --help, -h             Show this help");
        System.out.println();
        System.out.println("Version resolution order:");
        System.out.println("  --purpur-version > -Dmultipaper.purpurVersion > $MULTIPAPER_PURPUR_VERSION");
        System.out.println("  > multipaper.properties (purpurVersion=...) > interactive prompt");
        System.out.println();
        System.out.println("Arguments not recognised as starter options (or after --) are forwarded");
        System.out.println("to the server.");
    }
}
