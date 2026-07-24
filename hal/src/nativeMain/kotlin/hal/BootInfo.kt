package hal

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

object BootInfo {
    val hhdmOffset: ULong get() = Hal.boot.hhdmOffset

    val heapStart: ULong get() = Hal.boot.heapStart

    val heapEnd: ULong get() = Hal.boot.heapEnd

    val heapUsed: ULong get() = Hal.boot.heapUsed

    val heapTotal: ULong get() = Hal.boot.heapTotal

    val pagesStart: ULong get() = Hal.boot.pagesStart

    val pagesEnd: ULong get() = Hal.boot.pagesEnd

    val gpuPoolStart: ULong get() = Hal.boot.gpuPoolStart

    val gpuPoolEnd: ULong get() = Hal.boot.gpuPoolEnd

    val rsdpAddress: ULong get() = Hal.boot.rsdpAddress

    fun toVirtual(physical: ULong): ULong = physical + hhdmOffset

    fun toPhysical(virtual: ULong): ULong = virtual - hhdmOffset

    val framebuffer: FramebufferInfo?
        get() {
            if (Hal.boot.framebufferPresent == 0u) return null
            return FramebufferInfo(
                address = Hal.boot.framebufferAddress,
                width = Hal.boot.framebufferWidth.toUInt(),
                height = Hal.boot.framebufferHeight.toUInt(),
                pitch = Hal.boot.framebufferPitch.toUInt(),
                redShift = Hal.boot.framebufferRedShift.toInt(),
                greenShift = Hal.boot.framebufferGreenShift.toInt(),
                blueShift = Hal.boot.framebufferBlueShift.toInt(),
            )
        }

    val memoryMap: List<MemoryRegion>
        get() {
            val count = Hal.boot.memoryMapCount
            val regions = mutableListOf<MemoryRegion>()
            var i = 0UL
            while (i < count) {
                regions.add(
                    MemoryRegion(
                        base = Hal.boot.memoryMapBase(i),
                        length = Hal.boot.memoryMapLength(i),
                        kind = kindOf(Hal.boot.memoryMapType(i)),
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
