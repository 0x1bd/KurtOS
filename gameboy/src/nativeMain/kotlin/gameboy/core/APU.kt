package gameboy.core

class APU {
    val samples = ShortArray(MAX_FRAMES * 2)

    var frames = 0
        private set

    private val registers = IntArray(REGISTERS)
    private val outputs = IntArray(4)
    private val active = BooleanArray(4)

    private val square1 = SquareChannel(sweeping = true)
    private val square2 = SquareChannel(sweeping = false)
    private val wave = WaveChannel()
    private val noise = NoiseChannel()

    private var powered = false

    private var leftVolume = 7
    private var rightVolume = 7
    private var panning = 0xF3

    private var sequencerTimer = SEQUENCER_PERIOD
    private var sequencerStep = 0

    private var sampleTimer = 0

    private var chargeLeft = 0.0
    private var chargeRight = 0.0

    fun drain() {
        frames = 0
    }

    fun step(cycles: Int) {
        if (powered) {
            sequencerTimer -= cycles
            while (sequencerTimer <= 0) {
                sequencerTimer += SEQUENCER_PERIOD
                clockSequencer()
            }

            square1.step(cycles)
            square2.step(cycles)
            wave.step(cycles)
            noise.step(cycles)
        }

        sampleTimer += cycles * SAMPLE_RATE
        while (sampleTimer >= CPU_HZ) {
            sampleTimer -= CPU_HZ
            emit()
        }
    }

    private fun clockSequencer() {
        when (sequencerStep) {
            0, 4 -> clockLengths()

            2, 6 -> {
                clockLengths()
                square1.clockSweep()
            }

            7 -> {
                square1.clockEnvelope()
                square2.clockEnvelope()
                noise.clockEnvelope()
            }
        }

        sequencerStep = (sequencerStep + 1) and 7
    }

    private fun clockLengths() {
        square1.clockLength()
        square2.clockLength()
        wave.clockLength()
        noise.clockLength()
    }

    private fun emit() {
        if (frames >= MAX_FRAMES) return

        var left = 0
        var right = 0

        if (powered) {
            outputs[0] = square1.output()
            outputs[1] = square2.output()
            outputs[2] = wave.output()
            outputs[3] = noise.output()

            active[0] = square1.dacEnabled
            active[1] = square2.dacEnabled
            active[2] = wave.dacEnabled
            active[3] = noise.dacEnabled

            for (channel in 0 until 4) {
                if (!active[channel]) continue

                val value = outputs[channel] * 2 - 15

                if (panning and (0x10 shl channel) != 0) left += value
                if (panning and (0x01 shl channel) != 0) right += value
            }

            left *= leftVolume + 1
            right *= rightVolume + 1
        }

        samples[frames * 2] = clamp(highPass(left * GAIN, true))
        samples[frames * 2 + 1] = clamp(highPass(right * GAIN, false))
        frames++
    }

    private fun highPass(input: Int, isLeft: Boolean): Double {
        val value = input.toDouble()

        return if (isLeft) {
            val output = value - chargeLeft
            chargeLeft = value - output * CHARGE_FACTOR
            output
        } else {
            val output = value - chargeRight
            chargeRight = value - output * CHARGE_FACTOR
            output
        }
    }

    private fun clamp(value: Double): Short {
        if (value > 32767.0) return 32767
        if (value < -32768.0) return -32768
        return value.toInt().toShort()
    }

    fun read(address: Int): Int {
        if (address in 0xFF30..0xFF3F) {
            return wave.ram[address - 0xFF30].toInt() and 0xFF
        }

        if (address == 0xFF26) {
            var value = if (powered) 0x80 else 0x00
            if (square1.enabled) value = value or 0x01
            if (square2.enabled) value = value or 0x02
            if (wave.enabled) value = value or 0x04
            if (noise.enabled) value = value or 0x08
            return value or 0x70
        }

        val index = address - 0xFF10
        if (index < 0 || index >= REGISTERS) return 0xFF

        return registers[index] or READ_MASK[index]
    }

    fun write(address: Int, value: Int) {
        if (address in 0xFF30..0xFF3F) {
            wave.ram[address - 0xFF30] = value.toByte()
            return
        }

        if (address == 0xFF26) {
            val enable = value and 0x80 != 0
            if (!enable && powered) powerOff()
            if (enable && !powered) powerOn()
            powered = enable
            return
        }

        if (!powered) return

        val index = address - 0xFF10
        if (index < 0 || index >= REGISTERS) return

        registers[index] = value and 0xFF

        when (address) {
            in 0xFF10..0xFF14 -> square1.write(address - 0xFF10, value)
            in 0xFF16..0xFF19 -> square2.write(address - 0xFF15, value)
            in 0xFF1A..0xFF1E -> wave.write(address - 0xFF1A, value)
            in 0xFF20..0xFF23 -> noise.write(address - 0xFF1F, value)

            0xFF24 -> {
                rightVolume = value and 0x07
                leftVolume = (value shr 4) and 0x07
            }

            0xFF25 -> panning = value and 0xFF
        }
    }

    private fun powerOn() {
        sequencerStep = 0
        sequencerTimer = SEQUENCER_PERIOD
    }

    private fun powerOff() {
        square1.disable()
        square2.disable()
        wave.disable()
        noise.disable()

        for (i in registers.indices) registers[i] = 0

        leftVolume = 0
        rightVolume = 0
        panning = 0
    }

    private companion object {
        const val MAX_FRAMES = 1024
        const val SAMPLE_RATE = 48000
        const val CPU_HZ = 4194304
        const val SEQUENCER_PERIOD = 8192
        const val GAIN = 45
        const val CHARGE_FACTOR = 0.996
        const val REGISTERS = 0x30

        val READ_MASK = intArrayOf(
            0x80, 0x3F, 0x00, 0xFF, 0xBF,
            0xFF, 0x3F, 0x00, 0xFF, 0xBF,
            0x7F, 0xFF, 0x9F, 0xFF, 0xBF,
            0xFF, 0xFF, 0x00, 0x00, 0xBF,
            0x00, 0x00, 0x70, 0xFF,
            0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        )
    }
}
