package gameboy.core

class Timer(private val interrupts: Interrupts) {
    private var divider = 0
    private var counter = 0
    private var modulo = 0
    private var control = 0

    fun step(cycles: Int) {
        for (i in 0 until cycles) tick()
    }

    private fun tick() {
        val previous = divider
        divider = (divider + 1) and 0xFFFF

        if (control and 0x04 == 0) return

        val bit = when (control and 0x03) {
            0 -> 9
            1 -> 3
            2 -> 5
            else -> 7
        }

        val before = (previous shr bit) and 1
        val after = (divider shr bit) and 1

        if (before == 1 && after == 0) increment()
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
