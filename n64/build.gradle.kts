import kurtos.BuildTestRom

plugins {
    id("kurtos-benchmarked")
}

kotlin {
    linuxX64 {
        compilations["main"].cinterops {
            val rdpshader by creating {
                definitionFile = file("src/nativeInterop/cinterop/rdpshader.def")
                packageName = "rdpshader"
                compilerOpts("-I${rootProject.file("shaders").absolutePath}")
            }
        }
    }
}

val nemu64Rom = layout.buildDirectory.file("testroms/nemu64-test.z64")

val buildNemu64Test by tasks.registering(BuildTestRom::class) {
    group = "kurtos"
    description = "Build the nemu64-test ROM from the third_party/testroms/nemu64-test submodule"

    sourceDir.set(rootProject.layout.projectDirectory.dir("third_party/testroms/nemu64-test"))
    features.set(emptyList<String>())
    rom.set(nemu64Rom)
}

tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest>().configureEach {
    dependsOn(buildNemu64Test)

    inputs.files(nemu64Rom)
        .withPropertyName("nemu64Rom")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    environment("KURTOS_JIT", "1")
    environment("KURTOS_N64TESTS", rootProject.file("third_party/testroms/peterlemon-n64").absolutePath)
    environment("KURTOS_NEMU64", nemu64Rom.get().asFile.absolutePath)
    providers.environmentVariablesPrefixedBy("KURTOS_").get().forEach { (name, value) ->
        environment(name, value)
    }
}
