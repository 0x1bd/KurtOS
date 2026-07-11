plugins {
    kotlin("multiplatform")
}

kotlin {
    linuxX64 {
        binaries {
            staticLib("kurtos") {
                freeCompilerArgs = freeCompilerArgs + listOf("-Xbinary=gc=noop", "-g")
                if (buildType.name == "RELEASE") {
                    freeCompilerArgs = freeCompilerArgs + "-opt"
                }
            }
        }
    }

    sourceSets {
        val linuxX64Main by getting {
            dependencies {
                implementation(project(":hal"))
                implementation(project(":kapi"))
                implementation(project(":shell"))
                implementation(project(":apps"))
            }
        }
    }
}
