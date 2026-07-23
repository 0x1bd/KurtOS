package kernel.drivers

import hal.Arch
import kapi.KeyEvent
import kapi.Keys
import kernel.audio.AudioService
import kernel.ui.OSD
import kernel.ui.SystemSounds

object Keyboard {
    private const val MAX_KEYCODE = 256
    private const val VOLUME_STEP = 10

    private val down = BooleanArray(MAX_KEYCODE)
    private val pressed = BooleanArray(MAX_KEYCODE)
    private val chorded = BooleanArray(MAX_KEYCODE)
    private val events = ArrayDeque<KeyEvent>()
    private val characters = ArrayDeque<Char>()

    private var extended = false
    private var shift = false

    val shiftDown: Boolean get() = shift

    private var layout = KeyboardLayout.DE

    var received = 0
        private set
    var fromRing = 0L
        private set
    var fromPoll = 0L
        private set

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
            fromRing++
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
        chorded[index] = false
        return true
    }

    fun consumeChord(code: UShort): Boolean {
        val index = code.toInt()
        if (index !in chorded.indices || !chorded[index]) return false

        chorded[index] = false
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
        chorded.fill(false)
    }

    private fun handle(scancode: Int) {
        received++

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
        if (!released) {
            pressed[keycode] = true
            if (down[Keys.SUPER.toInt()]) chorded[keycode] = true
            adjustVolume(keycode)
        }
        events.addLast(KeyEvent(keycode.toUShort(), !released))

        if (!released) {
            layout.character(keycode, shift)?.let { characters.addLast(it) }
        }
    }

    private fun adjustVolume(keycode: Int) {
        when (keycode) {
            Keys.F5.toInt() -> AudioService.toggleMuted()
            Keys.F6.toInt() -> AudioService.adjustVolume(-VOLUME_STEP)
            Keys.F7.toInt() -> AudioService.adjustVolume(VOLUME_STEP)
            else -> return
        }

        SystemSounds.play(SystemSounds.Clip.Blip)
        OSD.showVolume()
    }

    private fun extendedKeycode(make: Int): Int = when (make) {
        0x48 -> Keys.UP.toInt()
        0x4B -> Keys.LEFT.toInt()
        0x4D -> Keys.RIGHT.toInt()
        0x50 -> Keys.DOWN.toInt()
        0x1C -> Keys.ENTER.toInt()
        0x20 -> Keys.F5.toInt()
        0x2E -> Keys.F6.toInt()
        0x30 -> Keys.F7.toInt()
        0x38 -> 100
        0x1D -> 97
        0x5B -> Keys.SUPER.toInt()
        0x5C -> Keys.SUPER.toInt()
        else -> 0
    }
}
