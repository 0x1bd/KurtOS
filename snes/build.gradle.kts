import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType

plugins {
    kotlin("multiplatform")
}

kotlin {
    linuxX64 {
        binaries {
            test(listOf(NativeBuildType.RELEASE))
        }

        testRuns.create("release") {
            setExecutionSourceFrom(binaries.getTest(NativeBuildType.RELEASE))
        }
    }

    sourceSets {
        val linuxX64Main by getting {
            dependencies {
                implementation(project(":kapi"))
            }
        }

        val linuxX64Test by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest>().configureEach {
    environment("KURTOS_TESTROMS", rootProject.file("third_party/testroms").absolutePath)
}

tasks.named<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest>("linuxX64ReleaseTest") {
    environment("KURTOS_BENCH", "1")
}
