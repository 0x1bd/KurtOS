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
    }
}
