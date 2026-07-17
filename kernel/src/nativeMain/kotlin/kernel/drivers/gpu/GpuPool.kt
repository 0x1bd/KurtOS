package kernel.drivers.gpu

import hal.BootInfo
import hal.RawMemory

class GpuRegion internal constructor(val address: ULong, val bytes: ULong, internal val firstPage: Int, internal val pages: Int) {
    val physical: ULong get() = BootInfo.toPhysical(address)

    fun zero() = RawMemory.zero(address, bytes)
}

object GpuPool {
    private var base: ULong = 0UL
    private var pages: BooleanArray = BooleanArray(0)

    val available: Boolean get() = pages.isNotEmpty()

    val totalBytes: ULong get() = pages.size.toULong() * PAGE_SIZE

    fun initialize(): Boolean {
        if (pages.isNotEmpty()) return true

        val start = BootInfo.gpuPoolStart
        val end = BootInfo.gpuPoolEnd
        if (start == 0UL || end <= start) return false

        base = start
        pages = BooleanArray(((end - start) / PAGE_SIZE).toInt())
        return true
    }

    fun allocate(bytes: ULong, alignPages: Int = 1): GpuRegion? {
        if (bytes == 0UL || pages.isEmpty()) return null

        val count = ((bytes + PAGE_SIZE - 1UL) / PAGE_SIZE).toInt()
        var candidate = 0

        while (candidate + count <= pages.size) {
            if (candidate % alignPages != 0) {
                candidate += alignPages - candidate % alignPages
                continue
            }

            var run = 0
            while (run < count && !pages[candidate + run]) run++

            if (run == count) {
                for (page in candidate until candidate + count) pages[page] = true
                val region = GpuRegion(base + candidate.toULong() * PAGE_SIZE, count.toULong() * PAGE_SIZE, candidate, count)
                region.zero()
                return region
            }

            candidate += run + 1
        }

        return null
    }

    fun free(region: GpuRegion) {
        for (page in region.firstPage until region.firstPage + region.pages) {
            if (page in pages.indices) pages[page] = false
        }
    }

    private const val PAGE_SIZE: ULong = 4096UL
}
