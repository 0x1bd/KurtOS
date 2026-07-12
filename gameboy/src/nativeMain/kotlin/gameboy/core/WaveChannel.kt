package gameboy.core

class WaveChannel {
    val ram = ByteArray(16)

    var enabled = false
        private set

    var dacEnabled = false
        private set

    private var frequency = 0
    private var timer = 0
    private var position = 0

    private var length = 0
    private var lengthEnabled = false

    private var volumeCode = 0

    fun step(cycles: Int) {
        timer -= cycles

        while (timer <= 0) {
            timer += period()
            position = (position + 1) and 31
        }
    }

    fun output(): Int {
        if (!enabled || !dacEnabled) return 0

        val byte = ram[position / 2].toInt() and 0xFF
        val sample = if (position % 2 == 0) (byte shr 4) and 0x0F else byte and 0x0F

        return when (volumeCode) {
            0 -> 0
            1 -> sample
            2 -> sample shr 1
            else -> sample shr 2
        }
    }

    fun clockLength() {
        if (!lengthEnabled || length == 0) return

        length--
        if (length == 0) enabled = false
    }

    private fun period(): Int {
        val value = (2048 - frequency) * 2
        return if (value <= 0) 2 else value
    }

    fun write(register: Int, value: Int) {
        when (register) {
            0 -> {
                dacEnabled = value and 0x80 != 0
                if (!dacEnabled) enabled = false
            }

            1 -> length = 256 - (value and 0xFF)

            2 -> volumeCode = (value shr 5) and 0x03

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

        if (length == 0) length = 256

        timer = period()
        position = 0
    }

    fun disable() {
        enabled = false
        dacEnabled = false
        length = 0
        lengthEnabled = false
        frequency = 0
        volumeCode = 0
        position = 0
    }
}
