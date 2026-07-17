package kernel.drivers.gpu.vega

import hal.RawMemory

class VegaRegs(private val base: ULong) {
    fun read(reg: UInt): UInt = RawMemory.read32(base + (reg.toULong() shl 2))

    fun write(reg: UInt, value: UInt) = RawMemory.write32(base + (reg.toULong() shl 2), value)

    fun readIndirect(reg: UInt): UInt {
        RawMemory.write32(base, reg shl 2)
        return RawMemory.read32(base + 4UL)
    }

    fun writeIndirect(reg: UInt, value: UInt) {
        RawMemory.write32(base, reg shl 2)
        RawMemory.write32(base + 4UL, value)
    }

    fun smnRead(address: UInt): UInt {
        RawMemory.write32(base + (0xEUL shl 2), address)
        return RawMemory.read32(base + (0xFUL shl 2))
    }

    fun smnWrite(address: UInt, value: UInt) {
        RawMemory.write32(base + (0xEUL shl 2), address)
        RawMemory.write32(base + (0xFUL shl 2), value)
    }

    fun poll(reg: UInt, mask: UInt, expected: UInt, spins: Int = 1_000_000): Boolean {
        var remaining = spins
        while (remaining > 0) {
            if (read(reg) and mask == expected) return true
            remaining--
        }
        return false
    }
}
