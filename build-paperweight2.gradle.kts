import java.net.URI
import java.util.Properties

plugins {
    java
    `maven-publish`
    id("io.papermc.paperweight.patcher")
}

// ---------------------------------------------------------------------------
// paperweight 2.x build script.
//
// This script is selected for Purpur versions whose upstream uses paperweight
// 2.x (currently 1.21.11 and newer). It forks PurpurMC/Purpur, runs Purpur's
// own downstream build, and then applies the MultiPaper patch sets from
// patches/<purpurVersion>/{api,server}.
//
// The 1.20.x versions keep using the legacy build.gradle.kts / paperweight 1.x.
// ---------------------------------------------------------------------------

val purpurVersions = Properties().apply {
    file("purpur-versions.properties").inputStream().use { load(it) }
}

val purpurVersionId = providers.gradleProperty("purpurVersion").getOrElse("1.20.1")
val purpurVersionMcVersion = purpurVersions.getProperty("$purpurVersionId.mcVersion")
    ?: error("Unknown purpurVersion \"$purpurVersionId\" in purpur-versions.properties")
val purpurRef = purpurVersions.getProperty("$purpurVersionId.ref")
    ?: error("No ref configured for purpurVersion \"$purpurVersionId\"")

logger.lifecycle("MultiPaper: building against Purpur $purpurVersionId (Minecraft $purpurVersionMcVersion)")

allprojects {
    apply(plugin = "java")
    apply(plugin = "maven-publish")

    group = "puregero.multipaper"
    version = "$purpurVersionMcVersion-R0.1-SNAPSHOT"

    extra["mcVersion"] = purpurVersionMcVersion
    extra["apiVersion"] = purpurVersionMcVersion
    extra["purpurVersion"] = purpurVersionId

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }
}

subprojects {
    tasks.withType<JavaCompile> {
        options.encoding = Charsets.UTF_8.name()
        options.release.set(21)
        options.isFork = true
    }
    tasks.withType<Javadoc> {
        options.encoding = Charsets.UTF_8.name()
    }
    tasks.withType<ProcessResources> {
        filteringCharset = Charsets.UTF_8.name()
    }

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://jitpack.io")
    }
}

paperweight {
    upstreams.register("purpur") {
        repo = "https://github.com/PurpurMC/Purpur.git"
        ref = purpurRef

        // MultiPaper-Server/API build scripts are generated from Purpur's
        // (which are themselves generated from Paper's) build scripts.
        patchFile {
            path = "purpur-server/build.gradle.kts"
            outputFile = file("MultiPaper-Server/build.gradle.kts")
            patchFile = file("build-data/multipaper-server.build.gradle.kts.patch")
        }
        patchFile {
            path = "purpur-api/build.gradle.kts"
            outputFile = file("MultiPaper-API/build.gradle.kts")
            patchFile = file("build-data/multipaper-api.build.gradle.kts.patch")
        }

        // The API is split between Purpur's patched Paper API ("paper-api")
        // and Purpur's own API sources ("purpur-api").
        patchRepo("paperApi") {
            upstreamPath = "paper-api"
            patchesDir = file("patches/$purpurVersionId/api")
            outputDir = file("paper-api")
        }
        patchDir("purpurApi") {
            upstreamPath = "purpur-api"
            excludes = listOf("build.gradle.kts", "build.gradle.kts.patch", "paper-patches")
            patchesDir = file("patches/$purpurVersionId/api/purpur")
            outputDir = file("purpur-api")
        }
    }
}

// paperweight 2.x only provides applyAllPatches at the root; keep the
// historical applyPatches entry point working.
tasks.register("applyPatches") {
    group = "patching"
    description = "Applies the MultiPaper patches onto Purpur."
    dependsOn("applyAllPatches")
}

tasks.register("purpurRefLatest") {
    val purpurVersionData = project.file("purpur-versions.properties")
    val purpurBranch = purpurVersions.getProperty("$purpurVersionId.branch")

    doLast {
        val json = URI("https://api.github.com/repos/PurpurMC/Purpur/commits/$purpurBranch")
            .toURL().readText()
        val latest = Regex("\"sha\"\\s*:\\s*\"([0-9a-f]{40})\"").find(json)?.groupValues?.get(1)
            ?: error("Could not read the latest commit of $purpurBranch")
        val updated = purpurVersionData.readText()
            .replace(Regex("(?m)^\\Q$purpurVersionId\\E\\.ref=.*$"), "$purpurVersionId.ref=$latest")
        purpurVersionData.writeText(updated)
        logger.lifecycle("Updated $purpurVersionId.ref to the latest commit of $purpurBranch")
    }
}

tasks.register("printPurpurVersion") {
    doLast {
        println("purpurVersion=$purpurVersionId mcVersion=$purpurVersionMcVersion")
    }
}
