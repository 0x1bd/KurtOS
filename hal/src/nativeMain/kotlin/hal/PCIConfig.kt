package hal

object PCIConfig {
    private const val ADDRESS: UShort = 0xCF8u
    private const val DATA: UShort = 0xCFCu

    private fun select(bus: Int, device: Int, function: Int, offset: Int) {
        val value = 0x80000000u or
            (bus.toUInt() shl 16) or
            ((device.toUInt() and 0x1Fu) shl 11) or
            ((function.toUInt() and 0x07u) shl 8) or
            (offset.toUInt() and 0xFCu)

        Port.write32(ADDRESS, value)
    }

    fun read32(bus: Int, device: Int, function: Int, offset: Int): UInt {
        select(bus, device, function, offset)
        return Port.read32(DATA)
    }

    fun write32(bus: Int, device: Int, function: Int, offset: Int, value: UInt) {
        select(bus, device, function, offset)
        Port.write32(DATA, value)
    }

    fun read16(bus: Int, device: Int, function: Int, offset: Int): UShort {
        val word = read32(bus, device, function, offset)
        return ((word shr ((offset and 2) * 8)) and 0xFFFFu).toUShort()
    }

    fun write16(bus: Int, device: Int, function: Int, offset: Int, value: UShort) {
        val shift = (offset and 2) * 8
        val mask = (0xFFFFu shl shift).inv()
        val current = read32(bus, device, function, offset)
        write32(bus, device, function, offset, (current and mask) or (value.toUInt() shl shift))
    }

    fun read8(bus: Int, device: Int, function: Int, offset: Int): UByte {
        val word = read32(bus, device, function, offset)
        return ((word shr ((offset and 3) * 8)) and 0xFFu).toUByte()
    }
}
