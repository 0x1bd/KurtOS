package kernel.memory

import hal.BootInfo
import hal.Cpu
import hal.RawMemory

enum class MemType {
    Uncached,
    WriteCombining,
}

object Mmio {
    private var next = WINDOW_BASE

    fun map(physical: ULong, bytes: ULong, memType: MemType = MemType.Uncached): ULong {
        if (physical == 0UL || bytes == 0UL) return 0UL

        val start = physical and LARGE_MASK.inv()
        val end = alignUp(physical + bytes, LARGE_SIZE)
        val offset = physical - start

        val virtual = next
        var current = virtual
        var frame = start

        while (frame < end) {
            if (!mapLarge(current, frame, flagsFor(memType))) return 0UL
            Cpu.invalidatePage(current)

            current += LARGE_SIZE
            frame += LARGE_SIZE
        }

        next = current
        return virtual + offset
    }

    private fun flagsFor(memType: MemType): ULong = when (memType) {
        MemType.Uncached -> LARGE_FLAGS
        MemType.WriteCombining -> WC_FLAGS
    }

    private fun mapLarge(virtual: ULong, physical: ULong, flags: ULong): Boolean {
        val root = BootInfo.toVirtual(Cpu.readCr3() and ADDRESS_MASK)

        val directoryPointer = descend(root, index(virtual, 39)) ?: return false
        val directory = descend(directoryPointer, index(virtual, 30)) ?: return false

        val entry = directory + index(virtual, 21).toULong() * 8UL
        RawMemory.write64(entry, (physical and ADDRESS_MASK) or flags)

        return true
    }

    private fun descend(table: ULong, slot: Int): ULong? {
        val entry = table + slot.toULong() * 8UL
        val value = RawMemory.read64(entry)

        if (value and PRESENT != 0UL) {
            if (value and HUGE != 0UL) return null
            return BootInfo.toVirtual(value and ADDRESS_MASK)
        }

        val page = PageAllocator.allocatePages(1) ?: return null
        page.zero()

        val physical = BootInfo.toPhysical(page.address)
        RawMemory.write64(entry, physical or TABLE_FLAGS)

        return page.address
    }

    private fun index(virtual: ULong, shift: Int): Int =
        ((virtual shr shift) and 0x1FFUL).toInt()

    private fun alignUp(value: ULong, alignment: ULong): ULong =
        (value + alignment - 1UL) and (alignment - 1UL).inv()

    private const val WINDOW_BASE: ULong = 0xFFFFFFFF00000000UL

    private const val LARGE_SIZE: ULong = 0x200000UL
    private const val LARGE_MASK: ULong = 0x1FFFFFUL

    private const val ADDRESS_MASK: ULong = 0x000FFFFFFFFFF000UL

    private const val PRESENT: ULong = 0x1UL
    private const val WRITABLE: ULong = 0x2UL
    private const val WRITE_THROUGH: ULong = 0x8UL
    private const val CACHE_DISABLE: ULong = 0x10UL
    private const val HUGE: ULong = 0x80UL

    private const val TABLE_FLAGS: ULong = 0x3UL
    private const val LARGE_FLAGS: ULong = 0x9BUL
    private const val WC_FLAGS: ULong = 0x8BUL
}
