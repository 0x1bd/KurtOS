package gameboy.core

class Timer(private val interrupts: Interrupts) {
    private var divider = 0
    private var counter = 0
    private var modulo = 0
    private var control = 0

    fun step(cycles: Int) {
        val previous = divider
        divider = (divider + cycles) and 0xFFFF

        if (control and 0x04 == 0) return

        val period = 1 shl (selectedBit() + 1)
        val edges = (previous + cycles) / period - previous / period

        for (i in 0 until edges) increment()
    }

    private fun selectedBit(): Int = when (control and 0x03) {
        0 -> 9
        1 -> 3
        2 -> 5
        else -> 7
    }

    private fun increment() {
        counter++
        if (counter > 0xFF) {
            counter = modulo
            interrupts.request(Interrupts.TIMER)
        }
    }

    fun read(address: Int): Int = when (address) {
        0xFF04 -> (divider shr 8) and 0xFF
        0xFF05 -> counter and 0xFF
        0xFF06 -> modulo and 0xFF
        0xFF07 -> control or 0xF8
        else -> 0xFF
    }

    fun write(address: Int, value: Int) {
        when (address) {
            0xFF04 -> divider = 0
            0xFF05 -> counter = value and 0xFF
            0xFF06 -> modulo = value and 0xFF
            0xFF07 -> control = value and 0x07
        }
    }
}
