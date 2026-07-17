package kernel.drivers

import hal.PCIConfig
import hal.RawMemory
import kernel.memory.Mmio

class PciDevice(val bus: Int, val slot: Int, val function: Int) {
    val vendorId: Int = PCIConfig.read16(bus, slot, function, 0x00).toInt()
    val deviceId: Int = PCIConfig.read16(bus, slot, function, 0x02).toInt()

    val revision: Int = PCIConfig.read8(bus, slot, function, 0x08).toInt()
    val programmingInterface: Int = PCIConfig.read8(bus, slot, function, 0x09).toInt()
    val subclass: Int = PCIConfig.read8(bus, slot, function, 0x0A).toInt()
    val classCode: Int = PCIConfig.read8(bus, slot, function, 0x0B).toInt()
    val headerType: Int = PCIConfig.read8(bus, slot, function, 0x0E).toInt()

    val interruptLine: Int = PCIConfig.read8(bus, slot, function, 0x3C).toInt()

    fun bar(index: Int): ULong {
        val offset = 0x10 + index * 4
        val low = PCIConfig.read32(bus, slot, function, offset)

        if (low and 0x1u != 0u) return (low and 0xFFFFFFFCu).toULong()

        val type = (low shr 1) and 0x3u
        val base = (low and 0xFFFFFFF0u).toULong()

        if (type != 0x2u) return base

        val high = PCIConfig.read32(bus, slot, function, offset + 4)
        return base or (high.toULong() shl 32)
    }

    fun barSize(index: Int): ULong {
        val offset = 0x10 + index * 4
        val low = PCIConfig.read32(bus, slot, function, offset)

        if (low and 0x1u != 0u) return 0UL

        val wide = ((low shr 1) and 0x3u) == 0x2u
        val high = if (wide) PCIConfig.read32(bus, slot, function, offset + 4) else 0u

        val command = PCIConfig.read16(bus, slot, function, 0x04)
        PCIConfig.write16(bus, slot, function, 0x04, (command.toUInt() and 0x3u.inv()).toUShort())

        PCIConfig.write32(bus, slot, function, offset, 0xFFFFFFFFu)
        val sizedLow = PCIConfig.read32(bus, slot, function, offset)
        PCIConfig.write32(bus, slot, function, offset, low)

        var mask = (sizedLow and 0xFFFFFFF0u).toULong()

        if (wide) {
            PCIConfig.write32(bus, slot, function, offset + 4, 0xFFFFFFFFu)
            val sizedHigh = PCIConfig.read32(bus, slot, function, offset + 4)
            PCIConfig.write32(bus, slot, function, offset + 4, high)
            mask = mask or (sizedHigh.toULong() shl 32)
        } else if (mask != 0UL) {
            mask = mask or 0xFFFFFFFF00000000UL
        }

        PCIConfig.write16(bus, slot, function, 0x04, command)

        if (mask == 0UL) return 0UL
        return mask.inv() + 1UL
    }

    fun bar64(index: Int): Boolean {
        val low = PCIConfig.read32(bus, slot, function, 0x10 + index * 4)
        return low and 0x1u == 0u && ((low shr 1) and 0x3u) == 0x2u
    }

    fun barPrefetchable(index: Int): Boolean {
        val low = PCIConfig.read32(bus, slot, function, 0x10 + index * 4)
        return low and 0x1u == 0u && (low and 0x8u) != 0u
    }

    fun enableBusMaster() {
        val command = PCIConfig.read16(bus, slot, function, 0x04).toUInt()
        PCIConfig.write16(bus, slot, function, 0x04, (command or 0x0006u).toUShort())
    }

    fun enableMessageInterrupt(vector: Int, apicId: UInt): Boolean =
        enableMsi(vector, apicId) || enableMsix(vector, apicId)

    private fun enableMsi(vector: Int, apicId: UInt): Boolean {
        val offset = findCapability(CAPABILITY_MSI)
        if (offset == 0) return false

        val control = PCIConfig.read16(bus, slot, function, offset + 2).toUInt()
        val wide = control and MSI_64BIT != 0u

        PCIConfig.write32(bus, slot, function, offset + 4, messageAddress(apicId))

        val dataOffset = if (wide) {
            PCIConfig.write32(bus, slot, function, offset + 8, 0u)
            offset + 12
        } else {
            offset + 8
        }

        PCIConfig.write16(bus, slot, function, dataOffset, vector.toUShort())
        PCIConfig.write16(bus, slot, function, offset + 2, ((control and MSI_MULTIPLE.inv()) or MSI_ENABLE).toUShort())

        disableIntx()
        return true
    }

