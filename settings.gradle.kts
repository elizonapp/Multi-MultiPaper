import java.util.Locale
import java.util.Properties

pluginManagement {
    // The pluginManagement block is compiled separately from the rest of the
    // settings script, so version selection has to be resolved in here.
    val purpurVersionId = providers.gradleProperty("purpurVersion").getOrElse("1.20.1")
    val purpurVersions = java.util.Properties().apply {
        val registry = java.io.File("purpur-versions.properties")
        if (registry.exists()) registry.inputStream().use { load(it) }
    }

    // 1.20.x is built against Purpur's paperweight 1.x; 1.21.11 and newer use
    // Purpur's paperweight 2.x (which requires Gradle 9). The exact paperweight
    // 2.x version differs per Purpur version and is listed in the registry.
    val paperweightVersion = purpurVersions.getProperty("$purpurVersionId.paperweight")
        ?: if (purpurVersionId == "1.20.1" || purpurVersionId == "1.20.6") "1.7.1" else "2.0.0-beta.19"

    repositories {
        gradlePluginPortal()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "io.papermc.paperweight.patcher") {
                useVersion(paperweightVersion)
            }
            if (requested.id.id == "com.github.johnrengelman.shadow" && requested.version == null) {
                useVersion("8.1.1")
            }
        }
    }
}

// Java 25 toolchains (needed by the 26.x versions) are provisioned
// automatically by the foojay resolver, just like in Purpur's own build.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "multipaper"

val purpurVersionId = providers.gradleProperty("purpurVersion").getOrElse("1.20.1")
val purpurVersions = Properties().apply {
    val registry = file("purpur-versions.properties")
    if (registry.exists()) registry.inputStream().use { load(it) }
}
val selectedPaperweight = purpurVersions.getProperty("$purpurVersionId.paperweight")
    ?: if (purpurVersionId == "1.20.1" || purpurVersionId == "1.20.6") "1.7.1" else "2.0.0-beta.19"
val legacyToolchain = selectedPaperweight.startsWith("1.")

// Paperweight 2.x has its own build script (build-paperweight2.gradle.kts);
// the legacy paperweight 1.x flow keeps using build.gradle.kts.
rootProject.buildFileName = if (legacyToolchain) "build.gradle.kts" else "build-paperweight2.gradle.kts"

// The two toolchains (and, for the legacy server build, each Purpur version)
// generate build scripts in the same project directories, so purge the
// generated projects when switching to a different target.
val currentToolchain = "${if (legacyToolchain) "legacy" else "paperweight2"}:$purpurVersionId"
val toolchainMarker = file(".multipaper-toolchain")
val previousToolchain = if (toolchainMarker.exists()) toolchainMarker.readText().trim() else ""
if (previousToolchain != currentToolchain) {
    file("MultiPaper-API").deleteRecursively()
    file("MultiPaper-Server").deleteRecursively()
    // The paperweight 1.x and 2.x upstream checkouts reuse the same cache
    // directory on case-insensitive filesystems, so start from a clean slate.
    file(".gradle/caches/paperweight/upstreams").deleteRecursively()
    toolchainMarker.writeText(currentToolchain)
}

val projectNames = if (legacyToolchain) {
    listOf(
        "MultiPaper-MasterMessagingProtocol",
        "MultiPaper-API",
        "MultiPaper-Server",
        "MultiPaper-Master",
        "MultiPaper-Starter",
    )
} else {
    // MultiPaper-Master / MultiPaper-Starter apply the (Gradle 8 only) shadow
    // plugin; they are not part of the paperweight 2.x server build.
    listOf(
        "MultiPaper-MasterMessagingProtocol",
        "MultiPaper-API",
        "MultiPaper-Server",
    )
}

for (name in projectNames) {
    val projName = name.lowercase(Locale.ENGLISH)
    val dir = file(name)
    // Gradle 9 requires the project directory to exist during configuration.
    if (!dir.exists()) dir.mkdirs()
    include(projName)
    findProject(":$projName")!!.projectDir = dir
}
