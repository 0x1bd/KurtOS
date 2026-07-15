@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package n64.core

import kotlinx.cinterop.get
import kotlinx.cinterop.set
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toLong

internal var cpuHostRef: N64? = null

private fun cpuHostN64(): N64 = cpuHostRef!!

private fun refreshLimit(n64: N64) {
    val ctx = n64.dynarec!!.ctx
    val deadline = n64.cpu.frameDeadline - 1
    val next = n64.cpu.nextEventCount
    ctx[CTX_LIMIT / 8] = if (deadline < next) deadline else next
}

private fun hostRead32(phys: Int): Int {
    val n64 = cpuHostN64()
    val ctx = n64.dynarec!!.ctx
    n64.cpu.count = ctx[1]
    val value = n64.read32(phys)
    ctx[1] = n64.cpu.count
    refreshLimit(n64)
    return value
}

private fun hostRead8(phys: Int): Int {
    val word = hostRead32(phys and 3.inv())
    return (word ushr (24 - ((phys and 3) shl 3))) and 0xFF
}

private fun hostRead16(phys: Int): Int {
    val word = hostRead32(phys and 3.inv())
    return (word ushr (16 - ((phys and 2) shl 3))) and 0xFFFF
}

private fun hostRead64(phys: Int): Long {
    val high = hostRead32(phys).toLong() shl 32
    return high or (hostRead32(phys + 4).toLong() and 0xFFFFFFFFL)
}

private fun hostWriteCommon(phys: Int, value: Int, mask: Int) {
    val n64 = cpuHostN64()
    val ctx = n64.dynarec!!.ctx
    n64.cpu.count = ctx[1]
    n64.write32(phys, value, mask)
    ctx[1] = n64.cpu.count
    ctx[6] = if (n64.mi.initMode) 1L else 0L
    refreshLimit(n64)
}

private fun hostWrite32(phys: Int, value: Int) = hostWriteCommon(phys, value, -1)

private fun hostWrite8(phys: Int, value: Int) {
    val shift = 24 - ((phys and 3) shl 3)
    hostWriteCommon(phys and 3.inv(), (value and 0xFF) shl shift, 0xFF shl shift)
}

private fun hostWrite16(phys: Int, value: Int) {
    val shift = 16 - ((phys and 2) shl 3)
    hostWriteCommon(phys and 3.inv(), (value and 0xFFFF) shl shift, 0xFFFF shl shift)
}

private fun hostWrite64(phys: Int, value: Long) {
    hostWriteCommon(phys, (value ushr 32).toInt(), -1)
    hostWriteCommon(phys + 4, value.toInt(), -1)
}

private fun hostWriteMasked(phys: Int, value: Int, mask: Int) = hostWriteCommon(phys, value, mask)

private fun hostInvalidate(page: Int) {
    cpuHostN64().dynarec!!.invalidatePage(page)
}

internal fun cpuCallbacks(): CpuCallbacks = CpuCallbacks(
    read8 = staticCFunction(::hostRead8).toLong(),
    read16 = staticCFunction(::hostRead16).toLong(),
    read32 = staticCFunction(::hostRead32).toLong(),
    read64 = staticCFunction(::hostRead64).toLong(),
    write8 = staticCFunction(::hostWrite8).toLong(),
    write16 = staticCFunction(::hostWrite16).toLong(),
    write32 = staticCFunction(::hostWrite32).toLong(),
    write64 = staticCFunction(::hostWrite64).toLong(),
    writeMasked = staticCFunction(::hostWriteMasked).toLong(),
    invalidate = staticCFunction(::hostInvalidate).toLong(),
)
