plugins {
    kotlin("multiplatform")
}

kotlin {
    linuxX64 {
        binaries {
            staticLib("kurtos") {
                freeCompilerArgs = freeCompilerArgs + listOf(
                    "-Xbinary=gc=stwms",
                    "-Xbinary=gcMarkSingleThreaded=true",
                    "-Xbinary=gcSchedulerType=manual",
                    "-g",
                )
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
                implementation(project(":frontend"))
            }
        }
    }
}
