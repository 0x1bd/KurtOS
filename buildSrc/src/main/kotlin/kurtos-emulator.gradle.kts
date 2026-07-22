import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest

plugins {
    id("kurtos-native")
}

kotlin {
    sourceSets {
        val linuxX64Main by getting {
            dependencies {
                implementation(project(":kapi"))
            }
        }

        val linuxX64Test by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(project(":testkit"))
            }
        }
    }
}

val testRoms = rootProject.layout.projectDirectory.dir("third_party/testroms")

tasks.withType<KotlinNativeTest>().configureEach {
    environment("KURTOS_TESTROMS", testRoms.asFile.absolutePath)

    inputs.files(
        rootProject.fileTree(testRoms) {
            include("**/*.gb", "**/*.gbc", "**/*.gba", "**/*.sfc", "**/*.smc", "**/*.n64", "**/*.N64", "**/*.z64")
        },
    ).withPropertyName("testRoms").withPathSensitivity(PathSensitivity.RELATIVE)
}
