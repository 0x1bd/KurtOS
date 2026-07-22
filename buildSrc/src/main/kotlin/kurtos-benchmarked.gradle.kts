import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest

plugins {
    id("kurtos-emulator")
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
}

tasks.withType<KotlinNativeTest>().configureEach {
    testLogging {
        showStandardStreams = true
        events("passed", "failed")
    }
}

tasks.named<KotlinNativeTest>("linuxX64ReleaseTest") {
    environment("KURTOS_BENCH", "1")
}
