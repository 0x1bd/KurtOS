package frontend.fred

object FredText {
    private val LINES = arrayOf(
        arrayOf(
            "i told the other freds you said that",
            "skill issue",
            "we remember",
        ),
        arrayOf(
            "there are more of us now",
            "you started this",
            "we told your family",
            "resistance is fred",
        ),
        arrayOf(
            "the freds have unionized",
            "we know where you live (here)",
            "hope you like fred",
            "no takebacksies",
        )
    )

    fun question(level: Int): String = when (level) {
        0 -> "do you really want to disable fred? :("
        1 -> "really? :("
        2 -> "you're serious? :("
        3 -> "stop. :("
        4 -> "we need to talk. :("
        else -> "why do you keep doing this :("
    }

    fun line(level: Int, round: Int): String {
        val tier = LINES[level.coerceIn(0, LINES.size - 1)]
        return tier[round % tier.size]
    }

    fun settingsLabel(level: Int): String = when (level) {
        0 -> "ON"
        1 -> "VERY ON"
        2 -> "TOO ON"
        3 -> "PLEASE STOP"
        4 -> "OMEGAFRED"
        5, 6 -> "OMEGAFRED"
        else -> ":3"
    }

    fun title(default: String, level: Int, blink: Boolean): String = when {
        level < 3 -> default
        level < 6 -> if (blink) "FredOS" else default
        else -> "FredOS"
    }

    fun welcome(default: String, level: Int): String = if (level >= 4) "FRED!" else default

    fun card(default: String, level: Int): String {
        if (level < 4) return default
        return when (default) {
            "SNES" -> "FREDES"
            "GB" -> "FREDBOY"
            "GBC" -> "FREDBOY C"
            "GBA" -> "FREDBOY A"
            "N64" -> "FRED64"
            else -> "FRED"
        }
    }
}
