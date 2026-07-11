import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import org.gradle.api.tasks.PathSensitivity

plugins {
    base
    kotlin("multiplatform") version "2.3.21" apply false
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
val flxAssetsRoot = layout.projectDirectory.dir("assets")
val flxImage = layout.buildDirectory.file("flx.img")
val limineDir = layout.projectDirectory.dir("third_party/limine")
val diskImage = layout.buildDirectory.file("kurtos.img")

data class FlxBuildObject(
    val hash: ByteArray,
    val type: Int,
    val payload: ByteArray,
    val logicalSize: Long,
    var offset: Long = 0L,
)

val buildFlxImage by tasks.registering {
    group = "kurtos"
    description = "Build the FLX content-addressed filesystem image at build/flx.img"

    inputs.dir(flxAssetsRoot).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.file(flxImage)

    doLast {
        val objects = linkedMapOf<String, FlxBuildObject>()

        fun le32(value: Long): ByteArray = byteArrayOf(
            value.toByte(),
            (value shr 8).toByte(),
            (value shr 16).toByte(),
            (value shr 24).toByte(),
        )

        fun writeLe32(target: ByteArray, offset: Int, value: Long) {
            for (i in 0 until 4) target[offset + i] = (value shr (i * 8)).toByte()
        }

        fun writeLe64(target: ByteArray, offset: Int, value: Long) {
            for (i in 0 until 8) target[offset + i] = (value shr (i * 8)).toByte()
        }

        fun hashObject(type: Int, payload: ByteArray): ByteArray {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update("FLX".toByteArray(Charsets.US_ASCII))
            digest.update(type.toByte())
            digest.update(le32(payload.size.toLong()))
            digest.update(payload)
            return digest.digest()
        }

        fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

        fun internObject(type: Int, payload: ByteArray, logicalSize: Long = payload.size.toLong()): ByteArray {
            val hash = hashObject(type, payload)
            objects.getOrPut(hex(hash)) { FlxBuildObject(hash, type, payload, logicalSize) }
            return hash
        }

        fun buildBlob(file: File): ByteArray = internObject(1, file.readBytes(), file.length())

        fun buildTree(dir: File): ByteArray {
            val children = dir.listFiles()
                ?.filter { !it.name.startsWith(".") }
                ?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name })
                .orEmpty()
            val entries = children.map { child ->
                val type = if (child.isDirectory) 2 else 1
                val hash = if (child.isDirectory) buildTree(child) else buildBlob(child)
                val size = if (child.isDirectory) 0L else child.length()
                Triple(child.name, type, Pair(hash, size))
            }

            val out = ByteArrayOutputStream()
            out.write(le32(entries.size.toLong()))
            entries.forEach { (name, type, hashAndSize) ->
                val nameBytes = name.toByteArray(Charsets.US_ASCII)
                out.write(le32(type.toLong()))
                out.write(le32(nameBytes.size.toLong()))
                val sizeBytes = ByteArray(8)
                writeLe64(sizeBytes, 0, hashAndSize.second)
                out.write(sizeBytes)
                out.write(hashAndSize.first)
                out.write(nameBytes)
            }
            return internObject(2, out.toByteArray(), 0L)
        }

        val rootHash = buildTree(flxAssetsRoot.asFile)
        val records = objects.values.sortedBy { hex(it.hash) }
        var offset = alignUp(512L + records.size * 64L, 512L)
        records.forEach { record ->
            record.offset = offset
            offset += record.payload.size
        }
        val finalSize = alignUp(offset, 512L).toInt()
        val image = ByteArray(finalSize)

        "FLX1".toByteArray(Charsets.US_ASCII).copyInto(image, 0)
        writeLe32(image, 4, 1L)
        writeLe32(image, 8, 512L)
        writeLe32(image, 12, records.size.toLong())
        writeLe64(image, 16, 512L)
        writeLe64(image, 24, records.size * 64L)
        rootHash.copyInto(image, 32)
        writeLe64(image, 64, finalSize.toLong())

        records.forEachIndexed { index, record ->
            val base = 512 + index * 64
            record.hash.copyInto(image, base)
            writeLe32(image, base + 32, record.type.toLong())
            writeLe64(image, base + 40, record.offset)
            writeLe64(image, base + 48, record.payload.size.toLong())
            writeLe64(image, base + 56, record.logicalSize)
            record.payload.copyInto(image, record.offset.toInt())
        }

        val output = flxImage.get().asFile
        output.parentFile.mkdirs()
        output.writeBytes(image)
        println("FLX image ready: ${output.absolutePath} (${output.length()} bytes, ${records.size} objects)")
    }
}

fun alignUp(value: Long, alignment: Long): Long =
    (value + alignment - 1L) and (alignment - 1L).inv()

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

val buildImage by tasks.registering(Exec::class) {
    group = "kurtos"
    description = "Build the bootable GPT/ESP disk image at build/kurtos.img"
    dependsOn(linkKurtOS, buildFlxImage)

    inputs.file(layout.buildDirectory.file("kurtos.elf"))
    inputs.file(flxImage)
    inputs.file(layout.projectDirectory.file("limine.conf"))
    outputs.file(diskImage)

    executable = "bash"
    args(
        layout.projectDirectory.file("tools/mkimage.sh").asFile.absolutePath,
        layout.buildDirectory.file("kurtos.elf").get().asFile.absolutePath,
        flxImage.get().asFile.absolutePath,
        limineDir.asFile.absolutePath,
        diskImage.get().asFile.absolutePath,
    )
}

tasks.named("assemble") {
    dependsOn(buildImage)
}
