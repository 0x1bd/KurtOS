package gba.core

class Interrupts {
    var master = false
    var enabled = 0
    var flags = 0

    val pending: Boolean get() = master && (enabled and flags) != 0

    fun request(interrupt: Int) {
        flags = flags or interrupt
    }

    fun acknowledge(bits: Int) {
        flags = flags and bits.inv()
    }

    companion object {
        const val VBLANK = 0x0001
        const val HBLANK = 0x0002
        const val VCOUNT = 0x0004
        const val TIMER0 = 0x0008
        const val TIMER1 = 0x0010
        const val TIMER2 = 0x0020
        const val TIMER3 = 0x0040
        const val SERIAL = 0x0080
        const val DMA0 = 0x0100
        const val DMA1 = 0x0200
        const val DMA2 = 0x0400
        const val DMA3 = 0x0800
        const val KEYPAD = 0x1000
        const val CARTRIDGE = 0x2000
    }
}
