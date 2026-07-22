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

tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest>().configureEach {
    environment("KURTOS_JIT", "1")
    environment("KURTOS_N64TESTS", rootProject.file("third_party/testroms/n64").absolutePath)
    environment(
        "KURTOS_N64SYSTEMTEST",
        rootProject.file("third_party/testroms/n64/n64-systemtest.z64").absolutePath,
    )
    providers.environmentVariablesPrefixedBy("KURTOS_").get().forEach { (name, value) ->
        environment(name, value)
    }
}
