import kurtos.CompileSpeziShaders

plugins {
    base
}

val linkerScript = file("linker-x86_64.ld")
val imageBuildType = if (providers.gradleProperty("kurtos.release").orNull == "true") "Release" else "Debug"
val kernelBinaryDir = "kurtos${imageBuildType}Static"
val kernelLinkTask = ":kernel:linkKurtos${imageBuildType}StaticLinuxX64"
val kernelStaticLib = project(":kernel").layout.buildDirectory.file("bin/linuxX64/$kernelBinaryDir/libkurtos.a")
val runtimeObjectsDir = project(":runtime").layout.buildDirectory.dir("objects")
val assetsRoot = layout.projectDirectory.dir("assets")
val limineDir = layout.projectDirectory.dir("third_party/limine")
val diskImage = layout.buildDirectory.file("kurtos.img")
val espImage = layout.buildDirectory.file("esp.img")

val cxxSupportDir = layout.buildDirectory.dir("cxx")

val compileSpeziShaders by tasks.registering(CompileSpeziShaders::class) {
    group = "kurtos"
    description = "Compile shaders/*.spz to gfx902 .kbin blobs and x86-64 objects"
    dependsOn(":spezi:linkSpeziDebugExecutableLinuxX64")

    sourceDir.set(shadersDir)
    compiler.set(project(":spezi").layout.buildDirectory.file("bin/linuxX64/speziDebugExecutable/spezi.kexe"))
    clang.set(clangBinary)
    gpuCpu.set("gfx902")
    optimization.set(2)
    extraArgs.set(emptyList<String>())
    expectedKernarg.set(mapOf("fill" to 8, "gradient" to 20, "rdp" to 44, "rdpspan" to 92))
    gpuDir.set(speziGpuDir)
    cpuDir.set(speziCpuDir)
}

val extractCxxSupport by tasks.registering {
    group = "kurtos"
    description = "Extract the libstdc++ members Kotlin/Native's runtime needs"

    outputs.dir(cxxSupportDir)

    val target = cxxSupportDir.get().asFile

    doLast {
        val archive = ProcessBuilder("gcc", "-print-file-name=libstdc++.a")
            .redirectErrorStream(true)
            .start()
            .inputStream.bufferedReader().readText().trim()

        target.mkdirs()

        val extract = ProcessBuilder("ar", "x", archive, "tree.o", "list.o")
            .directory(target)
            .redirectErrorStream(true)
            .start()
        val output = extract.inputStream.bufferedReader().readText()
        if (extract.waitFor() != 0) {
            throw GradleException("failed to extract libstdc++ members: $output")
        }
    }
}

val linkKurtOS by tasks.registering(Exec::class) {
    group = "kurtos"
    description = "Link runtime objects and the Kotlin kernel static library to build/kurtos.elf"
    dependsOn(":runtime:runtimeObjects", kernelLinkTask, extractCxxSupport, compileSpeziShaders)

    val script = linkerScript
    val kotlinLib = kernelStaticLib.get().asFile
    val runtimeDir = runtimeObjectsDir.get().asFile
    val speziDir = speziCpuDir.get().asFile
    val cxxDir = cxxSupportDir.get().asFile
    val elf = layout.buildDirectory.file("kurtos.elf").get().asFile

    inputs.file(script)
    inputs.file(kotlinLib)
    inputs.dir(runtimeDir)
    inputs.dir(speziDir)
    outputs.file(elf)

    doFirst {
        val runtimeObjects = runtimeDir.walkTopDown()
            .filter { it.isFile && it.extension == "o" }
            .sortedBy { it.relativeTo(runtimeDir).invariantSeparatorsPath }
            .toList()
        if (runtimeObjects.none { it.name == "arch_x86_64_boot.o" }) {
            throw GradleException("runtime boot.o was not produced")
        }
        if (!kotlinLib.isFile) {
            throw GradleException("Kotlin/Native kernel library was not produced: ${kotlinLib.absolutePath}")
        }

        val linkerArgs = mutableListOf(
            "-nostdlib", "-static", "-no-pie",
            "-Wl,-z,max-page-size=0x1000",
            "-Wl,--build-id=none",
            "-Wl,--gc-sections",
            "-T", script.absolutePath,
            "-o", elf.absolutePath,
        )
        linkerArgs.addAll(runtimeObjects.map { it.absolutePath })
        linkerArgs.add(kotlinLib.absolutePath)
        speziDir.listFiles()
            ?.filter { it.extension == "o" }
            ?.sortedBy { it.name }
            ?.forEach { linkerArgs.add(it.absolutePath) }
        cxxDir.listFiles()
            ?.filter { it.extension == "o" }
            ?.sortedBy { it.name }
            ?.forEach { linkerArgs.add(it.absolutePath) }
        linkerArgs.add("-lsupc++")
        linkerArgs.add("-lgcc")
        linkerArgs.add("-lgcc_eh")

        executable = "gcc"
        setArgs(linkerArgs)
    }
}

val shadersDir = layout.projectDirectory.dir("shaders")
val compiledShadersDir = layout.buildDirectory.dir("shaders")
val speziGpuDir = layout.buildDirectory.dir("shaders-spezi/gpu")
val speziCpuDir = layout.buildDirectory.dir("shaders-spezi/cpu")

val clangBinary = providers.gradleProperty("kurtos.clang")
    .orElse(providers.environmentVariable("KURTOS_CLANG"))
    .orElse("clang")

val collectShaders by tasks.registering(Sync::class) {
    group = "kurtos"
    description = "Gather every compiled .kbin blob that ships on the ESP"

    from(compileSpeziShaders.flatMap { it.gpuDir }) { include("*.kbin") }
    into(compiledShadersDir)
}

val buildImage by tasks.registering(Exec::class) {
    group = "kurtos"
    description = "Build the bootable GPT/ESP disk image at build/kurtos.img"
    dependsOn(linkKurtOS, collectShaders)

    inputs.file(layout.buildDirectory.file("kurtos.elf"))
    inputs.file(layout.projectDirectory.file("limine.conf"))
    inputs.dir(assetsRoot).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(compiledShadersDir)
    outputs.file(diskImage)
    outputs.file(espImage)

    executable = "bash"
    args(
        layout.projectDirectory.file("tools/mkimage.sh").asFile.absolutePath,
        layout.buildDirectory.file("kurtos.elf").get().asFile.absolutePath,
        limineDir.asFile.absolutePath,
        assetsRoot.asFile.absolutePath,
        diskImage.get().asFile.absolutePath,
        espImage.get().asFile.absolutePath,
        compiledShadersDir.get().asFile.absolutePath,
    )
}

tasks.named("assemble") {
    dependsOn(buildImage)
}
