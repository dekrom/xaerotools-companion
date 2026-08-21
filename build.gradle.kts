plugins {
    id("dev.kikugie.loom-back-compat")
}

val modId = property("mod.id") as String
val modVersion = property("mod.version") as String

version = "$modVersion+${sc.current.version}"
base.archivesName = modId

val requiredJava: JavaVersion = when {
    sc.current.parsed >= "26.1" -> JavaVersion.VERSION_25
    else -> JavaVersion.VERSION_21
}

repositories {
    maven("https://maven.meteordev.org/releases") { name = "meteor-maven" }
    maven("https://maven.meteordev.org/snapshots") { name = "meteor-maven-snapshots" }
}

dependencies {
    minecraft("com.mojang:minecraft:${sc.current.version}")
    // Applies Mojang mappings on the obfuscated (1.21.x) versions; no-op on 26.1+.
    loomx.applyMojangMappings()

    // `mod{...}` configurations even on 26.1+ — loom-back-compat converts them.
    modImplementation("net.fabricmc:fabric-loader:${property("deps.fabric_loader")}")
    modImplementation("meteordevelopment:meteor-client:${sc.current.version}-SNAPSHOT")
}

java {
    withSourcesJar()
    targetCompatibility = requiredJava
    sourceCompatibility = requiredJava

    toolchain {
        languageVersion = JavaLanguageVersion.of(requiredJava.majorVersion)
    }
}

tasks {
    processResources {
        val props = mapOf(
            "version" to modVersion,
            "minecraft" to (sc.properties["mod.mc_compat"] as String),
            "java" to requiredJava.majorVersion,
        )

        inputs.properties(props)
        filesMatching("fabric.mod.json") { expand(props) }
    }

    jar {
        from(rootProject.file("LICENSE")) {
            rename { "${it}_$modId" }
        }
    }

    withType<JavaCompile>().configureEach {
        options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:unchecked"))
    }

    register<Copy>("buildAndCollect") {
        group = "build"
        description = "Builds this version's jars and copies them to build/libs/{mod version}/ at the root"

        inputs.property("version", modVersion)
        from(loomx.modJar.flatMap { it.archiveFile }, loomx.modSourcesJar.flatMap { it.archiveFile })
        into(rootProject.layout.buildDirectory.file("libs/$modVersion"))
        dependsOn("build")
    }
}
