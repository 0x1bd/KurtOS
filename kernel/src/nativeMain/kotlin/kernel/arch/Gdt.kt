package kernel.arch

import hal.Arch
import hal.RawMemory

object Gdt {
    const val KERNEL_CODE: UShort = 0x08u
    const val KERNEL_DATA: UShort = 0x10u
    const val TSS_SELECTOR: UShort = 0x28u

    const val IST_IRQ = 1
    const val IST_FAULT = 2
    const val IST_ABORT = 3

    private const val IST_STACK_SIZE = 16384
    private const val TSS_SIZE = 104

    private val irqStack = NativeBuffer(IST_STACK_SIZE)
    private val faultStack = NativeBuffer(IST_STACK_SIZE)
    private val abortStack = NativeBuffer(IST_STACK_SIZE)

    private val tss = NativeBuffer(TSS_SIZE)
    private val gdt = NativeBuffer(7 * 8)
    private val descriptor = NativeBuffer(10)

    fun install() {
        RawMemory.write64(tss.address + 36u, irqStack.end)
        RawMemory.write64(tss.address + 44u, faultStack.end)
        RawMemory.write64(tss.address + 52u, abortStack.end)
        RawMemory.write16(tss.address + 102u, TSS_SIZE.toUShort())

        RawMemory.write64(gdt.address + 0u, 0UL)
        RawMemory.write64(gdt.address + 8u, entry(0x9Au, 0xAu))
        RawMemory.write64(gdt.address + 16u, entry(0x92u, 0xCu))
        RawMemory.write64(gdt.address + 24u, entry(0xF2u, 0xCu))
        RawMemory.write64(gdt.address + 32u, entry(0xFAu, 0xAu))

        val base = tss.address
        val limit = (TSS_SIZE - 1).toULong()
        var low = 0UL
        low = low or (limit and 0xFFFFUL)
        low = low or ((base and 0xFFFFFFUL) shl 16)
        low = low or (0x89UL shl 40)
        low = low or (((limit shr 16) and 0xFUL) shl 48)
        low = low or (((base shr 24) and 0xFFUL) shl 56)
        RawMemory.write64(gdt.address + 40u, low)
        RawMemory.write64(gdt.address + 48u, base shr 32)

        RawMemory.write16(descriptor.address, (7 * 8 - 1).toUShort())
        RawMemory.write64(descriptor.address + 2u, gdt.address)

        Arch.loadGdt(descriptor.address, KERNEL_CODE, KERNEL_DATA)
        Arch.loadTaskRegister(TSS_SELECTOR)
    }

    private fun entry(access: UInt, flags: UInt): ULong {
        var d = 0xFFFFUL
        d = d or (access.toULong() shl 40)
        d = d or (0xFUL shl 48)
        d = d or ((flags.toULong() and 0xFUL) shl 52)
        return d
    }
}
