package kurtos

import java.io.ByteArrayOutputStream
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
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
abstract class CompileShaders : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDir: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val extractScript: RegularFileProperty

    @get:Input
    abstract val compiler: Property<String>

    @get:Input
    abstract val target: Property<String>

    @get:Input
    abstract val cpu: Property<String>

    @get:Input
    abstract val compilerArgs: ListProperty<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val exec: ExecOperations

    @TaskAction
    fun compile() {
        val outDir = outputDir.get().asFile
        outDir.mkdirs()
        outDir.listFiles()?.forEach { it.delete() }

        val sources = sourceDir.get().asFile.listFiles()
            ?.filter { it.isFile && it.extension == "c" }
            ?.sortedBy { it.name }
            .orEmpty()

        if (sources.isEmpty()) throw GradleException("no shader sources in ${sourceDir.get().asFile}")

        for (source in sources) {
            val base = source.nameWithoutExtension
            val hsaco = outDir.resolve("$base.hsaco")
            val kbin = outDir.resolve("$base.kbin")

            run(
                listOf(compiler.get(), "-x", "c", "--target=${target.get()}", "-mcpu=${cpu.get()}") +
                    compilerArgs.get() +
                    listOf(source.absolutePath, "-o", hsaco.absolutePath),
                "compile ${source.name}",
            )

            val report = run(
                listOf(
                    "python3",
                    extractScript.get().asFile.absolutePath,
                    hsaco.absolutePath,
                    kbin.absolutePath,
                ),
                "extract ${source.name}",
            )

            logger.lifecycle("shader $base: ${report.trim()}")
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
        if (result.exitValue != 0) throw GradleException("shader $what failed:\n$output")
        return output
    }
}
