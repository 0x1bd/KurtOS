package kurtos

import java.io.ByteArrayOutputStream
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

@CacheableTask
abstract class CompileSpeziShaders : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDir: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val compiler: RegularFileProperty

    @get:Input
    abstract val clang: Property<String>

    @get:Input
    abstract val gpuCpu: Property<String>

    @get:Input
    abstract val optimization: Property<Int>

    @get:Input
    abstract val extraArgs: ListProperty<String>

    @get:Input
    abstract val expectedKernarg: MapProperty<String, Int>

    @get:OutputDirectory
    abstract val gpuDir: DirectoryProperty

    @get:OutputDirectory
    abstract val cpuDir: DirectoryProperty

    @get:Inject
    abstract val exec: ExecOperations

    @TaskAction
    fun compile() {
        val gpuOut = gpuDir.get().asFile
        val cpuOut = cpuDir.get().asFile
        for (dir in listOf(gpuOut, cpuOut)) {
            dir.mkdirs()
            dir.listFiles()?.forEach { it.delete() }
        }

        val sources = sourceDir.get().asFile.listFiles()
            ?.filter { it.isFile && it.extension == "spz" }
            ?.sortedBy { it.name }
            .orEmpty()

        if (sources.isEmpty()) throw GradleException("no spezi shaders in ${sourceDir.get().asFile}")

        val spezi = compiler.get().asFile.absolutePath
        val level = "-O${optimization.get()}"
        val expectations = expectedKernarg.get()

        for (source in sources) {
            val base = source.nameWithoutExtension
            val hsaco = gpuOut.resolve("$base.hsaco")
            val kbin = gpuOut.resolve("$base.kbin")
            val obj = cpuOut.resolve("$base.o")

            run(
                listOf(
                    spezi, "--target", "amdgcn", "--cpu", gpuCpu.get(), level,
                    "--clang", clang.get(), "-o", hsaco.absolutePath,
                ) + extraArgs.get() + listOf(source.absolutePath),
                "compile ${source.name} for the gpu",
            )

            val descriptor = try {
                ShaderBlob.extract(hsaco, kbin, base)
            } catch (failure: ShaderExtractionException) {
                throw GradleException(failure.message ?: "could not extract ${source.name}")
            }
            val report = descriptor.toString()

            val expected = expectations[base]
            if (expected != null) {
                val actual = descriptor.kernargSize
                if (actual != expected) {
                    throw GradleException(
                        "$base takes $actual bytes of kernel arguments but the host dispatch code " +
                            "builds $expected bytes. The shader signature and the host no longer agree, " +
                            "which makes the shader read its arguments from the wrong offsets. " +
                            "Slice parameters carry a length and change this layout, raw pointers do not.",
                    )
                }
            }

            run(
                listOf(
                    spezi, "--target", "x86_64", level,
                    "--clang", clang.get(), "-o", obj.absolutePath,
                ) + extraArgs.get() + listOf(source.absolutePath),
                "compile ${source.name} for the cpu",
            )

            logger.lifecycle("spezi $base: ${report.trim()}")
        }
    }

    private fun run(command: List<String>, what: String): String {
        val sink = ByteArrayOutputStream()
        val result = exec.exec {
            commandLine(command)
            standardOutput = sink
            errorOutput = sink
            isIgnoreExitValue = true
        }
        val output = sink.toString()
        if (result.exitValue != 0) throw GradleException("spezi failed to $what:\n$output")
        return output
    }
}
