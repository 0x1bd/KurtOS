package spezi.common

class SourceFile(val path: String, val content: String) {

    val lineStarts: IntArray by lazy {
        val starts = ArrayList<Int>(content.length / 24 + 4)
        starts.add(0)
        for (i in content.indices) if (content[i] == '\n') starts.add(i + 1)
        starts.toIntArray()
    }

    fun lineText(line: Int): String {
        val starts = lineStarts
        if (line < 1 || line > starts.size) return ""
        val from = starts[line - 1]
        var to = if (line < starts.size) starts[line] else content.length
        while (to > from && (content[to - 1] == '\n' || content[to - 1] == '\r')) to--
        return content.substring(from, to)
    }

    companion object {

        fun load(path: String): SourceFile? {
            val content = Files.read(path) ?: return null
            return SourceFile(path, content)
        }
    }
}
