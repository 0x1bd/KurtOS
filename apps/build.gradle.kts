plugins {
    kotlin("multiplatform")
}

kotlin {
    linuxX64()

    sourceSets {
        val linuxX64Main by getting {
            dependencies {
                implementation(project(":kapi"))
                implementation(project(":shell"))
                implementation(project(":gameboy"))
                implementation(project(":gba"))
            }
        }
    }
}
