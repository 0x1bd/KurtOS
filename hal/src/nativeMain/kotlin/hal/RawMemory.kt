package hal

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.toLong
import kotlinx.cinterop.usePinned

@OptIn(ExperimentalForeignApi::class)
object RawMemory {
    fun read8(address: ULong): UByte = Hal.memory.read8(address)

    fun read16(address: ULong): UShort = Hal.memory.read16(address)

    fun read32(address: ULong): UInt = Hal.memory.read32(address)

    fun read64(address: ULong): ULong = Hal.memory.read64(address)

    fun write8(address: ULong, value: UByte) = Hal.memory.write8(address, value)

    fun write16(address: ULong, value: UShort) = Hal.memory.write16(address, value)

    fun write32(address: ULong, value: UInt) = Hal.memory.write32(address, value)

    fun write64(address: ULong, value: ULong) = Hal.memory.write64(address, value)

    fun fill32(address: ULong, value: UInt, count: UInt) = Hal.memory.fill32(address, value, count.toULong())

    fun copy(destination: ULong, source: ULong, bytes: ULong) = Hal.memory.copy(destination, source, bytes)

    fun zero(address: ULong, length: ULong) = Hal.memory.zero(address, length)

    fun blitIndexed(
        destination: ULong,
        destinationStride: UInt,
        source: ULong,
        sourceWidth: UInt,
        sourceHeight: UInt,
        palette: ULong,
        scale: UInt,
    ) = Hal.memory.blitIndexed(
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
    ) = Hal.memory.blitHigh(
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

    fun copyOut(address: ULong, target: ByteArray, offset: Int, length: Int) {
        if (length <= 0) return
        target.usePinned { pinned ->
            Hal.memory.copy(pinned.addressOf(offset).toLong().toULong(), address, length.toULong())
        }
    }

    fun copyIn(address: ULong, source: ByteArray, offset: Int, length: Int) {
        if (length <= 0) return
        source.usePinned { pinned ->
            Hal.memory.copy(address, pinned.addressOf(offset).toLong().toULong(), length.toULong())
        }
    }

    fun copyOutWords(address: ULong, target: IntArray, offset: Int, count: Int) {
        if (count <= 0) return
        target.usePinned { pinned ->
            Hal.memory.copyStream(pinned.addressOf(offset).toLong().toULong(), address, (count.toULong()) * 4UL)
        }
    }

    fun copyInWords(address: ULong, source: IntArray, offset: Int, count: Int) {
        if (count <= 0) return
        source.usePinned { pinned ->
            Hal.memory.copy(address, pinned.addressOf(offset).toLong().toULong(), (count.toULong()) * 4UL)
        }
    }
}
