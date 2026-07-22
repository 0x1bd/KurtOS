import org.jetbrains.kotlin.gradle.tasks.CInteropProcess
import org.jetbrains.kotlin.gradle.tasks.KotlinNativeLink

plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

repositories {
    mavenCentral()
}

val speziObjects = rootProject.layout.buildDirectory.dir("shaders-spezi/cpu")

kotlin {
    linuxX64 {
        binaries.all {
            linkerOpts(speziObjects.get().asFile.resolve("rdpspan.o").absolutePath)
        }
    }
}

tasks.withType<KotlinNativeLink>().configureEach {
    dependsOn(":compileSpeziShaders")
    inputs.dir(speziObjects)
        .withPropertyName("speziObjects")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

tasks.withType<CInteropProcess>().configureEach {
    val moduleInterop = layout.projectDirectory.dir("src/nativeInterop")
    if (moduleInterop.asFile.isDirectory) {
        inputs.dir(moduleInterop)
            .withPropertyName("nativeInteropSources")
            .withPathSensitivity(PathSensitivity.RELATIVE)
    }

    val sharedShaders = rootProject.layout.projectDirectory.dir("shaders")
    if (sharedShaders.asFile.isDirectory) {
        inputs.dir(sharedShaders)
            .withPropertyName("sharedShaderSources")
            .withPathSensitivity(PathSensitivity.RELATIVE)
    }
}
