plugins {
    java
    id("com.github.johnrengelman.shadow")
}

// The starter is deliberately version-independent and contains no Minecraft
// code. Build it with the JDK Gradle itself is running on (Java 21 in this
// repository), but emit Java 17 bytecode so the resulting jar can run on any
// modern JVM regardless of the Purpur version it is asked to prepare.
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(JavaVersion.current().majorVersion.toInt()))
    }
}

tasks.withType<JavaCompile> {
    options.release.set(17)
}

val mainClass = "puregero.multipaper.starter.MultiPaperStarter"

// The starter is version-independent, so keep its artifact names stable
// instead of embedding the version of whichever Purpur target happened to be
// selected when it was built.
tasks.jar {
    archiveFileName.set("multipaper-starter.jar")
    manifest {
        attributes("Main-Class" to mainClass)
    }
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveFileName.set("multipaper-starter-all.jar")
    manifest {
        attributes("Main-Class" to mainClass)
    }
}
