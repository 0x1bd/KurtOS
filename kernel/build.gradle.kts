plugins {
    id("kurtos-native")
}

kotlin {
    linuxX64 {
        binaries {
            staticLib("kurtos") {
                freeCompilerArgs = freeCompilerArgs + listOf(
                    "-Xbinary=gc=stwms",
                    "-Xbinary=gcMarkSingleThreaded=true",
                    "-Xbinary=gcSchedulerType=adaptive",
                )
                freeCompilerArgs = freeCompilerArgs + if (buildType.name == "RELEASE") "-opt" else "-g"
            }
        }
    }

    sourceSets {
        val linuxX64Main by getting {
            dependencies {
                implementation(project(":hal"))
                implementation(project(":hal-x86"))
                implementation(project(":kapi"))
                implementation(project(":shell"))
                implementation(project(":frontend"))
            }
        }
    }
}
