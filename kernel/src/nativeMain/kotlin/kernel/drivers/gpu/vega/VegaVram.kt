package kernel.drivers.gpu.vega

import kernel.drivers.gpu.GpuLog

import hal.RawMemory

class VramAlloc(val gpuAddress: ULong, val cpuAddress: ULong, val bytes: ULong) {
    fun zero() = RawMemory.zero(cpuAddress, bytes)

    fun readDword(offset: ULong): UInt = RawMemory.read32(cpuAddress + offset)

    fun writeDword(offset: ULong, value: UInt) = RawMemory.write32(cpuAddress + offset, value)
}

object VegaVram {
    private var regionCpu: ULong = 0UL
    private var regionGpu: ULong = 0UL
    private var regionBytes: ULong = 0UL
    private var cursor: ULong = 0UL

    fun initialize(): Boolean {
        val window = GpuService.vramWindow
        val windowBytes = GpuService.vramWindowBytes
        if (window == 0UL || windowBytes < DRIVER_RESERVE) return false

        var usable = if (GpuService.carveoutBytes in 1UL until windowBytes) GpuService.carveoutBytes else windowBytes

        val scanout = GpuService.scanoutOffset
        if (scanout != ULong.MAX_VALUE && scanout < usable && scanout >= usable - DRIVER_RESERVE) {
            GpuLog.info("vram driver region moved below scanout at +${GpuLog.hex(scanout)}")
            usable = scanout and 0xFFFUL.inv()
        }

        if (usable < DRIVER_RESERVE) return false

        regionBytes = DRIVER_RESERVE
        regionCpu = window + usable - DRIVER_RESERVE
        regionGpu = GpuService.carveoutBase + usable - DRIVER_RESERVE
        cursor = 0UL

        GpuLog.info("vram driver region gpu ${GpuLog.hex(regionGpu)} cpu window +${GpuLog.hex(usable - DRIVER_RESERVE)} (${GpuLog.mib(regionBytes)})")
        return true
    }

    fun allocate(bytes: ULong, align: ULong = 0x1000UL): VramAlloc? {
        if (regionBytes == 0UL) return null

        val alignedGpu = (regionGpu + cursor + align - 1UL) and (align - 1UL).inv()
        val at = alignedGpu - regionGpu
        if (at + bytes > regionBytes) return null

        cursor = at + bytes
        val alloc = VramAlloc(regionGpu + at, regionCpu + at, bytes)
        alloc.zero()
        return alloc
    }

    private const val DRIVER_RESERVE: ULong = 0x2000000UL
}
