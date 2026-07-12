package kernel.drivers

import hal.Arch
import kapi.KeyEvent
import kapi.Keys

object Keyboard {
    private const val MAX_KEYCODE = 256

    private val down = BooleanArray(MAX_KEYCODE)
    private val pressed = BooleanArray(MAX_KEYCODE)
    private val events = ArrayDeque<KeyEvent>()
    private val characters = ArrayDeque<Char>()

    private var extended = false
    private var shift = false

    private var layout = KeyboardLayout.DE

    val layoutName: String get() = layout.name

    fun selectLayout(name: String): Boolean {
        val next = KeyboardLayout.find(name) ?: return false
        layout = next
        return true
    }

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

    fun consumePress(code: UShort): Boolean {
        val index = code.toInt()
        if (index !in pressed.indices || !pressed[index]) return false

        pressed[index] = false
        return true
    }

    fun nextEvent(): KeyEvent? = events.removeFirstOrNull()

    fun nextChar(): Char? = characters.removeFirstOrNull()

    fun characterFor(code: UShort): Char? = layout.character(code.toInt(), false)

    fun drain() {
        events.clear()
        characters.clear()
        pressed.fill(false)
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
        if (!released) pressed[keycode] = true
        events.addLast(KeyEvent(keycode.toUShort(), !released))

        if (!released) {
            layout.character(keycode, shift)?.let { characters.addLast(it) }
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
}
