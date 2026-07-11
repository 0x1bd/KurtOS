package kernel.drivers

import hal.Arch
import kapi.KeyEvent
import kapi.Keys

object Keyboard {
    private const val MAX_KEYCODE = 256

    private val down = BooleanArray(MAX_KEYCODE)
    private val events = ArrayDeque<KeyEvent>()
    private val characters = ArrayDeque<Char>()

    private var extended = false
    private var shift = false

    fun poll() {
        while (true) {
            val raw = Arch.nextScancode()
            if (raw < 0) break
            handle(raw and 0xFF)
        }
    }

    fun isKeyDown(code: UShort): Boolean {
        val index = code.toInt()
        return index in down.indices && down[index]
    }

    fun nextEvent(): KeyEvent? = events.removeFirstOrNull()

    fun nextChar(): Char? = characters.removeFirstOrNull()

    fun drain() {
        events.clear()
        characters.clear()
    }

    private fun handle(scancode: Int) {
        if (scancode == 0xE0) {
            extended = true
            return
        }

        val released = (scancode and 0x80) != 0
        val make = scancode and 0x7F

        val keycode = if (extended) extendedKeycode(make) else make
        extended = false

        if (keycode <= 0 || keycode >= MAX_KEYCODE) return

        if (keycode == 42 || keycode == 54) {
            shift = !released
        }

        down[keycode] = !released
        events.addLast(KeyEvent(keycode.toUShort(), !released))

        if (!released) {
            asciiFor(keycode)?.let { characters.addLast(it) }
        }
    }

    private fun extendedKeycode(make: Int): Int = when (make) {
        0x48 -> Keys.UP.toInt()
        0x4B -> Keys.LEFT.toInt()
        0x4D -> Keys.RIGHT.toInt()
        0x50 -> Keys.DOWN.toInt()
        0x1C -> Keys.ENTER.toInt()
        0x38 -> 100
        0x1D -> 97
        else -> 0
    }

    private val unshifted = arrayOf(
        "", "", "1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "-", "=", "\b",
        "\t", "q", "w", "e", "r", "t", "y", "u", "i", "o", "p", "[", "]", "\n",
        "", "a", "s", "d", "f", "g", "h", "j", "k", "l", ";", "'", "`",
        "", "\\", "z", "x", "c", "v", "b", "n", "m", ",", ".", "/",
        "", "*", "", " ",
    )

    private val shifted = arrayOf(
        "", "", "!", "@", "#", "$", "%", "^", "&", "*", "(", ")", "_", "+", "\b",
        "\t", "Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P", "{", "}", "\n",
        "", "A", "S", "D", "F", "G", "H", "J", "K", "L", ":", "\"", "~",
        "", "|", "Z", "X", "C", "V", "B", "N", "M", "<", ">", "?",
        "", "*", "", " ",
    )

    private fun asciiFor(keycode: Int): Char? {
        val table = if (shift) shifted else unshifted
        if (keycode >= table.size) return null
        val text = table[keycode]
        return if (text.isEmpty()) null else text[0]
    }
}
