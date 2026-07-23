package kernel.arch

import hal.BootInfo
import hal.Cpu
import hal.Port
import hal.RawMemory

object Power {

    fun reboot(): Nothing {
        Cpu.disableInterrupts()

        Acpi.resetRegister()?.let { write(it.first, it.second, it.third) }
        settle()

        keyboardPulse()
        settle()

        Cpu.hang()
    }

    fun shutdown(): Nothing {
        Cpu.disableInterrupts()

        val sleep = Acpi.sleepState5()
        val pm1a = Acpi.pm1aControlPort()
        if (sleep != null && pm1a != 0) {
            enableAcpi(pm1a)
            writeSleep(pm1a, sleep.first)

            val pm1b = Acpi.pm1bControlPort()
            if (pm1b != 0) writeSleep(pm1b, sleep.second)
        }

        for (port in EMULATOR_PORTS) {
            Port.write16(port.first, port.second)
            settle()
        }

        Cpu.hang()
    }

    private fun enableAcpi(pm1a: Int) {
        val smiCmd = Acpi.smiCommandPort()
        val enable = Acpi.acpiEnableValue()
        if (smiCmd == 0 || enable == 0) return
        if (Port.read16(pm1a.toUShort()).toInt() and SCI_EN != 0) return

        Port.write8(smiCmd.toUShort(), enable.toUByte())

        var guard = 0
        while (guard < SCI_WAITS && Port.read16(pm1a.toUShort()).toInt() and SCI_EN == 0) {
            settle()
            guard++
        }
    }

    private fun writeSleep(port: Int, slpTyp: Int) {
        val current = Port.read16(port.toUShort()).toInt()
        val value = (current and (7 shl 10).inv()) or ((slpTyp and 7) shl 10) or SLP_EN
        Port.write16(port.toUShort(), value.toUShort())
        settle()
    }

    private fun write(space: Int, address: ULong, value: UByte) {
        if (space == SPACE_IO) {
            Port.write8(address.toUShort(), value)
        } else if (space == SPACE_MEMORY) {
            RawMemory.write8(virtual(address), value)
        }
    }

    private fun virtual(physical: ULong): ULong =
        if (physical >= BootInfo.hhdmOffset) physical else BootInfo.toVirtual(physical)

    private fun keyboardPulse() {
        var guard = 0
        while (guard < DRAIN_LIMIT && Port.read8(STATUS_PORT).toInt() and INPUT_FULL != 0) {
            Port.read8(DATA_PORT)
            guard++
        }
        Port.write8(COMMAND_PORT, PULSE_RESET)
    }

    private fun settle() {
        for (i in 0 until SETTLE_WAITS) Port.wait()
    }

    private const val SPACE_MEMORY = 0
    private const val SPACE_IO = 1

    private const val SLP_EN = 1 shl 13
    private const val SCI_EN = 1
    private const val SCI_WAITS = 256

    private const val DATA_PORT: UShort = 0x60u
    private const val STATUS_PORT: UShort = 0x64u
    private const val COMMAND_PORT: UShort = 0x64u
    private const val INPUT_FULL = 0x02
    private const val PULSE_RESET: UByte = 0xFEu
    private const val DRAIN_LIMIT = 1024
    private const val SETTLE_WAITS = 64

    private val EMULATOR_PORTS = listOf(
        0x604.toUShort() to 0x2000.toUShort(),
        0xB004.toUShort() to 0x2000.toUShort(),
        0x4004.toUShort() to 0x3400.toUShort(),
    )
}
