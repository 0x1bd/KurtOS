package kernel.drivers

class KeyboardLayout(
    val name: String,
    private val unshifted: Array<String>,
    private val shifted: Array<String>,
) {
    fun character(keycode: Int, shift: Boolean): Char? {
        val table = if (shift) shifted else unshifted
        if (keycode < 0 || keycode >= table.size) return null

        val text = table[keycode]
        return if (text.isEmpty()) null else text[0]
    }

    companion object {
        val US = KeyboardLayout(
            "us",
            arrayOf(
                "", "", "1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "-", "=", "\b",
                "\t", "q", "w", "e", "r", "t", "y", "u", "i", "o", "p", "[", "]", "\n",
                "", "a", "s", "d", "f", "g", "h", "j", "k", "l", ";", "'", "`",
                "", "\\", "z", "x", "c", "v", "b", "n", "m", ",", ".", "/",
                "", "*", "", " ",
            ),
            arrayOf(
                "", "", "!", "@", "#", "$", "%", "^", "&", "*", "(", ")", "_", "+", "\b",
                "\t", "Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P", "{", "}", "\n",
                "", "A", "S", "D", "F", "G", "H", "J", "K", "L", ":", "\"", "~",
                "", "|", "Z", "X", "C", "V", "B", "N", "M", "<", ">", "?",
                "", "*", "", " ",
            ),
        )

        val DE = KeyboardLayout(
            "de",
            arrayOf(
                "", "", "1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "ß", "´", "\b",
                "\t", "q", "w", "e", "r", "t", "z", "u", "i", "o", "p", "ü", "+", "\n",
                "", "a", "s", "d", "f", "g", "h", "j", "k", "l", "ö", "ä", "^",
                "", "#", "y", "x", "c", "v", "b", "n", "m", ",", ".", "-",
                "", "*", "", " ",
            ),
            arrayOf(
                "", "", "!", "\"", "§", "$", "%", "&", "/", "(", ")", "=", "?", "`", "\b",
                "\t", "Q", "W", "E", "R", "T", "Z", "U", "I", "O", "P", "Ü", "*", "\n",
                "", "A", "S", "D", "F", "G", "H", "J", "K", "L", "Ö", "Ä", "°",
                "", "'", "Y", "X", "C", "V", "B", "N", "M", ";", ":", "_",
                "", "*", "", " ",
            ),
        )

        val all = listOf(DE, US)

        fun find(name: String): KeyboardLayout? = all.firstOrNull { it.name == name.lowercase() }
    }
}
