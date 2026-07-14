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
    testLogging {
        showStandardStreams = true
        events("passed", "failed")
    }
    environment("KURTOS_JIT", "1")
    environment("KURTOS_TESTROMS", rootProject.file("third_party/testroms").absolutePath)
    environment("KURTOS_N64TESTS", rootProject.file("third_party/testroms/n64").absolutePath)
    environment(
        "KURTOS_N64SYSTEMTEST",
        rootProject.file("third_party/testroms/n64/n64-systemtest.z64").absolutePath,
    )
    providers.environmentVariablesPrefixedBy("KURTOS_").get().forEach { (name, value) ->
        environment(name, value)
    }
}

tasks.named<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest>("linuxX64ReleaseTest") {
    environment("KURTOS_BENCH", "1")
}
