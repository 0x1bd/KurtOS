package kernel.arch

import hal.RawMemory

object IoApic {
    private const val REG_SELECT: ULong = 0x00u
    private const val REG_WINDOW: ULong = 0x10u

    private const val REDIRECTION_BASE = 0x10

    fun route(irq: Int, vector: Int, destinationApicId: UInt) {
        val gsi = Acpi.gsiForIrq(irq)
        val flags = Acpi.flagsForIrq(irq)

        val activeLow = (flags and 0b11) == 0b11
        val levelTriggered = ((flags shr 2) and 0b11) == 0b11

        var low = vector.toULong() and 0xFFUL
        if (activeLow) low = low or (1UL shl 13)
        if (levelTriggered) low = low or (1UL shl 15)

        val high = destinationApicId shl 24

        val index = REDIRECTION_BASE + (gsi - Acpi.ioApicGsiBase) * 2
        write(index + 1, high)
        write(index, low.toUInt())
    }

    fun mask(irq: Int) {
        val gsi = Acpi.gsiForIrq(irq)
        val index = REDIRECTION_BASE + (gsi - Acpi.ioApicGsiBase) * 2
        write(index, read(index) or (1u shl 16))
    }

    private fun read(index: Int): UInt {
        RawMemory.write32(Acpi.ioApicAddress + REG_SELECT, index.toUInt())
        return RawMemory.read32(Acpi.ioApicAddress + REG_WINDOW)
    }

    private fun write(index: Int, value: UInt) {
        RawMemory.write32(Acpi.ioApicAddress + REG_SELECT, index.toUInt())
        RawMemory.write32(Acpi.ioApicAddress + REG_WINDOW, value)
    }
}
