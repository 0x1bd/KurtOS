package kurtos

import java.io.ByteArrayOutputStream
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

abstract class BuildTestRom : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDir: DirectoryProperty

    @get:Input
    abstract val features: ListProperty<String>

    @get:Input
    @get:Optional
    abstract val romName: Property<String>

    @get:OutputFile
    abstract val rom: RegularFileProperty

    @get:Inject
    abstract val exec: ExecOperations

    @TaskAction
    fun build() {
        val source = sourceDir.get().asFile
        if (!source.resolve("Cargo.toml").isFile) {
            throw GradleException(
                "${source.name} submodule is empty. Run: git submodule update --init --recursive",
            )
        }

        val cargo = which("cargo")
        if (cargo == null) {
            logger.warn("cargo not found; skipping ${source.name} build. Install rustup to run this suite.")
            return
        }
        if (which("nust64") == null) {
            logger.warn(
                "nust64 not found; skipping ${source.name} build. " +
                    "Install with: cargo +stable install nust64 --version 0.4.1 --locked --force",
            )
            return
        }

        val command = mutableListOf(cargo, "run", "--release")
        val selected = features.get()
        if (selected.isNotEmpty()) {
            command += "--features"
            command += selected.joinToString(",")
        }

        val sink = ByteArrayOutputStream()
        val result = exec.exec {
            commandLine(command)
            workingDir(source)
            standardOutput = sink
            errorOutput = sink
            isIgnoreExitValue = true
        }
        if (result.exitValue != 0) throw GradleException("${source.name} build failed:\n$sink")

        val built = source.walkTopDown()
            .filter { it.isFile && (it.extension == "z64" || it.extension == "n64") }
            .filter { romName.orNull == null || it.nameWithoutExtension == romName.get() }
            .maxByOrNull { it.lastModified() }
            ?: throw GradleException("${source.name} produced no ROM:\n$sink")

        val target = rom.get().asFile
        target.parentFile.mkdirs()
        built.copyTo(target, overwrite = true)
        logger.lifecycle("test rom ${target.name}: ${target.length() / 1024} KiB")
    }

    private fun which(tool: String): String? {
        val path = System.getenv("PATH")?.split(':').orEmpty()
        val home = System.getenv("HOME")
        val roots = path + listOfNotNull(home?.let { "$it/.cargo/bin" })
        return roots.map { java.io.File(it, tool) }
            .firstOrNull { it.isFile && it.canExecute() }
            ?.absolutePath
    }
}
