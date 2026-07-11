package kernel.arch

import hal.Port

object Pit {
    private const val CHANNEL2: UShort = 0x42u
    private const val COMMAND: UShort = 0x43u
    private const val GATE: UShort = 0x61u

    private const val FREQUENCY = 1_193_182UL

    fun isPresent(): Boolean {
        Port.write8(COMMAND, 0xB0u)
        Port.write8(CHANNEL2, 0xFFu)
        Port.write8(CHANNEL2, 0xFFu)

        val gate = Port.read8(GATE)
        Port.write8(GATE, ((gate.toUInt() and 0xFDu) or 0x01u).toUByte())

        val first = latch()
        var spin = 0
        while (spin < 10_000) spin++
        val second = latch()

        Port.write8(GATE, gate)
        return first != second
    }

    fun waitMicros(micros: UInt) {
        var count = (FREQUENCY * micros.toULong()) / 1_000_000UL
        if (count == 0UL) count = 1UL
        if (count > 0xFFFFUL) count = 0xFFFFUL

        val gate = (Port.read8(GATE).toUInt() and 0xFCu).toUByte()
        Port.write8(GATE, gate)

        Port.write8(COMMAND, 0xB0u)
        Port.write8(CHANNEL2, (count and 0xFFUL).toUByte())
        Port.write8(CHANNEL2, ((count shr 8) and 0xFFUL).toUByte())

        Port.write8(GATE, (gate.toUInt() or 0x01u).toUByte())
        while (Port.read8(GATE).toUInt() and 0x20u == 0u) {
        }

        Port.write8(GATE, gate)
    }

    private fun latch(): UShort {
        Port.write8(COMMAND, 0x80u)
        val low = Port.read8(CHANNEL2).toUInt()
        val high = Port.read8(CHANNEL2).toUInt()
        return ((high shl 8) or low).toUShort()
    }
}
