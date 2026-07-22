package kurtos

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

abstract class GenerateTestPaths : DefaultTask() {
    @get:Input
    abstract val paths: MapProperty<String, String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val target = outputDir.get().asFile.resolve("kurtos/testkit/TestPaths.kt")
        target.parentFile.mkdirs()

        val entries = paths.get().toSortedMap().entries.joinToString("\n") { (name, value) ->
            "    const val $name = \"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        }

        target.writeText(
            buildString {
                appendLine("package kurtos.testkit")
                appendLine()
                appendLine("object TestPaths {")
                appendLine(entries)
                appendLine("}")
            },
        )
    }
}
