plugins {
    id("kurtos-native")
}

val generateBuildInfo by tasks.registering {
    val output = layout.buildDirectory.dir("generated/buildinfo")
    val value = project.version.toString()

    inputs.property("version", value)
    outputs.dir(output)

    doLast {
        val file = output.get().file("frontend/BuildInfo.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            package frontend

            object BuildInfo {
                const val VERSION = "$value"
            }

            """.trimIndent(),
        )
    }
}

kotlin {
    sourceSets {
        val linuxX64Main by getting {
            dependencies {
                implementation(project(":kapi"))
                implementation(project(":shell"))
                implementation(project(":gameboy"))
                implementation(project(":gba"))
                implementation(project(":snes"))
                implementation(project(":n64"))
            }
        }

        val linuxX64Test by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

kotlin.sourceSets.matching { it.name == "nativeMain" }.configureEach {
    kotlin.srcDir(generateBuildInfo)
}
