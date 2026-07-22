package spezi.common

import spezi.domain.Token

enum class Level(val label: String) {
    INFO("info"),
    WARN("warning"),
    ERROR("error"),
}

class Diagnostic(
    val level: Level,
    val message: String,
    val loc: Token?,
    val note: String?,
)

class DiagnosticReporter(private val color: Boolean) {

    private val entries = ArrayList<Diagnostic>()

    val diagnostics: List<Diagnostic> get() = entries
    val errorCount: Int get() = entries.count { it.level == Level.ERROR }
    val hasErrors: Boolean get() = entries.any { it.level == Level.ERROR }

    fun report(level: Level, message: String, loc: Token?, note: String? = null) {
        entries.add(Diagnostic(level, message, loc, note))
    }

    fun render(): String {
        val out = StringBuilder()
        for (diagnostic in entries) {
            val tint = when (diagnostic.level) {
                Level.ERROR -> RED
                Level.WARN -> YELLOW
                Level.INFO -> BLUE
            }
            out.append(paint(tint + BOLD, diagnostic.level.label))
            out.append(paint(BOLD, ": " + diagnostic.message))
            out.append('\n')

            val loc = diagnostic.loc
            if (loc != null) {
                val lineText = loc.source.lineText(loc.line)
                val gutter = loc.line.toString()
                val pad = " ".repeat(gutter.length)
                out.append(paint(CYAN, "$pad--> ")).append(loc.source.path)
                out.append(':').append(loc.line).append(':').append(loc.col).append('\n')
                out.append(paint(CYAN, "$pad |")).append('\n')
                out.append(paint(CYAN, "$gutter |")).append(' ').append(lineText.replace("\t", "    ")).append('\n')
                val leading = lineText.take((loc.col - 1).coerceAtLeast(0))
                val caretPad = leading.replace("\t", "    ").replace(Regex("[^\t]"), " ")
                val carets = "^".repeat(loc.length.coerceAtLeast(1))
                out.append(paint(CYAN, "$pad |")).append(' ').append(caretPad).append(paint(tint, carets)).append('\n')
            }

            if (diagnostic.note != null) {
                out.append(paint(CYAN, "note")).append(": ").append(diagnostic.note).append('\n')
            }
            out.append('\n')
        }
        return out.toString()
    }

    private fun paint(style: String, text: String): String = if (color) "$style$text$RESET" else text

    private companion object {

        const val RESET = "[0m"
        const val BOLD = "[1m"
        const val RED = "[31m"
        const val YELLOW = "[33m"
        const val BLUE = "[34m"
        const val CYAN = "[36m"
    }
}

class CompilerException(message: String) : RuntimeException(message)
