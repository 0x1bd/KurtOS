package gba.core

class Timers(private val interrupts: Interrupts) {
    lateinit var apu: APU

    private val reload = IntArray(4)
    private val counter = IntArray(4)
    private val control = IntArray(4)
    private val prescalerCount = IntArray(4)

    fun ioRead(offset: Int): Int {
        val timer = (offset - 0x100) / 4
        return if (offset and 2 == 0) counter[timer] else control[timer]
    }

    fun ioWrite(offset: Int, value: Int) {
        val timer = (offset - 0x100) / 4

        if (offset and 2 == 0) {
            reload[timer] = value
        } else {
            val wasEnabled = control[timer] and 0x80 != 0
            control[timer] = value

            if (!wasEnabled && value and 0x80 != 0) {
                counter[timer] = reload[timer]
                prescalerCount[timer] = 0
            }
        }
    }

    fun step(cycles: Int) {
        for (timer in 0 until 4) {
            val ctrl = control[timer]
            if (ctrl and 0x80 == 0) continue
            if (ctrl and 0x04 != 0) continue

            val shift = PRESCALER_SHIFTS[ctrl and 0x3]
            prescalerCount[timer] += cycles

            val ticks = prescalerCount[timer] shr shift
            prescalerCount[timer] = prescalerCount[timer] and ((1 shl shift) - 1)

            if (ticks > 0) tick(timer, ticks)
        }
    }

    private fun tick(timer: Int, amount: Int) {
        var remaining = amount

        while (remaining > 0) {
            val untilOverflow = 0x10000 - counter[timer]

            if (remaining < untilOverflow) {
                counter[timer] += remaining
                return
            }

            remaining -= untilOverflow
            counter[timer] = reload[timer]
            overflow(timer)
        }
    }

    private fun overflow(timer: Int) {
        if (control[timer] and 0x40 != 0) {
            interrupts.request(Interrupts.TIMER0 shl timer)
        }

        if (timer < 2) apu.onTimerOverflow(timer)

        val next = timer + 1
        if (next < 4 && control[next] and 0x80 != 0 && control[next] and 0x04 != 0) {
            counter[next]++
            if (counter[next] >= 0x10000) {
                counter[next] = reload[next]
                overflow(next)
            }
        }
    }

    private companion object {
        val PRESCALER_SHIFTS = intArrayOf(0, 6, 8, 10)
    }
}
