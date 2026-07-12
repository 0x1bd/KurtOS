package snes.core

import kapi.state.StateReader
import kapi.state.StateWriter

class Apu : SpcMemory {
    val ram = ByteArray(0x10000)
    val dsp = Sdsp(ram)
    val spc = Spc700(this)

    val samples = ShortArray(MAX_FRAMES * 2)

    var frames = 0
        private set

    var clock = 0L
        private set

    private var control = 0
    private var dspAddress = 0

    private val cpuToApu = IntArray(4)
    private val apuToCpu = IntArray(4)

    private val timerTarget = IntArray(3)
    private val timerCounter = IntArray(3)
    private val timerOutput = IntArray(3)
    private val timerStage = IntArray(3)

    private var dspCounter = 0

    private var resamplePos = 0
    private var previousLeft = 0
    private var previousRight = 0

    fun reset() {
        ram.fill(0)
        dsp.reset()

        control = 0x80
        dspAddress = 0
        clock = 0
        dspCounter = 0
        frames = 0
        resamplePos = 0
        previousLeft = 0
        previousRight = 0

        cpuToApu.fill(0)
        apuToCpu.fill(0)
        timerTarget.fill(0)
        timerCounter.fill(0)
        timerOutput.fill(0)
        timerStage.fill(0)

        spc.reset()
    }

    fun catchUp(master: Long) {
        val target = spcCycles(master)

        while (clock < target) {
            val used = spc.step()
            clock += used
            advance(used)
        }
    }

    fun rebase(master: Long) {
        clock -= spcCycles(master)
    }

    fun drain() {
        frames = 0
    }

    fun readPort(index: Int): Int = apuToCpu[index and 3]

    fun writePort(index: Int, value: Int) {
        cpuToApu[index and 3] = value and 0xFF
    }

    override fun read(address: Int): Int {
        if (address in 0x00F0..0x00FF) {
            return when (address) {
                0x00F2 -> dspAddress
                0x00F3 -> dsp.read(dspAddress and 0x7F)
                0x00F4, 0x00F5, 0x00F6, 0x00F7 -> cpuToApu[address - 0x00F4]
                0x00F8, 0x00F9 -> ram[address].toInt() and 0xFF

                0x00FD, 0x00FE, 0x00FF -> {
                    val index = address - 0x00FD
                    val value = timerOutput[index]
                    timerOutput[index] = 0
                    value
                }

                else -> 0
            }
        }

        if (address >= 0xFFC0 && control and 0x80 != 0) {
            return IPL[address - 0xFFC0].toInt() and 0xFF
        }

        return ram[address].toInt() and 0xFF
    }

    override fun write(address: Int, value: Int) {
        if (address in 0x00F0..0x00FF) {
            when (address) {
                0x00F1 -> writeControl(value)
                0x00F2 -> dspAddress = value
                0x00F3 -> if (dspAddress and 0x80 == 0) dsp.write(dspAddress and 0x7F, value)
                0x00F4, 0x00F5, 0x00F6, 0x00F7 -> apuToCpu[address - 0x00F4] = value
                0x00FA, 0x00FB, 0x00FC -> timerTarget[address - 0x00FA] = value
            }
        }

        ram[address] = value.toByte()
    }

    private fun writeControl(value: Int) {
        if (value and 0x10 != 0) {
            cpuToApu[0] = 0
            cpuToApu[1] = 0
        }

        if (value and 0x20 != 0) {
            cpuToApu[2] = 0
            cpuToApu[3] = 0
        }

        for (i in 0 until 3) {
            val enabled = value and (1 shl i) != 0
            val was = control and (1 shl i) != 0

            if (enabled && !was) {
                timerCounter[i] = 0
                timerOutput[i] = 0
                timerStage[i] = 0
            }
        }

        control = value
    }

    private fun advance(used: Int) {
        for (i in 0 until 3) {
            if (control and (1 shl i) == 0) continue

            val divider = if (i == 2) FAST_TIMER else SLOW_TIMER

            timerStage[i] += used

            while (timerStage[i] >= divider) {
                timerStage[i] -= divider

                timerCounter[i]++

                val target = if (timerTarget[i] == 0) 256 else timerTarget[i]

                if (timerCounter[i] >= target) {
                    timerCounter[i] = 0
                    timerOutput[i] = (timerOutput[i] + 1) and 0x0F
                }
            }
        }

        dspCounter += used

        while (dspCounter >= DSP_DIVIDER) {
            dspCounter -= DSP_DIVIDER
            dsp.sample()
            push(dsp.outLeft, dsp.outRight)
        }
    }

