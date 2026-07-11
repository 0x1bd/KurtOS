package kernel.fs

import hal.BootInfo
import hal.RawMemory

interface ByteSource {
    fun read(offset: ULong, length: UInt): ByteArray?
}

object ModuleByteSource : ByteSource {
    val available: Boolean get() = BootInfo.moduleSize > 0UL

    override fun read(offset: ULong, length: UInt): ByteArray? {
        val size = BootInfo.moduleSize
        if (size == 0UL) return null
        if (offset >= size) return ByteArray(0)

        val available = size - offset
        val actual = minOf(length.toULong(), available).toInt()
        return RawMemory.readBytes(BootInfo.moduleAddress + offset, actual)
    }
}
