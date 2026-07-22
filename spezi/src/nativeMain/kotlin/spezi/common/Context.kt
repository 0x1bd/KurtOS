package spezi.common

import spezi.backend.target.TargetProfile
import spezi.domain.Token

class CompilationOptions(
    val input: String,
    val output: String,
    val profile: TargetProfile,
    val optLevel: Int,
    val emitIr: Boolean,
    val emitAsm: Boolean,
    val boundsChecks: Boolean,
    val includePaths: List<String>,
    val clang: String,
    val verbose: Boolean,
    val color: Boolean,
    val extraClangArgs: List<String>,
)

enum class CompilationState {
    Reading,
    Parsing,
    SemanticAnalysis,
    Codegen,
    Assembling,
}

class Context(val options: CompilationOptions) {

    val reporter = DiagnosticReporter(options.color)
    var state: CompilationState = CompilationState.Reading
    var currentSource: SourceFile = SourceFile("<unknown>", "")

    private val loadedModules = HashSet<String>()

    fun report(level: Level, message: String, loc: Token? = null, note: String? = null) {
        reporter.report(level, message, loc, note)
    }

    fun resolveImport(module: String): String? {
        val relative = module.replace('.', '/') + ".spz"
        if (Files.exists(relative)) return relative
        for (root in options.includePaths) {
            val candidate = Files.join(root, relative)
            if (Files.exists(candidate)) return candidate
        }
        return null
    }

    fun markLoaded(path: String): Boolean = !loadedModules.add(Files.normalize(path))
}
