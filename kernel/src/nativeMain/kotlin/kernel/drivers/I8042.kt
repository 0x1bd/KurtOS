package kernel.drivers

import hal.Port

object I8042 {
    private const val DATA: UShort = 0x60u
    private const val STATUS: UShort = 0x64u
    private const val COMMAND: UShort = 0x64u

    private const val STATUS_OUTPUT_FULL: UInt = 0x01u
    private const val STATUS_INPUT_FULL: UInt = 0x02u
    private const val STATUS_AUX: UInt = 0x20u

    private const val ACK: UByte = 0xFAu
    private const val SELF_TEST_GUARD = 5_000_000

    var present = false
        private set

    fun pollScancode(): Int {
        val status = Port.read8(STATUS).toUInt()
        if (status and STATUS_OUTPUT_FULL == 0u) return -1

        val data = Port.read8(DATA).toInt() and 0xFF
        if (status and STATUS_AUX != 0u) return -1

        return data
    }

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

        resetKeyboard()
        enableScanning()
        drainOutput()

        return true
    }

    private fun resetKeyboard() {
        if (!writeData(0xFFu)) return
        if (readData() != ACK) return
        readData(SELF_TEST_GUARD)
    }

    private fun enableScanning() {
        if (!writeData(0xF4u)) return
        readData()
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

    private fun readData(guard: Int = 100_000): UByte? {
        var spins = 0
        while (Port.read8(STATUS).toUInt() and STATUS_OUTPUT_FULL == 0u) {
            if (++spins > guard) return null
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
