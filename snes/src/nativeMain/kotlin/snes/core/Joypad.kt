package snes.core

import kapi.state.StateReader
import kapi.state.StateWriter

class Joypad {
    private var buttons = 0
    private var latch = false
    private val shift = IntArray(2)

    var auto = IntArray(4)
        private set

    fun setButtons(mask: Int) {
        buttons = mask
    }

    fun strobe(value: Boolean) {
        if (latch && !value) reload()
        latch = value
        if (value) reload()
    }

    fun readSerial(port: Int): Int {
        if (latch) reload()

        if (port != 0) return 0

        val bit = (shift[0] ushr 15) and 1
        shift[0] = ((shift[0] shl 1) or 1) and 0xFFFF

        return bit
    }

    fun autoRead() {
        auto[0] = word()
        auto[1] = 0
        auto[2] = 0
        auto[3] = 0
    }

    fun readAuto(offset: Int): Int {
        val index = (offset - 0x4218) shr 1
        if (index !in 0..3) return 0

        val value = auto[index]
        return if (offset and 1 == 0) value and 0xFF else (value ushr 8) and 0xFF
    }

    fun reset() {
        buttons = 0
        latch = false
        shift[0] = 0
        shift[1] = 0
        auto = IntArray(4)
    }

    fun save(writer: StateWriter) {
        writer.int(buttons)
        writer.bool(latch)
        writer.ints(shift)
        writer.ints(auto)
    }

    fun load(reader: StateReader) {
        buttons = reader.int()
        latch = reader.bool()
        reader.ints(shift)
        reader.ints(auto)
    }

    private fun reload() {
        shift[0] = word()
        shift[1] = 0
    }

    private fun word(): Int {
        var value = 0

        if (buttons and kapi.emu.Button.B != 0) value = value or B
        if (buttons and kapi.emu.Button.Y != 0) value = value or Y
        if (buttons and kapi.emu.Button.SELECT != 0) value = value or SELECT
        if (buttons and kapi.emu.Button.START != 0) value = value or START
        if (buttons and kapi.emu.Button.UP != 0) value = value or UP
        if (buttons and kapi.emu.Button.DOWN != 0) value = value or DOWN
        if (buttons and kapi.emu.Button.LEFT != 0) value = value or LEFT
        if (buttons and kapi.emu.Button.RIGHT != 0) value = value or RIGHT
        if (buttons and kapi.emu.Button.A != 0) value = value or A
        if (buttons and kapi.emu.Button.X != 0) value = value or X
        if (buttons and kapi.emu.Button.L != 0) value = value or L
        if (buttons and kapi.emu.Button.R != 0) value = value or R

        return value
    }

    companion object {
        const val B = 0x8000
        const val Y = 0x4000
        const val SELECT = 0x2000
        const val START = 0x1000
        const val UP = 0x0800
        const val DOWN = 0x0400
        const val LEFT = 0x0200
        const val RIGHT = 0x0100
        const val A = 0x0080
        const val X = 0x0040
        const val L = 0x0020
        const val R = 0x0010
    }
}
