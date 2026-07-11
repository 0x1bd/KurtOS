package kernel.drivers

import hal.Port

object I8042 {
    private const val DATA: UShort = 0x60u
    private const val STATUS: UShort = 0x64u
    private const val COMMAND: UShort = 0x64u

    private const val STATUS_OUTPUT_FULL: UInt = 0x01u
    private const val STATUS_INPUT_FULL: UInt = 0x02u

    var present = false
        private set

    fun initialize(): Boolean {
        command(0xADu)
        command(0xA7u)

        drainOutput()

        command(0x20u)
        val config = readData() ?: return fail()

        var updated = (config.toUInt() or 0x01u) and 0x10u.inv()
        updated = updated or 0x40u

        command(0x60u)
        if (!writeData(updated.toUByte())) return fail()

        command(0xAEu)

        present = true
        return true
    }

    private fun fail(): Boolean {
        present = false
        return false
    }

    private fun drainOutput() {
        var guard = 0
        while (Port.read8(STATUS).toUInt() and STATUS_OUTPUT_FULL != 0u && guard < 64) {
            Port.read8(DATA)
            guard++
        }
    }

    private fun command(value: UByte) {
        if (!waitInputClear()) return
        Port.write8(COMMAND, value)
    }

    private fun writeData(value: UByte): Boolean {
        if (!waitInputClear()) return false
        Port.write8(DATA, value)
        return true
    }

    private fun readData(): UByte? {
        var guard = 0
        while (Port.read8(STATUS).toUInt() and STATUS_OUTPUT_FULL == 0u) {
            if (++guard > 100_000) return null
        }
        return Port.read8(DATA)
    }

    private fun waitInputClear(): Boolean {
        var guard = 0
        while (Port.read8(STATUS).toUInt() and STATUS_INPUT_FULL != 0u) {
            if (++guard > 100_000) return false
        }
        return true
    }
}
