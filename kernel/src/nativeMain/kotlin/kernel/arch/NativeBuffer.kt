package kernel.arch

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.rawValue
import hal.RawMemory

@OptIn(ExperimentalForeignApi::class)
class NativeBuffer(val size: Int, alignment: Int = 16) {
    val address: ULong

    init {
        val raw = nativeHeap.allocArray<ByteVar>(size + alignment)
        val base = raw.rawValue.toLong().toULong()
        val mask = (alignment - 1).toULong()
        address = (base + mask) and mask.inv()
        RawMemory.zero(address, size.toULong())
    }

    val end: ULong get() = address + size.toULong()
}
