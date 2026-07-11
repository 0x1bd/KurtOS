package kernel.memory

import hal.BootInfo
import hal.RawMemory

private const val PAGE_SIZE: UInt = 4096u

class Region internal constructor(val address: ULong, val size: UInt, internal val pages: Int) {
    fun zero() = RawMemory.zero(address, size.toULong())
}

object PageAllocator {
    private var base: ULong = 0UL
    private var pages: BooleanArray = BooleanArray(0)
    private var initialized = false

    val pageSize: UInt get() = PAGE_SIZE

    val totalPages: Int get() = pages.size

    val freePages: Int get() = pages.count { !it }

    fun initialize() {
        if (initialized) return
        initialized = true

        base = alignUp(BootInfo.pagesStart, PAGE_SIZE.toULong())
        val end = alignDown(BootInfo.pagesEnd, PAGE_SIZE.toULong())
        val count = if (end > base) ((end - base) / PAGE_SIZE.toULong()).toInt() else 0
        pages = BooleanArray(count)
    }

    fun allocatePages(count: Int): Region? {
        if (count <= 0) return null
        initialize()

        var runStart = -1
        var runLength = 0

        for (i in pages.indices) {
            if (pages[i]) {
                runStart = -1
                runLength = 0
                continue
            }

            if (runStart == -1) runStart = i
            runLength++

            if (runLength == count) {
                for (page in runStart until runStart + count) pages[page] = true
                val region = Region(
                    base + runStart.toULong() * PAGE_SIZE.toULong(),
                    count.toUInt() * PAGE_SIZE,
                    count,
                )
                region.zero()
                return region
            }
        }

        return null
    }

    fun allocateBytes(size: UInt): Region? =
        allocatePages(((size + PAGE_SIZE - 1u) / PAGE_SIZE).toInt())

    fun free(region: Region) {
        initialize()
        val start = ((region.address - base) / PAGE_SIZE.toULong()).toInt()
        for (i in start until start + region.pages) {
            if (i in pages.indices) pages[i] = false
        }
    }

    private fun alignUp(value: ULong, alignment: ULong): ULong =
        (value + alignment - 1UL) and (alignment - 1UL).inv()

    private fun alignDown(value: ULong, alignment: ULong): ULong =
        value and (alignment - 1UL).inv()
}