    private fun enableMsix(vector: Int, apicId: UInt): Boolean {
        val offset = findCapability(CAPABILITY_MSIX)
        if (offset == 0) return false

        val location = PCIConfig.read32(bus, slot, function, offset + 4)
        val barBase = bar((location and 0x7u).toInt())
        if (barBase == 0UL) return false

        val entry = Mmio.map(barBase + (location and 0x7u.inv()).toULong(), 16UL)
        if (entry == 0UL) return false

        RawMemory.write32(entry, messageAddress(apicId))
        RawMemory.write32(entry + 4UL, 0u)
        RawMemory.write32(entry + 8UL, vector.toUInt())
        RawMemory.write32(entry + 12UL, 0u)

        val control = PCIConfig.read16(bus, slot, function, offset + 2).toUInt()
        PCIConfig.write16(bus, slot, function, offset + 2, ((control or MSIX_ENABLE) and MSIX_FUNCTION_MASK.inv()).toUShort())

        disableIntx()
        return true
    }

    private fun messageAddress(apicId: UInt): UInt = 0xFEE00000u or (apicId shl 12)

    private fun disableIntx() {
        val command = PCIConfig.read16(bus, slot, function, 0x04).toUInt()
        PCIConfig.write16(bus, slot, function, 0x04, (command or INTX_DISABLE).toUShort())
    }

    private fun findCapability(id: Int): Int {
        val status = PCIConfig.read16(bus, slot, function, 0x06).toUInt()
        if (status and CAPABILITIES_LIST == 0u) return 0

        var offset = PCIConfig.read8(bus, slot, function, 0x34).toInt() and 0xFC
        var guard = 0

        while (offset != 0 && guard < 48) {
            guard++

            if (PCIConfig.read8(bus, slot, function, offset).toInt() == id) return offset
            offset = PCIConfig.read8(bus, slot, function, offset + 1).toInt() and 0xFC
        }

        return 0
    }

    fun describe(): String {
        val location = "${hex(bus, 2)}:${hex(slot, 2)}.${function}"
        val identity = "${hex(vendorId, 4)}:${hex(deviceId, 4)}"
        return "$location  $identity  ${hex(classCode, 2)}${hex(subclass, 2)}  ${name()}"
    }

    private fun name(): String = when {
        classCode == 0x04 && subclass == 0x03 -> "audio (hd audio)"
        classCode == 0x04 && subclass == 0x01 -> "audio (ac97)"
        classCode == 0x0C && subclass == 0x03 -> "usb controller"
        classCode == 0x01 -> "storage"
        classCode == 0x02 -> "network"
        classCode == 0x03 -> "display"
        classCode == 0x06 -> "bridge"
        else -> "device"
    }

    private fun hex(value: Int, width: Int): String =
        value.toString(16).padStart(width, '0')

    private companion object {
        const val CAPABILITY_MSI = 0x05
        const val CAPABILITY_MSIX = 0x11
        const val CAPABILITIES_LIST = 0x10u
        const val MSI_ENABLE = 0x1u
        const val MSI_64BIT = 0x80u
        const val MSI_MULTIPLE = 0x70u
        const val MSIX_ENABLE = 0x8000u
        const val MSIX_FUNCTION_MASK = 0x4000u
        const val INTX_DISABLE = 0x400u
    }
}

object Pci {
    private val devices = mutableListOf<PciDevice>()
    private var scanned = false

    fun all(): List<PciDevice> {
        scan()
        return devices
    }

    fun find(classCode: Int, subclass: Int): PciDevice? {
        scan()
        return devices.firstOrNull { it.classCode == classCode && it.subclass == subclass }
    }

    fun findAll(classCode: Int, subclass: Int): List<PciDevice> {
        scan()
        return devices.filter { it.classCode == classCode && it.subclass == subclass }
    }

    private fun scan() {
        if (scanned) return
        scanned = true

        for (bus in 0 until 256) {
            for (slot in 0 until 32) {
                probe(bus, slot)
            }
        }
    }

    private fun probe(bus: Int, slot: Int) {
        if (PCIConfig.read16(bus, slot, 0, 0x00).toInt() == 0xFFFF) return

        val device = PciDevice(bus, slot, 0)
        devices.add(device)

        if (device.headerType and 0x80 == 0) return

        for (function in 1 until 8) {
            if (PCIConfig.read16(bus, slot, function, 0x00).toInt() == 0xFFFF) continue
            devices.add(PciDevice(bus, slot, function))
        }
    }
}
