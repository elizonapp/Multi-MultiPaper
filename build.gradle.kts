import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import java.util.Properties

plugins {
    java
    `maven-publish`
    id("com.github.johnrengelman.shadow") version "8.1.1"
    id("io.papermc.paperweight.patcher") version "1.7.1"
}

buildscript {
    repositories {
        gradlePluginPortal()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
    dependencies {
        classpath("com.github.johnrengelman:shadow:8.1.1")
    }
}

// ---------------------------------------------------------------------------
// Version selection
//
// The upstream Purpur version MultiPaper is built against is chosen by the
// "purpurVersion" property in gradle.properties (or via -PpurpurVersion=...).
// On a first start you can pick a version interactively with ./setup.sh, which
// stores your selection in gradle.properties. Everything else (mcVersion,
// version and purpurRef) is derived below from purpur-versions.properties.
// ---------------------------------------------------------------------------
val purpurVersions = Properties().apply {
    file("purpur-versions.properties").inputStream().use { load(it) }
}

val purpurVersionId = providers.gradleProperty("purpurVersion").getOrElse("1.20.1")
val availableVersions = purpurVersions.getProperty("purpur.version.list")
    ?: error("purpur.version.list is missing from purpur-versions.properties")
val purpurVersionMcVersion = purpurVersions.getProperty("$purpurVersionId.mcVersion")
    ?: error("Unknown purpurVersion \"$purpurVersionId\". Select one of: $availableVersions (e.g. run ./setup.sh)")

// 1.20.1 targets Java 17; 1.20.6 and newer target Java 21.
val multiPaperJavaVersion = if (purpurVersionMcVersion == "1.20.1") 17 else 21

// tiny-remapper 0.8.6 ships ASM 9.3, which cannot read Java 21 class files
// (major version 65). Use a newer tiny-remapper (ASM 9.x with Java 21+ support)
// whenever the target is Java 21; keep 0.8.6 for the Java 17 builds.
val tinyRemapperVersion = if (multiPaperJavaVersion >= 21) "0.10.2" else "0.8.6"

logger.lifecycle("MultiPaper: building against Purpur $purpurVersionId (Minecraft $purpurVersionMcVersion)")

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") {
        content { onlyForConfigurations(PAPERCLIP_CONFIG) }
    }
}

dependencies {
    remapper("net.fabricmc:tiny-remapper:$tinyRemapperVersion:fat")
    decompiler("net.minecraftforge:forgeflower:2.0.627.2")
    paperclip("io.papermc:paperclip:3.0.3")
}

allprojects {
    apply(plugin = "java")
    apply(plugin = "maven-publish")

    group = "puregero.multipaper"
    version = "$purpurVersionMcVersion-R0.1-SNAPSHOT"

    extra["mcVersion"] = purpurVersionMcVersion
    extra["purpurVersion"] = purpurVersionId

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(multiPaperJavaVersion))
        }
    }
}

subprojects {
    tasks.withType<JavaCompile> {
        options.encoding = Charsets.UTF_8.name()
        options.release.set(multiPaperJavaVersion)
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
        maven("https://oss.sonatype.org/content/groups/public/")
        maven("https://ci.emc.gs/nexus/content/groups/aikar/")
        maven("https://repo.aikar.co/content/groups/aikar")
        maven("https://repo.md-5.net/content/repositories/releases/")
        maven("https://hub.spigotmc.org/nexus/content/groups/public/")
        maven("https://jitpack.io")
    }
}

paperweight {
    serverProject.set(project(":multipaper-server"))

    remapRepo.set("https://repo.papermc.io/repository/maven-public/")
    decompileRepo.set("https://repo.papermc.io/repository/maven-public/")

    useStandardUpstream("Purpur") {
        url.set(github("PurpurMC", "Purpur"))
        ref.set(purpurVersions.getProperty("$purpurVersionId.ref"))

        withStandardPatcher {
            apiSourceDirPath.set("Purpur-API")
            // Each Purpur version has its own patch set under patches/<version>/.
            // The set that gets applied is chosen by the selected purpurVersion.
            apiPatchDir.set(layout.projectDirectory.dir("patches/$purpurVersionId/api"))
            apiOutputDir.set(layout.projectDirectory.dir("MultiPaper-API"))

            serverSourceDirPath.set("Purpur-Server")
            serverPatchDir.set(layout.projectDirectory.dir("patches/$purpurVersionId/server"))
            serverOutputDir.set(layout.projectDirectory.dir("MultiPaper-Server"))
        }
    }

    tasks.register("purpurRefLatest") {
        // Update the pinned commit of the selected Purpur version in
        // purpur-versions.properties to the latest commit of its branch.
        val purpurVersionData = project.file("purpur-versions.properties");
        val tempDir = layout.cacheDir("purpurRefLatest");
        val purpurBranch = purpurVersions.getProperty("$purpurVersionId.branch");

        doFirst {
            data class GithubCommit(
                    val sha: String
            )

            val purpurLatestCommitJson = layout.cache.resolve("purpurLatestCommit.json");
            download.get().download("https://api.github.com/repos/PurpurMC/Purpur/commits/$purpurBranch", purpurLatestCommitJson);
            val purpurLatestCommit = gson.fromJson<paper.libs.com.google.gson.JsonObject>(purpurLatestCommitJson)["sha"].asString;

            copy {
                from(purpurVersionData)
                into(tempDir)
                filter { line: String ->
                    line.replace(Regex("^\\Q$purpurVersionId.ref=.*\\E$"), "$purpurVersionId.ref=$purpurLatestCommit")
                }
            }
        }

        doLast {
            copy {
                from(tempDir.file("purpur-versions.properties"))
                into(purpurVersionData.parent)
            }
            logger.lifecycle("Updated $purpurVersionId.ref to the latest commit of $purpurBranch")
        }
    }
}

// Print out which Purpur version this build is configured for.
tasks.register("printPurpurVersion") {
    doLast {
        println("purpurVersion=$purpurVersionId mcVersion=$purpurVersionMcVersion")
    }
}

tasks.generateDevelopmentBundle {
    apiCoordinates.set("puregero.multipaper:MultiPaper-API")
    libraryRepositories.set(
        listOf(
            "https://repo.maven.apache.org/maven2/",
            "https://repo.papermc.io/repository/maven-public/",
            "https://jitpack.io"
        )
    )
}
publishing {
    publications.create<MavenPublication>("devBundle") {
        artifact(tasks.generateDevelopmentBundle) {
            artifactId = "dev-bundle"
        }
    }
}