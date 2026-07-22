import kurtos.CompileShaders

plugins {
    base
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

val extractCxxSupport by tasks.registering {
    group = "kurtos"
    description = "Extract the libstdc++ members Kotlin/Native's runtime needs"

    outputs.dir(cxxSupportDir)

    doLast {
        val archive = ProcessBuilder("gcc", "-print-file-name=libstdc++.a")
            .redirectErrorStream(true)
            .start()
            .inputStream.bufferedReader().readText().trim()

        val target = cxxSupportDir.get().asFile
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
    dependsOn(":runtime:runtimeObjects", kernelLinkTask, extractCxxSupport)

    inputs.file(linkerScript)
    inputs.file(kernelStaticLib)
    inputs.dir(runtimeObjectsDir)
    outputs.file(layout.buildDirectory.file("kurtos.elf"))

    doFirst {
        val runtimeObjects = runtimeObjectsDir.get().asFile.walkTopDown()
            .filter { it.isFile && it.extension == "o" }
            .sortedBy { it.relativeTo(runtimeObjectsDir.get().asFile).invariantSeparatorsPath }
            .toList()
        if (runtimeObjects.none { it.name == "arch_x86_64_boot.o" }) {
            throw GradleException("runtime boot.o was not produced")
        }
        val kotlinLib = kernelStaticLib.get().asFile
        if (!kotlinLib.isFile) {
            throw GradleException("Kotlin/Native kernel library was not produced: ${kotlinLib.absolutePath}")
        }

        val linkerArgs = mutableListOf(
            "-nostdlib", "-static", "-no-pie",
            "-Wl,-z,max-page-size=0x1000",
            "-Wl,--build-id=none",
            "-T", linkerScript.absolutePath,
            "-o", layout.buildDirectory.file("kurtos.elf").get().asFile.absolutePath,
        )
        linkerArgs.addAll(runtimeObjects.map { it.absolutePath })
        linkerArgs.add(kotlinLib.absolutePath)
        cxxSupportDir.get().asFile.listFiles()
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

val compileShaders by tasks.registering(CompileShaders::class) {
    group = "kurtos"
    description = "Compile shaders/*.c to gfx902 HSA code objects and extract .kbin blobs"

    sourceDir.set(shadersDir)
    extractScript.set(layout.projectDirectory.file("tools/extract_shader.py"))
    compiler.set(
        providers.gradleProperty("kurtos.clang")
            .orElse(providers.environmentVariable("KURTOS_CLANG"))
            .orElse("clang")
    )
    target.set("amdgcn-amd-amdhsa")
    cpu.set("gfx902")
    compilerArgs.set(listOf("-O2", "-nogpulib", "-ffreestanding", "-fno-jump-tables"))
    outputDir.set(compiledShadersDir)
}

val buildImage by tasks.registering(Exec::class) {
    group = "kurtos"
    description = "Build the bootable GPT/ESP disk image at build/kurtos.img"
    dependsOn(linkKurtOS, compileShaders)

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
