import org.jetbrains.kotlin.gradle.tasks.CInteropProcess

plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

repositories {
    mavenCentral()
}

kotlin {
    linuxX64()
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
