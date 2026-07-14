package n64.core

const val VI_STATUS = 0
const val VI_ORIGIN = 1
const val VI_WIDTH = 2
const val VI_V_INTR = 3
const val VI_CURRENT = 4
const val VI_BURST = 5
const val VI_V_SYNC = 6
const val VI_H_SYNC = 7
const val VI_LEAP = 8
const val VI_H_START = 9
const val VI_V_START = 10
const val VI_V_BURST = 11
const val VI_X_SCALE = 12
const val VI_Y_SCALE = 13

class VI(private val n64: N64) {
    val regs = IntArray(14)

    var clock = 48681812L
    var delay = 1562500L
    var countPerScanline = 6000L
    var field = 0

    val frame = ShortArray(WIDTH * HEIGHT)

    fun reset() {
        regs.fill(0)
        clock = if (n64.rom.pal) 49656530L else 48681812L
        delay = 1562500L
        countPerScanline = 6000L
        field = 0
        frame.fill(0)
    }

    fun setRefreshRate() {
        val vsync = (regs[VI_V_SYNC] + 1).toLong()
        val hsync = ((regs[VI_H_SYNC] and 0xFFF) + 1).toLong()
        if (vsync <= 1L || hsync <= 1L) {
            delay = n64.clockRate / 60
            countPerScanline = delay / 525
            return
        }
        val refresh = clock.toDouble() / vsync.toDouble() / hsync.toDouble() * 2.0
        delay = (n64.clockRate.toDouble() / refresh).toLong()
        countPerScanline = delay / vsync
        if (countPerScanline <= 0) countPerScanline = 1
    }

    val refreshMicros: ULong
        get() = (1000000.0 * delay.toDouble() / n64.clockRate.toDouble()).toULong()

    fun read(reg: Int): Int {
        n64.cpu.addCycles(20)
        if (reg == VI_CURRENT) {
            setCurrentLine()
        }
        return if (reg < 14) regs[reg] else 0
    }

    private fun setCurrentLine() {
        if (n64.eventPending(EVENT_VI)) {
            val remaining = n64.eventCount[EVENT_VI] - n64.cpu.count
            val elapsed = if (delay > remaining) delay - remaining else 0L
            var line = (elapsed / countPerScanline).toInt()
            val vsync = regs[VI_V_SYNC]
            if (vsync > 0 && line >= vsync) line -= vsync
            regs[VI_CURRENT] = line
        }
        regs[VI_CURRENT] = (regs[VI_CURRENT] and 1.inv()) or field
    }

    fun write(reg: Int, value: Int, mask: Int) {
        when (reg) {
            VI_CURRENT -> {
                n64.mi.clearInterrupt(MI_INTR_VI)
                n64.cpu.checkPendingInterrupts()
            }

            VI_V_SYNC -> {
                val updated = (regs[reg] and mask.inv()) or (value and mask)
                if (updated != regs[reg]) {
                    regs[reg] = updated
                    if (!n64.eventPending(EVENT_VI)) n64.createEvent(EVENT_VI, delay)
                    setRefreshRate()
                }
            }

            VI_H_SYNC -> {
                val updated = (regs[reg] and mask.inv()) or (value and mask)
                if (updated != regs[reg]) {
                    regs[reg] = updated
                    setRefreshRate()
                }
            }

            else -> if (reg < 14) regs[reg] = (regs[reg] and mask.inv()) or (value and mask)
        }
    }

    fun verticalInterrupt() {
        field = field xor ((regs[VI_STATUS] shr 6) and 1)

        scanout()

        n64.mi.setInterrupt(MI_INTR_VI)
        n64.createEventAt(EVENT_VI, n64.nextEventCount + delay)
        n64.frameDone = true
    }

    private fun scanout() {
        val status = regs[VI_STATUS]
        val type = status and 3
        val origin = regs[VI_ORIGIN] and 0xFFFFFF
        val width = regs[VI_WIDTH] and 0xFFF

        if (type < 2 || width == 0) {
            frame.fill(0)
            return
        }

        val yScale = regs[VI_Y_SCALE] and 0xFFF
        val vStart = (regs[VI_V_START] shr 16) and 0x3FF
        val vEnd = regs[VI_V_START] and 0x3FF
        var lines = if (vEnd > vStart) (vEnd - vStart) / 2 else 0
        if (yScale != 0 && lines > 0) lines = (lines * yScale) shr 10
        if (lines <= 0) lines = 240
        if (lines > 480) lines = 480

        val height = lines

        for (y in 0 until HEIGHT) {
            val sy = y * height / HEIGHT
            val rowBase = origin + sy * width * (if (type == 3) 4 else 2)
            val out = y * WIDTH
            for (x in 0 until WIDTH) {
                val sx = x * width / WIDTH
                frame[out + x] = if (type == 3) {
                    val pixel = n64.ramRead32(rowBase + sx * 4)
                    val r = (pixel ushr 27) and 0x1F
                    val g = (pixel ushr 19) and 0x1F
                    val b = (pixel ushr 11) and 0x1F
                    ((b shl 10) or (g shl 5) or r).toShort()
                } else {
                    val pixel = n64.ramRead16(rowBase + sx * 2)
                    val r = (pixel ushr 11) and 0x1F
                    val g = (pixel ushr 6) and 0x1F
                    val b = (pixel ushr 1) and 0x1F
                    ((b shl 10) or (g shl 5) or r).toShort()
                }
            }
        }
    }

    companion object {
        const val WIDTH = 320
        const val HEIGHT = 240
    }
}
