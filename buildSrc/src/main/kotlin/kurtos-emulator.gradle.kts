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
            }
        }
    }
}

tasks.withType<KotlinNativeTest>().configureEach {
    environment("KURTOS_TESTROMS", rootProject.file("third_party/testroms").absolutePath)
}
