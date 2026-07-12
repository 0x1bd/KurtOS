package gba.core

class APU(private val interrupts: Interrupts) {
    lateinit var dma: DMA

    val samples = ShortArray(MAX_FRAMES * 2)
    var frames = 0
        private set

    private val fifoA = ByteArray(64)
    private val fifoB = ByteArray(64)
    private var fifoAHead = 0
    private var fifoATail = 0
    private var fifoBHead = 0
    private var fifoBTail = 0

    private var sampleA = 0
    private var sampleB = 0

    private var soundCntH = 0
    private var soundCntX = 0
    private var soundBias = 0x200

    private val ioStore = IntArray(0x28)

    private var sampleCounter = 0

    fun ioRead(offset: Int): Int = when (offset) {
        0x82 -> soundCntH
        0x84 -> soundCntX
        0x88 -> soundBias
        in 0x60..0xA6 -> ioStore[(offset - 0x60) / 2]
        else -> 0
    }

    fun ioWrite(offset: Int, value: Int) {
        when (offset) {
            0x82 -> {
                soundCntH = value
                if (value and 0x0800 != 0) resetFifo(false)
                if (value and 0x8000 != 0) resetFifo(true)
            }
            0x84 -> soundCntX = value and 0x80
            0x88 -> soundBias = value
            0xA0, 0xA2 -> fifoWrite(false, value or (value shl 16))
            0xA4, 0xA6 -> fifoWrite(true, value or (value shl 16))
            in 0x60..0x9E -> ioStore[(offset - 0x60) / 2] = value
        }
    }

    fun fifoWrite(fifoB: Boolean, value: Int) {
        for (i in 0 until 4) {
            val byte = ((value ushr (i * 8)) and 0xFF).toByte()
            if (fifoB) {
                if ((fifoBTail + 1) and 63 != fifoBHead) {
                    this.fifoB[fifoBTail] = byte
                    fifoBTail = (fifoBTail + 1) and 63
                }
            } else {
                if ((fifoATail + 1) and 63 != fifoAHead) {
                    fifoA[fifoATail] = byte
                    fifoATail = (fifoATail + 1) and 63
                }
            }
        }
    }

    private fun resetFifo(fifoB: Boolean) {
        if (fifoB) {
            fifoBHead = 0
            fifoBTail = 0
        } else {
            fifoAHead = 0
            fifoATail = 0
        }
    }

    fun onTimerOverflow(timer: Int) {
        val timerA = (soundCntH ushr 10) and 1
        val timerB = (soundCntH ushr 14) and 1

        if (timerA == timer) {
            if (fifoAHead != fifoATail) {
                sampleA = fifoA[fifoAHead].toInt()
                fifoAHead = (fifoAHead + 1) and 63
            }
            if (fifoCount(false) <= 16) dma.onFifoRequest(false)
        }

        if (timerB == timer) {
            if (fifoBHead != fifoBTail) {
                sampleB = fifoB[fifoBHead].toInt()
                fifoBHead = (fifoBHead + 1) and 63
            }
            if (fifoCount(true) <= 16) dma.onFifoRequest(true)
        }
    }

    private fun fifoCount(fifoB: Boolean): Int =
        if (fifoB) (fifoBTail - fifoBHead) and 63 else (fifoATail - fifoAHead) and 63

    fun step(cycles: Int) {
        sampleCounter += cycles * OUTPUT_RATE

        while (sampleCounter >= CPU_HZ) {
            sampleCounter -= CPU_HZ

            if (frames < MAX_FRAMES) {
                var left = 0
                var right = 0

                if (soundCntX and 0x80 != 0) {
                    val volumeA = if (soundCntH and 0x4 != 0) 2 else 1
                    val volumeB = if (soundCntH and 0x8 != 0) 2 else 1

                    if (soundCntH and 0x0200 != 0) left += sampleA * volumeA
                    if (soundCntH and 0x0100 != 0) right += sampleA * volumeA
                    if (soundCntH and 0x2000 != 0) left += sampleB * volumeB
                    if (soundCntH and 0x1000 != 0) right += sampleB * volumeB
                }

                samples[frames * 2] = (left * 64).coerceIn(-32768, 32767).toShort()
                samples[frames * 2 + 1] = (right * 64).coerceIn(-32768, 32767).toShort()
                frames++
            }
        }
    }

    fun drain() {
        frames = 0
    }

    private companion object {
        const val CPU_HZ = 16777216
        const val OUTPUT_RATE = 48000
        const val MAX_FRAMES = 4096
    }
}
