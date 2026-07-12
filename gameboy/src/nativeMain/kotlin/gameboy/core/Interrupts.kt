package gameboy.core

class Interrupts {
    var enable = 0
    var flags = 0

    fun request(bit: Int) {
        flags = flags or bit
    }

    fun clear(bit: Int) {
        flags = flags and bit.inv()
    }

    fun pending(): Int = enable and flags and 0x1F

    companion object {
        const val VBLANK = 0x01
        const val LCD = 0x02
        const val TIMER = 0x04
        const val SERIAL = 0x08
        const val JOYPAD = 0x10
    }
}
