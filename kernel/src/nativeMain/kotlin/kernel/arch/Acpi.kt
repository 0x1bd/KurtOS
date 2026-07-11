package kernel.arch

import hal.BootInfo
import hal.RawMemory

data class InterruptOverride(val source: Int, val gsi: Int, val flags: Int)

object Acpi {
    var ioApicAddress: ULong = 0xFEC00000UL
        private set

    var ioApicGsiBase: Int = 0
        private set

    private val overrides = mutableListOf<InterruptOverride>()

    var available: Boolean = false
        private set

    fun initialize() {
        val rsdp = BootInfo.rsdpAddress
        if (rsdp == 0UL) return

        val rsdpVirtual = if (rsdp >= BootInfo.hhdmOffset) rsdp else BootInfo.toVirtual(rsdp)

        val revision = RawMemory.read8(rsdpVirtual + 15u).toInt()
        val madt = if (revision >= 2) {
            val xsdt = RawMemory.read64(rsdpVirtual + 24u)
            if (xsdt != 0UL) findTable(virtual(xsdt), entryWidth = 8) else 0UL
        } else {
            val rsdt = RawMemory.read32(rsdpVirtual + 16u).toULong()
            if (rsdt != 0UL) findTable(virtual(rsdt), entryWidth = 4) else 0UL
        }

        if (madt == 0UL) return

        parseMadt(madt)
        available = true
    }

    fun gsiForIrq(irq: Int): Int {
        val override = overrides.firstOrNull { it.source == irq }
        return override?.gsi ?: irq
    }

    fun flagsForIrq(irq: Int): Int =
        overrides.firstOrNull { it.source == irq }?.flags ?: 0

    private fun virtual(physical: ULong): ULong =
        if (physical >= BootInfo.hhdmOffset) physical else BootInfo.toVirtual(physical)

    private fun findTable(root: ULong, entryWidth: Int): ULong {
        val length = RawMemory.read32(root + 4u)
        if (length < 36u) return 0UL

        val count = ((length - 36u) / entryWidth.toUInt()).toInt()
        for (i in 0 until count) {
            val offset = root + 36UL + (i * entryWidth).toULong()
            val entry = if (entryWidth == 8) {
                RawMemory.read64(offset)
            } else {
                RawMemory.read32(offset).toULong()
            }
            if (entry == 0UL) continue

            val table = virtual(entry)
            if (signature(table) == "APIC") return table
        }
        return 0UL
    }

    private fun parseMadt(madt: ULong) {
        val length = RawMemory.read32(madt + 4u)

        var cursor = madt + 44UL
        val end = madt + length.toULong()

        while (cursor + 2UL <= end) {
            val type = RawMemory.read8(cursor).toInt()
            val entryLength = RawMemory.read8(cursor + 1u).toInt()
            if (entryLength < 2) break

            when (type) {
                1 -> {
                    ioApicAddress = virtual(RawMemory.read32(cursor + 4u).toULong())
                    ioApicGsiBase = RawMemory.read32(cursor + 8u).toInt()
                }
                2 -> {
                    overrides.add(
                        InterruptOverride(
                            source = RawMemory.read8(cursor + 3u).toInt(),
                            gsi = RawMemory.read32(cursor + 4u).toInt(),
                            flags = RawMemory.read16(cursor + 8u).toInt(),
                        )
                    )
                }
            }

            cursor += entryLength.toULong()
        }
    }

    private fun signature(table: ULong): String {
        val builder = StringBuilder()
        for (i in 0 until 4) {
            builder.append(RawMemory.read8(table + i.toULong()).toInt().toChar())
        }
        return builder.toString()
    }
}
