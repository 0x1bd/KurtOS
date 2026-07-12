package kernel.arch

import hal.Arch
import hal.RawMemory

object Idt {
    const val VECTOR_TIMER = 0x20
    const val VECTOR_KEYBOARD = 0x21
    const val VECTOR_USB = 0x22
    const val VECTOR_SPURIOUS = 0xFF

    private const val ENTRY_SIZE = 16
    private const val GATE_INTERRUPT: UInt = 0x8Eu

    private val idt = NativeBuffer(256 * ENTRY_SIZE)
    private val descriptor = NativeBuffer(10)

    fun install() {
        for (vector in 0 until 256) {
            setGate(vector, Arch.isrStub(vector), istFor(vector))
        }

        RawMemory.write16(descriptor.address, (256 * ENTRY_SIZE - 1).toUShort())
        RawMemory.write64(descriptor.address + 2u, idt.address)

        Arch.loadIdt(descriptor.address)
    }

    private fun istFor(vector: Int): UByte = when {
        vector == 2 || vector == 8 || vector == 18 -> Gdt.IST_ABORT.toUByte()
        vector < 32 -> Gdt.IST_FAULT.toUByte()
        else -> Gdt.IST_IRQ.toUByte()
    }

    private fun setGate(vector: Int, handler: ULong, ist: UByte) {
        val base = idt.address + (vector * ENTRY_SIZE).toULong()
        RawMemory.write16(base, (handler and 0xFFFFUL).toUShort())
        RawMemory.write16(base + 2u, Gdt.KERNEL_CODE)
        RawMemory.write8(base + 4u, ist)
        RawMemory.write8(base + 5u, GATE_INTERRUPT.toUByte())
        RawMemory.write16(base + 6u, ((handler shr 16) and 0xFFFFUL).toUShort())
        RawMemory.write32(base + 8u, ((handler shr 32) and 0xFFFFFFFFUL).toUInt())
        RawMemory.write32(base + 12u, 0u)
    }
}
