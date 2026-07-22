plugins {
    base
    id("kurtos-image")
}

group = "org.kvxd.kurtos"
version = "0.1.0"

allprojects {
    group = rootProject.group
    version = rootProject.version

    repositories {
        mavenCentral()
    }
}
