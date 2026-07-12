package kernel.drivers.usb

import hal.BootInfo
import hal.RawMemory
import kernel.memory.PageAllocator
import kernel.memory.Region

class XhciRing(private val region: Region, private val entries: Int, private val link: Boolean) {
    val address: ULong = region.address
    val physical: ULong = BootInfo.toPhysical(region.address)

    var cycle = 1
        private set

    private var index = 0

    init {
        region.zero()
        if (link) writeLink()
    }

    private fun writeLink() {
        val slot = address + ((entries - 1) * TRB_BYTES).toULong()

        RawMemory.write64(slot, physical)
        RawMemory.write32(slot + 8UL, 0u)
        RawMemory.write32(slot + 12UL, (TRB_LINK shl 10).toUInt() or TOGGLE_CYCLE)
    }

    fun enqueue(parameter: ULong, status: UInt, control: UInt): ULong {
        val slot = address + (index * TRB_BYTES).toULong()

        RawMemory.write64(slot, parameter)
        RawMemory.write32(slot + 8UL, status)
        RawMemory.write32(slot + 12UL, control or cycle.toUInt())

        val trb = physical + (index * TRB_BYTES).toULong()

        index++
        if (link && index == entries - 1) {
            val linkSlot = address + (index * TRB_BYTES).toULong()
            val current = RawMemory.read32(linkSlot + 12UL) and 1u.inv()
            RawMemory.write32(linkSlot + 12UL, current or cycle.toUInt())

            index = 0
            cycle = cycle xor 1
        }

        return trb
    }

    fun dequeuePointer(): ULong = physical + (index * TRB_BYTES).toULong()

    companion object {
        const val TRB_BYTES = 16
        const val TRB_LINK = 6

        private const val TOGGLE_CYCLE = 0x2u

        fun allocate(entries: Int, link: Boolean): XhciRing? {
            val bytes = (entries * TRB_BYTES).toUInt()
            val region = PageAllocator.allocateBytes(bytes) ?: return null
            return XhciRing(region, entries, link)
        }
    }
}

class XhciEventRing(private val region: Region, private val entries: Int) {
    val address: ULong = region.address
    val physical: ULong = BootInfo.toPhysical(region.address)

    private var cycle = 1
    private var index = 0

    init {
        region.zero()
    }

    fun pending(): Boolean {
        val slot = address + (index * XhciRing.TRB_BYTES).toULong()
        val control = RawMemory.read32(slot + 12UL)
        return (control and 1u).toInt() == cycle
    }

    fun parameter(): ULong = RawMemory.read64(address + (index * XhciRing.TRB_BYTES).toULong())

    fun status(): UInt = RawMemory.read32(address + (index * XhciRing.TRB_BYTES).toULong() + 8UL)

    fun control(): UInt = RawMemory.read32(address + (index * XhciRing.TRB_BYTES).toULong() + 12UL)

    fun advance() {
        index++
        if (index == entries) {
            index = 0
            cycle = cycle xor 1
        }
    }

    fun dequeuePointer(): ULong = physical + (index * XhciRing.TRB_BYTES).toULong()

    companion object {
        fun allocate(entries: Int): XhciEventRing? {
            val bytes = (entries * XhciRing.TRB_BYTES).toUInt()
            val region = PageAllocator.allocateBytes(bytes) ?: return null
            return XhciEventRing(region, entries)
        }
    }
}
