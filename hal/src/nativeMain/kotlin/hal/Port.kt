package hal

object Port {
    fun read8(port: UShort): UByte = Hal.port.read8(port)

    fun write8(port: UShort, value: UByte) = Hal.port.write8(port, value)

    fun read16(port: UShort): UShort = Hal.port.read16(port)

    fun write16(port: UShort, value: UShort) = Hal.port.write16(port, value)

    fun read32(port: UShort): UInt = Hal.port.read32(port)

    fun write32(port: UShort, value: UInt) = Hal.port.write32(port, value)

    fun wait() = Hal.port.wait()
}
