import java.io.File

plugins {
    base
}

val runtimeSrcDir = layout.projectDirectory.dir("src/main")
val generatedAbiDir = layout.buildDirectory.dir("generated/abi")
val objectsDir = layout.buildDirectory.dir("objects")

val baseCFlags = listOf(
    "-ffreestanding",
    "-fno-stack-protector",
    "-fno-stack-clash-protection",
    "-fno-pic",
    "-fno-pie",
    "-mno-red-zone",
    "-mcmodel=kernel",
    "-nostdlib",
    "-O2",
    "-Wall",
)

val noSseFlags = listOf("-mno-80387", "-mno-mmx", "-mno-sse", "-mno-sse2")

val sseFreeSources = setOf("boot.c", "isr.c")

fun flagsFor(source: File): List<String> =
    if (source.name in sseFreeSources) baseCFlags + noSseFlags else baseCFlags

fun taskSuffix(source: File): String =
    source.relativeTo(runtimeSrcDir.asFile)
        .invariantSeparatorsPath
        .replace(Regex("[^A-Za-z0-9]"), "_")
        .replaceFirstChar { it.uppercase() }

fun objectFile(source: File): File =
    objectsDir.get().asFile.resolve(
        source.relativeTo(runtimeSrcDir.asFile)
            .invariantSeparatorsPath
            .replace('/', '_')
            .replace(Regex("\\.(c|S)$"), ".o")
    )

val generateKotlinNativeStubs by tasks.registering(Exec::class) {
    group = "kurtos"
    description = "Generate freestanding Kotlin/Native ABI stubs"

    val script = runtimeSrcDir.file("abi/generate_stubs.py")
    val definitions = runtimeSrcDir.file("abi/kotlin_native_stubs.def")
    val output = generatedAbiDir.map { it.file("kotlin_native_stubs.c") }

    inputs.file(script)
    inputs.file(definitions)
    outputs.file(output)

    doFirst {
        output.get().asFile.parentFile.mkdirs()
    }

    executable = "python3"
    args(script.asFile.absolutePath, definitions.asFile.absolutePath, output.get().asFile.absolutePath)
}

val compileTasks = mutableListOf<TaskProvider<Exec>>()

runtimeSrcDir.asFileTree.matching { include("**/*.c") }.files.sortedBy { it.invariantSeparatorsPath }.forEach { source ->
    val output = objectFile(source)
    compileTasks += tasks.register<Exec>("compileRuntime${taskSuffix(source)}") {
        group = "kurtos"
        description = "Compile ${source.relativeTo(runtimeSrcDir.asFile).invariantSeparatorsPath}"

        inputs.file(source)
        outputs.file(output)

        doFirst {
            output.parentFile.mkdirs()
        }

        executable = "gcc"
        args(flagsFor(source) + listOf("-c", source.absolutePath, "-o", output.absolutePath))
    }
}

runtimeSrcDir.asFileTree.matching { include("**/*.S") }.files.sortedBy { it.invariantSeparatorsPath }.forEach { source ->
    val output = objectFile(source)
    compileTasks += tasks.register<Exec>("compileRuntime${taskSuffix(source)}") {
        group = "kurtos"
        description = "Assemble ${source.relativeTo(runtimeSrcDir.asFile).invariantSeparatorsPath}"

        inputs.file(source)
        outputs.file(output)

        doFirst {
            output.parentFile.mkdirs()
        }

        executable = "gcc"
        args(baseCFlags + noSseFlags + listOf("-nostdinc", "-c", source.absolutePath, "-o", output.absolutePath))
    }
}

val compileKotlinNativeStubs by tasks.registering(Exec::class) {
    group = "kurtos"
    description = "Compile generated Kotlin/Native ABI stubs"
    dependsOn(generateKotlinNativeStubs)

    val source = generatedAbiDir.map { it.file("kotlin_native_stubs.c") }
    val output = objectsDir.map { it.file("abi_kotlin_native_stubs.o") }

    inputs.file(source)
    outputs.file(output)

    doFirst {
        output.get().asFile.parentFile.mkdirs()
    }

    executable = "gcc"
    args(baseCFlags + listOf("-c", source.get().asFile.absolutePath, "-o", output.get().asFile.absolutePath))
}

tasks.register("runtimeObjects") {
    group = "kurtos"
    description = "Build freestanding runtime objects"
    dependsOn(compileTasks)
    dependsOn(compileKotlinNativeStubs)
}
