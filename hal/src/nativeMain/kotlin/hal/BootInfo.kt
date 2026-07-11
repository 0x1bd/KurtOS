package hal

import kotlinx.cinterop.ExperimentalForeignApi
import mmio.boot_fb_address
import mmio.boot_fb_blue_shift
import mmio.boot_fb_green_shift
import mmio.boot_fb_height
import mmio.boot_fb_pitch
import mmio.boot_fb_present
import mmio.boot_fb_red_shift
import mmio.boot_fb_width
import mmio.boot_heap_end
import mmio.boot_heap_start
import mmio.boot_hhdm
import mmio.boot_memmap_base
import mmio.boot_memmap_count
import mmio.boot_memmap_length
import mmio.boot_memmap_type
import mmio.boot_module_address
import mmio.boot_module_size
import mmio.boot_pages_end
import mmio.boot_pages_start
import mmio.boot_rsdp
import mmio.heap_total
import mmio.heap_used

enum class MemoryKind {
    Usable,
    Reserved,
    AcpiReclaimable,
    AcpiNvs,
    Bad,
    BootloaderReclaimable,
    Executable,
    Framebuffer,
    Unknown,
}

data class MemoryRegion(val base: ULong, val length: ULong, val kind: MemoryKind)

data class FramebufferInfo(
    val address: ULong,
    val width: UInt,
    val height: UInt,
    val pitch: UInt,
    val redShift: Int,
    val greenShift: Int,
    val blueShift: Int,
)

@OptIn(ExperimentalForeignApi::class)
object BootInfo {
    val hhdmOffset: ULong get() = boot_hhdm()

    val heapStart: ULong get() = boot_heap_start()

    val heapEnd: ULong get() = boot_heap_end()

    val heapUsed: ULong get() = heap_used()

    val heapTotal: ULong get() = heap_total()

    val pagesStart: ULong get() = boot_pages_start()

    val pagesEnd: ULong get() = boot_pages_end()

    val rsdpAddress: ULong get() = boot_rsdp()

    val moduleAddress: ULong get() = boot_module_address()

    val moduleSize: ULong get() = boot_module_size()

    fun toVirtual(physical: ULong): ULong = physical + hhdmOffset

    val framebuffer: FramebufferInfo?
        get() {
            if (boot_fb_present() == 0u) return null
            return FramebufferInfo(
                address = boot_fb_address(),
                width = boot_fb_width().toUInt(),
                height = boot_fb_height().toUInt(),
                pitch = boot_fb_pitch().toUInt(),
                redShift = boot_fb_red_shift().toInt(),
                greenShift = boot_fb_green_shift().toInt(),
                blueShift = boot_fb_blue_shift().toInt(),
            )
        }

    val memoryMap: List<MemoryRegion>
        get() {
            val count = boot_memmap_count()
            val regions = mutableListOf<MemoryRegion>()
            var i = 0UL
            while (i < count) {
                regions.add(
                    MemoryRegion(
                        base = boot_memmap_base(i),
                        length = boot_memmap_length(i),
                        kind = kindOf(boot_memmap_type(i)),
                    )
                )
                i++
            }
            return regions
        }

    private fun kindOf(type: ULong): MemoryKind = when (type) {
        0UL -> MemoryKind.Usable
        1UL -> MemoryKind.Reserved
        2UL -> MemoryKind.AcpiReclaimable
        3UL -> MemoryKind.AcpiNvs
        4UL -> MemoryKind.Bad
        5UL -> MemoryKind.BootloaderReclaimable
        6UL -> MemoryKind.Executable
        7UL -> MemoryKind.Framebuffer
        else -> MemoryKind.Unknown
    }
}
