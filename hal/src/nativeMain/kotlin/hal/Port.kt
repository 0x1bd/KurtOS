package hal

import kotlinx.cinterop.ExperimentalForeignApi
import mmio.io_wait
import mmio.port_in16
import mmio.port_in32
import mmio.port_in8
import mmio.port_out16
import mmio.port_out32
import mmio.port_out8

@OptIn(ExperimentalForeignApi::class)
object Port {
    fun read8(port: UShort): UByte = port_in8(port)

    fun write8(port: UShort, value: UByte) = port_out8(port, value)

    fun read16(port: UShort): UShort = port_in16(port)

    fun write16(port: UShort, value: UShort) = port_out16(port, value)

    fun read32(port: UShort): UInt = port_in32(port)

    fun write32(port: UShort, value: UInt) = port_out32(port, value)

    fun wait() = io_wait()
}
