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

    private var fadt: ULong = 0UL
    private var fadtLength: UInt = 0u

    fun initialize() {
        val rsdp = BootInfo.rsdpAddress
        if (rsdp == 0UL) return

        val rsdpVirtual = if (rsdp >= BootInfo.hhdmOffset) rsdp else BootInfo.toVirtual(rsdp)

        val revision = RawMemory.read8(rsdpVirtual + 15u).toInt()
        val root: ULong
        val width: Int
        if (revision >= 2) {
            root = RawMemory.read64(rsdpVirtual + 24u)
            width = 8
        } else {
            root = RawMemory.read32(rsdpVirtual + 16u).toULong()
            width = 4
        }
        if (root == 0UL) return

        val rootVirtual = virtual(root)
        fadt = findTable(rootVirtual, width, "FACP")
        if (fadt != 0UL) fadtLength = RawMemory.read32(fadt + 4u)

        val madt = findTable(rootVirtual, width, "APIC")
        if (madt == 0UL) return

        parseMadt(madt)
        available = true
    }

    fun pm1aControlPort(): Int = if (fadt == 0UL) 0 else RawMemory.read32(fadt + 64u).toInt()

    fun pm1bControlPort(): Int = if (fadt == 0UL) 0 else RawMemory.read32(fadt + 68u).toInt()

    fun smiCommandPort(): Int = if (fadt == 0UL) 0 else RawMemory.read32(fadt + 48u).toInt()

    fun acpiEnableValue(): Int = if (fadt == 0UL) 0 else RawMemory.read8(fadt + 52u).toInt()

    fun resetRegister(): Triple<Int, ULong, UByte>? {
        if (fadt == 0UL || fadtLength < 129u) return null
        if (RawMemory.read32(fadt + 112u) and RESET_SUPPORTED == 0u) return null

        val space = RawMemory.read8(fadt + 116u).toInt()
        val address = RawMemory.read64(fadt + 120u)
        if (address == 0UL) return null

        return Triple(space, address, RawMemory.read8(fadt + 128u))
    }

    fun sleepState5(): Pair<Int, Int>? {
        val dsdt = dsdtAddress()
        if (dsdt == 0UL) return null

        val length = RawMemory.read32(dsdt + 4u)
        if (length < 36u || length > MAX_DSDT) return null

        val body = RawMemory.readBytes(dsdt + 36u, (length - 36u).toInt())
        val at = findSignature(body) ?: return null

        var cursor = at + 4
        if (cursor >= body.size) return null

        if (body[cursor].toInt() and 0xFF == PACKAGE_OP) {
            cursor++
            if (cursor >= body.size) return null
            cursor += ((body[cursor].toInt() and 0xC0) shr 6) + 2
        }

        val first = element(body, cursor) ?: return null
        val second = element(body, first.second) ?: return null
        return first.first to second.first
    }

    private fun element(body: ByteArray, at: Int): Pair<Int, Int>? {
        var cursor = at
        if (cursor >= body.size) return null

        val head = body[cursor].toInt() and 0xFF
        if (head == BYTE_PREFIX) {
            cursor++
            if (cursor >= body.size) return null
            return (body[cursor].toInt() and 0xFF) to (cursor + 1)
        }
        if (head == ZERO_OP) return 0 to (cursor + 1)
        if (head == ONE_OP) return 1 to (cursor + 1)
        return null
    }

    private fun findSignature(body: ByteArray): Int? {
        for (i in 2 until body.size - 4) {
            if (body[i] != '_'.code.toByte() || body[i + 1] != 'S'.code.toByte()) continue
            if (body[i + 2] != '5'.code.toByte() || body[i + 3] != '_'.code.toByte()) continue

            val before = body[i - 1].toInt() and 0xFF
            val twoBefore = body[i - 2].toInt() and 0xFF
            if (before == NAME_OP) return i
            if (before == '\\'.code && twoBefore == NAME_OP) return i
        }
        return null
    }

    private fun dsdtAddress(): ULong {
        if (fadt == 0UL) return 0UL

        if (fadtLength >= 148u) {
            val extended = RawMemory.read64(fadt + 140u)
            if (extended != 0UL) return virtual(extended)
        }

        val legacy = RawMemory.read32(fadt + 40u).toULong()
        return if (legacy == 0UL) 0UL else virtual(legacy)
    }

    fun gsiForIrq(irq: Int): Int {
        val override = overrides.firstOrNull { it.source == irq }
        return override?.gsi ?: irq
    }

    fun flagsForIrq(irq: Int): Int =
        overrides.firstOrNull { it.source == irq }?.flags ?: 0

    private fun virtual(physical: ULong): ULong =
        if (physical >= BootInfo.hhdmOffset) physical else BootInfo.toVirtual(physical)

    private fun findTable(root: ULong, entryWidth: Int, wanted: String): ULong {
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
            if (signature(table) == wanted) return table
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

    private val RESET_SUPPORTED: UInt = 1u shl 10
    private val MAX_DSDT: UInt = 512u * 1024u
    private const val NAME_OP = 0x08
    private const val PACKAGE_OP = 0x12
    private const val BYTE_PREFIX = 0x0A
    private const val ZERO_OP = 0x00
    private const val ONE_OP = 0x01
}