    private fun push(left: Int, right: Int) {
        while (resamplePos < 0x10000) {
            if (frames < MAX_FRAMES) {
                val outLeft = previousLeft + (((left - previousLeft) * resamplePos) shr 16)
                val outRight = previousRight + (((right - previousRight) * resamplePos) shr 16)

                samples[frames * 2] = outLeft.toShort()
                samples[frames * 2 + 1] = outRight.toShort()
                frames++
            }

            resamplePos += RESAMPLE_STEP
        }

        resamplePos -= 0x10000

        previousLeft = left
        previousRight = right
    }

    private fun spcCycles(master: Long): Long = master * SPC_HZ / MASTER_HZ

    fun save(writer: StateWriter) {
        writer.bytes(ram)
        writer.long(clock)
        writer.int(control)
        writer.int(dspAddress)
        writer.ints(cpuToApu)
        writer.ints(apuToCpu)
        writer.ints(timerTarget)
        writer.ints(timerCounter)
        writer.ints(timerOutput)
        writer.ints(timerStage)
        writer.int(dspCounter)
        writer.int(resamplePos)
        writer.int(previousLeft)
        writer.int(previousRight)

        spc.save(writer)
        dsp.save(writer)
    }

    fun load(reader: StateReader) {
        reader.bytes(ram)
        clock = reader.long()
        control = reader.int()
        dspAddress = reader.int()
        reader.ints(cpuToApu)
        reader.ints(apuToCpu)
        reader.ints(timerTarget)
        reader.ints(timerCounter)
        reader.ints(timerOutput)
        reader.ints(timerStage)
        dspCounter = reader.int()
        resamplePos = reader.int()
        previousLeft = reader.int()
        previousRight = reader.int()

        spc.load(reader)
        dsp.load(reader)

        frames = 0
    }

    companion object {
        const val MAX_FRAMES = 1200
        const val SAMPLE_RATE = 48000

        private const val SPC_HZ = 1_024_000L
        private const val MASTER_HZ = 21_477_272L

        private const val DSP_DIVIDER = 32
        private const val SLOW_TIMER = 128
        private const val FAST_TIMER = 16

        private const val RESAMPLE_STEP = 32000 * 0x10000 / SAMPLE_RATE

        private val IPL = byteArrayOf(
            0xCD.toByte(), 0xEF.toByte(), 0xBD.toByte(), 0xE8.toByte(),
            0x00, 0xC6.toByte(), 0x1D, 0xD0.toByte(),
            0xFC.toByte(), 0x8F.toByte(), 0xAA.toByte(), 0xF4.toByte(),
            0x8F.toByte(), 0xBB.toByte(), 0xF5.toByte(), 0x78,
            0xCC.toByte(), 0xF4.toByte(), 0xD0.toByte(), 0xFB.toByte(),
            0x2F, 0x19, 0xEB.toByte(), 0xF4.toByte(),
            0xD0.toByte(), 0xFC.toByte(), 0x7E, 0xF4.toByte(),
            0xD0.toByte(), 0x0B, 0xE4.toByte(), 0xF5.toByte(),
            0xCB.toByte(), 0xF4.toByte(), 0xD7.toByte(), 0x00,
            0xFC.toByte(), 0xD0.toByte(), 0xF3.toByte(), 0xAB.toByte(),
            0x01, 0x10, 0xEF.toByte(), 0x7E,
            0xF4.toByte(), 0x10, 0xEB.toByte(), 0xBA.toByte(),
            0xF6.toByte(), 0xDA.toByte(), 0x00, 0xBA.toByte(),
            0xF4.toByte(), 0xC4.toByte(), 0xF4.toByte(), 0xDD.toByte(),
            0x5D, 0xD0.toByte(), 0xDB.toByte(), 0x1F,
            0x00, 0x00, 0xC0.toByte(), 0xFF.toByte(),
        )
    }
}
