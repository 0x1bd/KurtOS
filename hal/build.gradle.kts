plugins {
    id("kurtos-native")
}

kotlin {
    linuxX64 {
        compilations["main"].cinterops {
            val mmio by creating {
                definitionFile = file("src/nativeInterop/cinterop/mmio.def")
                packageName = "mmio"
            }
        }
    }
}
