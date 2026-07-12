package hal

import kotlinx.cinterop.ExperimentalForeignApi
import mmio.raw_blit_high
import mmio.raw_blit_indexed
import mmio.raw_copy
import mmio.raw_fill32
import mmio.raw_read16
import mmio.raw_read32
import mmio.raw_read64
import mmio.raw_read8
import mmio.raw_write16
import mmio.raw_write32
import mmio.raw_write64
import mmio.raw_write8
import mmio.raw_zero

@OptIn(ExperimentalForeignApi::class)
object RawMemory {
    fun read8(address: ULong): UByte = raw_read8(address)

    fun read16(address: ULong): UShort = raw_read16(address)

    fun read32(address: ULong): UInt = raw_read32(address)

    fun read64(address: ULong): ULong = raw_read64(address)

    fun write8(address: ULong, value: UByte) = raw_write8(address, value)

    fun write16(address: ULong, value: UShort) = raw_write16(address, value)

    fun write32(address: ULong, value: UInt) = raw_write32(address, value)

    fun write64(address: ULong, value: ULong) = raw_write64(address, value)

    fun fill32(address: ULong, value: UInt, count: UInt) = raw_fill32(address, value, count.toULong())

    fun copy(destination: ULong, source: ULong, bytes: ULong) = raw_copy(destination, source, bytes)

    fun zero(address: ULong, length: ULong) = raw_zero(address, length)

    fun blitIndexed(
        destination: ULong,
        destinationStride: UInt,
        source: ULong,
        sourceWidth: UInt,
        sourceHeight: UInt,
        palette: ULong,
        scale: UInt,
    ) = raw_blit_indexed(
        destination,
        destinationStride.toULong(),
        source,
        sourceWidth,
        sourceHeight,
        palette,
        scale,
    )

    fun blitHigh(
        destination: ULong,
        destinationStride: UInt,
        source: ULong,
        sourceWidth: UInt,
        sourceHeight: UInt,
        palette: ULong,
        scale: UInt,
    ) = raw_blit_high(
        destination,
        destinationStride.toULong(),
        source,
        sourceWidth,
        sourceHeight,
        palette,
        scale,
    )

    fun readBytes(address: ULong, length: Int): ByteArray {
        val result = ByteArray(length)
        for (i in 0 until length) {
            result[i] = read8(address + i.toULong()).toByte()
        }
        return result
    }
}
