package kernel.drivers

import hal.PCIConfig

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

    fun enableBusMaster() {
        val command = PCIConfig.read16(bus, slot, function, 0x04).toUInt()
        PCIConfig.write16(bus, slot, function, 0x04, (command or 0x0006u).toUShort())
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
