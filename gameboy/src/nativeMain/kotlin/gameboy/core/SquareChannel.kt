package gameboy.core

class SquareChannel(private val sweeping: Boolean) {
    var enabled = false
        private set

    var dacEnabled = false
        private set

    private var duty = 0
    private var dutyStep = 0
    private var timer = 0

    private var frequency = 0

    private var length = 0
    private var lengthEnabled = false

    private var envelopeInitial = 0
    private var envelopeIncreasing = false
    private var envelopePeriod = 0
    private var envelopeTimer = 0
    private var volume = 0

    private var sweepPeriod = 0
    private var sweepNegate = false
    private var sweepShift = 0
    private var sweepTimer = 0
    private var sweepEnabled = false
    private var shadowFrequency = 0

    fun step(cycles: Int) {
        timer -= cycles

        while (timer <= 0) {
            timer += period()
            dutyStep = (dutyStep + 1) and 7
        }
    }

    fun output(): Int {
        if (!enabled || !dacEnabled) return 0
        return DUTY[duty][dutyStep] * volume
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

    fun clockSweep() {
        if (!sweeping || !sweepEnabled) return

        sweepTimer--
        if (sweepTimer > 0) return

        sweepTimer = if (sweepPeriod == 0) 8 else sweepPeriod
        if (sweepPeriod == 0) return

        val next = sweepTarget()
        if (next > 2047) {
            enabled = false
            return
        }

        if (sweepShift == 0) return

        shadowFrequency = next
        frequency = next

        if (sweepTarget() > 2047) enabled = false
    }

    private fun sweepTarget(): Int {
        val delta = shadowFrequency shr sweepShift
        return if (sweepNegate) shadowFrequency - delta else shadowFrequency + delta
    }

    private fun period(): Int {
        val value = (2048 - frequency) * 4
        return if (value <= 0) 4 else value
    }

    fun write(register: Int, value: Int) {
        when (register) {
            0 -> {
                sweepPeriod = (value shr 4) and 0x07
                sweepNegate = value and 0x08 != 0
                sweepShift = value and 0x07
            }

            1 -> {
                duty = (value shr 6) and 0x03
                length = 64 - (value and 0x3F)
            }

            2 -> {
                envelopeInitial = (value shr 4) and 0x0F
                envelopeIncreasing = value and 0x08 != 0
                envelopePeriod = value and 0x07

                dacEnabled = value and 0xF8 != 0
                if (!dacEnabled) enabled = false
            }

            3 -> frequency = (frequency and 0x700) or (value and 0xFF)

            4 -> {
                frequency = (frequency and 0xFF) or ((value and 0x07) shl 8)
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

        if (!sweeping) return

        shadowFrequency = frequency
        sweepTimer = if (sweepPeriod == 0) 8 else sweepPeriod
        sweepEnabled = sweepPeriod != 0 || sweepShift != 0

        if (sweepShift != 0 && sweepTarget() > 2047) enabled = false
    }

    fun disable() {
        enabled = false
        dacEnabled = false
        volume = 0
        length = 0
        lengthEnabled = false
        frequency = 0
        duty = 0
        dutyStep = 0
    }

    private companion object {
        val DUTY = arrayOf(
            intArrayOf(0, 0, 0, 0, 0, 0, 0, 1),
            intArrayOf(1, 0, 0, 0, 0, 0, 0, 1),
            intArrayOf(1, 0, 0, 0, 0, 1, 1, 1),
            intArrayOf(0, 1, 1, 1, 1, 1, 1, 0),
        )
    }
}
