plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

repositories {
    mavenCentral()
}

kotlin {
    sourceSets.all {
        languageSettings.optIn("kotlinx.cinterop.ExperimentalForeignApi")
    }

    linuxX64 {
        binaries {
            executable("spezi") {
                entryPoint = "spezi.main"
                freeCompilerArgs = freeCompilerArgs + if (buildType.name == "RELEASE") "-opt" else "-g"
            }
        }
    }

    sourceSets {
        val linuxX64Test by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}
