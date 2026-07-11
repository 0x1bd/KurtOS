plugins {
    kotlin("multiplatform")
}

kotlin {
    linuxX64 {
        compilations["main"].apply {
            cinterops {
                val mmio by creating {
                    definitionFile = file("src/nativeInterop/cinterop/mmio.def")
                    packageName = "mmio"
                }
            }
        }
    }

    sourceSets {
        val linuxX64Main by getting
    }
}
