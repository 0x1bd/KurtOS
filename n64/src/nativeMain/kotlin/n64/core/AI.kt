package n64.core

const val AI_DRAM_ADDR = 0
const val AI_LEN = 1
const val AI_CONTROL = 2
const val AI_STATUS = 3
const val AI_DACRATE = 4
const val AI_BITRATE = 5

const val AI_STATUS_BUSY = 0x40000000
const val AI_STATUS_FULL = -0x80000000

const val OUTPUT_RATE = 48000

class AI(private val n64: N64) {
    val regs = IntArray(6)

    private val address = LongArray(2)
    private val length = LongArray(2)
    private val duration = LongArray(2)

    private var freq = 44100L
    private var delayedCarry = false

    val samples = ShortArray(16384)
    var frames = 0
        private set

    private var resampleAcc = 0L

    fun reset() {
        regs.fill(0)
        address.fill(0)
        length.fill(0)
        duration.fill(0)
        freq = 44100
        delayedCarry = false
        frames = 0
        resampleAcc = 0
    }

    fun read(reg: Int): Int {
        n64.cpu.addCycles(20)
        return when (reg) {
            AI_LEN -> remainingLength().toInt()
            else -> if (reg < 6) regs[reg] else 0
        }
    }

    fun write(reg: Int, value: Int, mask: Int) {
        when (reg) {
            AI_LEN -> {
                regs[reg] = (regs[reg] and mask.inv()) or (value and mask)
                if (regs[reg] != 0) push()
            }

            AI_STATUS -> {
                n64.mi.clearInterrupt(MI_INTR_AI)
                n64.cpu.checkPendingInterrupts()
            }

            AI_DACRATE -> {
                val updated = (regs[reg] and mask.inv()) or (value and mask)
                if (updated != regs[reg]) {
                    freq = n64.vi.clock / (1L + (updated.toLong() and 0x3FFF))
                    if (freq <= 0) freq = 44100
                }
                regs[reg] = updated
            }

            else -> if (reg < 6) regs[reg] = (regs[reg] and mask.inv()) or (value and mask)
        }
    }

    private fun dmaDuration(): Long {
        val bytes = (regs[AI_LEN] and 0x3FFF8).toLong()
        if (freq <= 0) return 1
        return (bytes * n64.clockRate) / (4L * freq)
    }

    private fun remainingLength(): Long {
        if (duration[0] == 0L || !n64.eventPending(EVENT_AI)) return 0
        val remaining = n64.eventCount[EVENT_AI] - n64.cpu.count
        if (remaining <= 0) return 0
        return (remaining * length[0] / duration[0]) and 7L.inv()
    }

    private fun push() {
        val d = dmaDuration()

        if (regs[AI_STATUS] and AI_STATUS_BUSY != 0) {
            address[1] = (regs[AI_DRAM_ADDR] and RDRAM_MASK).toLong()
            length[1] = (regs[AI_LEN] and 0x3FFF8).toLong()
            duration[1] = d
            regs[AI_STATUS] = regs[AI_STATUS] or AI_STATUS_FULL
        } else {
            address[0] = (regs[AI_DRAM_ADDR] and RDRAM_MASK).toLong()
            length[0] = (regs[AI_LEN] and 0x3FFF8).toLong()
            duration[0] = d
            regs[AI_STATUS] = regs[AI_STATUS] or AI_STATUS_BUSY
            startDma()
        }
    }

    private fun startDma() {
        if (delayedCarry) address[0] += 0x2000
        delayedCarry = ((address[0] + length[0]) and 0x1FFF) == 0L

        play(address[0].toInt(), length[0].toInt())

        n64.createEvent(EVENT_AI, if (duration[0] > 0) duration[0] else 1)
        n64.mi.setInterrupt(MI_INTR_AI)
    }

    fun dmaEvent() {
        if (regs[AI_STATUS] and AI_STATUS_FULL != 0) {
            address[0] = address[1]
            length[0] = length[1]
            duration[0] = duration[1]
            regs[AI_STATUS] = regs[AI_STATUS] and AI_STATUS_FULL.inv()
            startDma()
        } else {
            regs[AI_STATUS] = regs[AI_STATUS] and AI_STATUS_BUSY.inv()
            delayedCarry = false
        }
    }

    private fun play(start: Int, bytes: Int) {
        if (bytes <= 0) return

        val step = (freq shl 16) / OUTPUT_RATE
        val count = bytes / 4

        var index = 0
        while (index < count) {
            val at = start + index * 4
            val left = n64.ramRead16(at).toShort()
            val right = n64.ramRead16(at + 2).toShort()

            resampleAcc += 65536L
            while (resampleAcc >= step && frames < samples.size / 2) {
                resampleAcc -= step
                samples[frames * 2] = left
                samples[frames * 2 + 1] = right
                frames++
            }
            index++
        }
    }

    fun drain() {
        frames = 0
    }
}
