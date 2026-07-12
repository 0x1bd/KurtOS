package gameboy.core

class NoiseChannel {
    var enabled = false
        private set

    var dacEnabled = false
        private set

    private var timer = 0
    private var register = 0x7FFF

    private var shift = 0
    private var narrow = false
    private var divisor = 0

    private var length = 0
    private var lengthEnabled = false

    private var envelopeInitial = 0
    private var envelopeIncreasing = false
    private var envelopePeriod = 0
    private var envelopeTimer = 0
    private var volume = 0

    fun step(cycles: Int) {
        timer -= cycles

        while (timer <= 0) {
            timer += period()
            advance()
        }
    }

    private fun advance() {
        val bit = (register and 1) xor ((register shr 1) and 1)
        register = register shr 1
        register = register or (bit shl 14)

        if (narrow) {
            register = (register and 0x40.inv()) or (bit shl 6)
        }
    }

    fun output(): Int {
        if (!enabled || !dacEnabled) return 0
        if (register and 1 != 0) return 0

        return volume
    }

    fun clockLength() {
        if (!lengthEnabled || length == 0) return

        length--
        if (length == 0) enabled = false
    }

    fun clockEnvelope() {
        if (envelopePeriod == 0) return

        envelopeTimer--
        if (envelopeTimer > 0) return

        envelopeTimer = envelopePeriod

        if (envelopeIncreasing && volume < 15) volume++
        if (!envelopeIncreasing && volume > 0) volume--
    }

    private fun period(): Int {
        val base = DIVISORS[divisor]
        val value = base shl shift
        return if (value <= 0) 8 else value
    }

    fun write(index: Int, value: Int) {
        when (index) {
            1 -> length = 64 - (value and 0x3F)

            2 -> {
                envelopeInitial = (value shr 4) and 0x0F
                envelopeIncreasing = value and 0x08 != 0
                envelopePeriod = value and 0x07

                dacEnabled = value and 0xF8 != 0
                if (!dacEnabled) enabled = false
            }

            3 -> {
                shift = (value shr 4) and 0x0F
                narrow = value and 0x08 != 0
                divisor = value and 0x07
            }

            4 -> {
                lengthEnabled = value and 0x40 != 0
                if (value and 0x80 != 0) trigger()
            }
        }
    }

    private fun trigger() {
        enabled = dacEnabled

        if (length == 0) length = 64

        timer = period()
        envelopeTimer = if (envelopePeriod == 0) 8 else envelopePeriod
        volume = envelopeInitial
        register = 0x7FFF
    }

    fun disable() {
        enabled = false
        dacEnabled = false
        volume = 0
        length = 0
        lengthEnabled = false
        register = 0x7FFF
    }

    private companion object {
        val DIVISORS = intArrayOf(8, 16, 32, 48, 64, 80, 96, 112)
    }
}
