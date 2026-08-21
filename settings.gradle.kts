pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        maven("https://maven.kikugie.dev/releases") { name = "KikuGie Releases" }
        maven("https://maven.kikugie.dev/snapshots") { name = "KikuGie Snapshots" }
    }
}

plugins {
    id("dev.kikugie.stonecutter") version "0.9.7"
    // Picks the right loom variant per version: 1.21.x is obfuscated (remap), 26.1+ is not.
    id("dev.kikugie.loom-back-compat") version "0.4.2"
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

stonecutter {
    create(rootProject) {
        // One node per meteor-client artifact on maven.meteordev.org; the
        // 1.21.10 build also covers 1.21.9 (Meteor shipped no 1.21.9 jar).
        versions("1.21.4", "1.21.5", "1.21.6", "1.21.7", "1.21.8", "1.21.10", "1.21.11", "26.1.2", "26.2")
        vcsVersion = "26.2"
    }
}

rootProject.name = "xaerotools-companion"
