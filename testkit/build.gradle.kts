import kurtos.GenerateTestPaths

plugins {
    id("kurtos-native")
}

val generatedPaths = layout.buildDirectory.dir("generated/testpaths")

val generateTestPaths by tasks.registering(GenerateTestPaths::class) {
    group = "kurtos"
    description = "Generate compile-time paths for the emulator test suites"

    paths.put("TEST_ROMS", rootProject.file("third_party/testroms").absolutePath)
    paths.put("PETERLEMON_N64", rootProject.file("third_party/testroms/peterlemon-n64").absolutePath)
    paths.put("NEMU64_ROM", project(":n64").layout.buildDirectory.file("testroms/nemu64-test.z64").get().asFile.absolutePath)
    paths.put("REPORTS", layout.buildDirectory.dir("reports/testkit").get().asFile.absolutePath)
    outputDir.set(generatedPaths)
}

kotlin.sourceSets.named("linuxX64Main") {
    kotlin.srcDir(generateTestPaths)
}
