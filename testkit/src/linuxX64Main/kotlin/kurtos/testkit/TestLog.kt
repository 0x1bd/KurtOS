package kurtos.testkit

object TestLog {
    private val skipped = mutableListOf<String>()

    fun info(tag: String, message: String) {
        println("[$tag] $message")
    }

    fun skip(tag: String, what: String, hint: String = "") {
        val suffix = if (hint.isEmpty()) "" else " ($hint)"
        skipped += "$tag: $what"
        println("[skip] $tag: $what$suffix")
    }

    fun skippedItems(): List<String> = skipped

    fun report(name: String, lines: List<String>) {
        for (line in lines) println("[$name] $line")
        val directory = TestPaths.REPORTS
        if (directory.isEmpty()) return
        writeFile("$directory/$name.txt", lines.joinToString("\n", postfix = "\n"))
    }
}
