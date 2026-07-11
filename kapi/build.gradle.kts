plugins {
    kotlin("multiplatform")
}

kotlin {
    linuxX64()

    sourceSets {
        val linuxX64Main by getting
    }
}
