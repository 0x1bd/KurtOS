import java.io.File

plugins {
    base
}

val runtimeSrcDir = layout.projectDirectory.dir("src/main")
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

val sseFreeSources = setOf("boot.c", "isr.c", "smp.c")

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

tasks.register("runtimeObjects") {
    group = "kurtos"
    description = "Build freestanding runtime objects"
    dependsOn(compileTasks)
}
