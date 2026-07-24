package hal.x86

import hal.ArchOps
import hal.BootSource
import hal.CpuOps
import hal.CpuidResult
import hal.Platform
import hal.PortIo
import hal.RawMemoryOps
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.NativePtr
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.interpretCPointer
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import mmio.boot_fb_address
import mmio.boot_fb_blue_shift
import mmio.boot_fb_green_shift
import mmio.boot_fb_height
import mmio.boot_fb_pitch
import mmio.boot_fb_present
import mmio.boot_fb_red_shift
import mmio.boot_fb_width
import mmio.boot_gpu_pool_end
import mmio.boot_gpu_pool_start
import mmio.boot_heap_end
import mmio.boot_heap_start
import mmio.boot_hhdm
import mmio.boot_memmap_base
import mmio.boot_memmap_count
import mmio.boot_memmap_length
import mmio.boot_memmap_type
import mmio.boot_pages_end
import mmio.boot_pages_start
import mmio.boot_rsdp
import mmio.cpu_cpuid
import mmio.cpu_disable_interrupts
import mmio.cpu_enable_interrupts
import mmio.cpu_halt
import mmio.cpu_hang
import mmio.cpu_invlpg
import mmio.cpu_rdtsc
import mmio.cpu_read_cr2
import mmio.cpu_read_cr3
import mmio.cpu_sfence
import mmio.heap_total
import mmio.heap_used
import mmio.io_wait
import mmio.isr_stub
import mmio.kbd_irq_count
import mmio.kbd_poll_set
import mmio.kbd_ring_overflows
import mmio.kbd_ring_pop
import mmio.lapic_base_set
import mmio.lgdt_load
import mmio.lidt_load
import mmio.ltr_load
import mmio.msr_read
import mmio.msr_write
import mmio.port_in16
import mmio.port_in32
import mmio.port_in8
import mmio.port_out16
import mmio.port_out32
import mmio.port_out8
import mmio.raw_blit_high
import mmio.raw_blit_indexed
import mmio.raw_copy
import mmio.raw_copy_stream
import mmio.raw_fill32
import mmio.raw_read16
import mmio.raw_read32
import mmio.raw_read64
import mmio.raw_read8
import mmio.raw_write16
import mmio.raw_write32
import mmio.raw_write64
import mmio.raw_write8
import mmio.raw_zero
import mmio.smp_cpus
import mmio.smp_start
import mmio.timer_ticks
import mmio.usb_irq_count

@OptIn(ExperimentalForeignApi::class)
private fun ULong.toCPointer(): COpaquePointer? =
    interpretCPointer(NativePtr.NULL.plus(this.toLong()))

@OptIn(ExperimentalForeignApi::class)
private object X86PortIo : PortIo {
    override fun read8(port: UShort): UByte = port_in8(port)

    override fun write8(port: UShort, value: UByte) = port_out8(port, value)

    override fun read16(port: UShort): UShort = port_in16(port)

    override fun write16(port: UShort, value: UShort) = port_out16(port, value)

    override fun read32(port: UShort): UInt = port_in32(port)

    override fun write32(port: UShort, value: UInt) = port_out32(port, value)

    override fun wait() = io_wait()
}

@OptIn(ExperimentalForeignApi::class)
private object X86RawMemory : RawMemoryOps {
    override fun read8(address: ULong): UByte = raw_read8(address)

    override fun read16(address: ULong): UShort = raw_read16(address)

    override fun read32(address: ULong): UInt = raw_read32(address)

    override fun read64(address: ULong): ULong = raw_read64(address)

    override fun write8(address: ULong, value: UByte) = raw_write8(address, value)

    override fun write16(address: ULong, value: UShort) = raw_write16(address, value)

    override fun write32(address: ULong, value: UInt) = raw_write32(address, value)

    override fun write64(address: ULong, value: ULong) = raw_write64(address, value)

    override fun fill32(address: ULong, value: UInt, count: ULong) = raw_fill32(address, value, count)

    override fun copy(destination: ULong, source: ULong, bytes: ULong) = raw_copy(destination, source, bytes)

    override fun copyStream(destination: ULong, source: ULong, bytes: ULong) =
        raw_copy_stream(destination, source, bytes)

    override fun zero(address: ULong, length: ULong) = raw_zero(address, length)

    override fun blitIndexed(
        destination: ULong,
        destinationStride: ULong,
        source: ULong,
        sourceWidth: UInt,
        sourceHeight: UInt,
        palette: ULong,
        scale: UInt,
    ) = raw_blit_indexed(destination, destinationStride, source, sourceWidth, sourceHeight, palette, scale)

    override fun blitHigh(
        destination: ULong,
        destinationStride: ULong,
        source: ULong,
        sourceWidth: UInt,
        sourceHeight: UInt,
        palette: ULong,
        scale: UInt,
    ) = raw_blit_high(destination, destinationStride, source, sourceWidth, sourceHeight, palette, scale)
}

@OptIn(ExperimentalForeignApi::class)
private object X86Cpu : CpuOps {
    override fun enableInterrupts() = cpu_enable_interrupts()

    override fun disableInterrupts() = cpu_disable_interrupts()

    override fun waitForInterrupt() = cpu_halt()

    override fun hang() = cpu_hang()

    override fun timestamp(): ULong = cpu_rdtsc()

    override fun readCr2(): ULong = cpu_read_cr2()

    override fun readCr3(): ULong = cpu_read_cr3()

    override fun invalidatePage(address: ULong) = cpu_invlpg(address)

    override fun readMsr(msr: UInt): ULong = msr_read(msr)

    override fun writeMsr(msr: UInt, value: ULong) = msr_write(msr, value)

    override fun storeFence() = cpu_sfence()

    override fun cpuid(leaf: UInt): CpuidResult = memScoped {
        val a = alloc<UIntVar>()
        val b = alloc<UIntVar>()
        val c = alloc<UIntVar>()
        val d = alloc<UIntVar>()
        cpu_cpuid(leaf, a.ptr, b.ptr, c.ptr, d.ptr)
        CpuidResult(a.value, b.value, c.value, d.value)
    }
}

@OptIn(ExperimentalForeignApi::class)
private object X86Arch : ArchOps {
    override fun isrStub(vector: Int): ULong = isr_stub(vector.toULong())

    override fun loadGdt(descriptorAddress: ULong, codeSelector: UShort, dataSelector: UShort) =
        lgdt_load(descriptorAddress.toCPointer(), codeSelector, dataSelector)

    override fun loadTaskRegister(selector: UShort) = ltr_load(selector)

    override fun loadIdt(descriptorAddress: ULong) = lidt_load(descriptorAddress.toCPointer())

    override fun setLapicBase(base: ULong) = lapic_base_set(base)

    override fun ticks(): ULong = timer_ticks()

    override fun nextScancode(): Int = kbd_ring_pop()

    override fun enableKeyboardPoll() = kbd_poll_set(1u)

    override fun droppedScancodes(): ULong = kbd_ring_overflows()

    override fun keyboardInterrupts(): ULong = kbd_irq_count()

    override fun usbInterrupts(): ULong = usb_irq_count()

    override fun smpStart(): Int = smp_start()

    override fun smpCpus(): Int = smp_cpus()
}

@OptIn(ExperimentalForeignApi::class)
private object X86Boot : BootSource {
    override val hhdmOffset: ULong get() = boot_hhdm()

    override val heapStart: ULong get() = boot_heap_start()

    override val heapEnd: ULong get() = boot_heap_end()

    override val heapUsed: ULong get() = heap_used()

    override val heapTotal: ULong get() = heap_total()

    override val pagesStart: ULong get() = boot_pages_start()

    override val pagesEnd: ULong get() = boot_pages_end()

    override val gpuPoolStart: ULong get() = boot_gpu_pool_start()

    override val gpuPoolEnd: ULong get() = boot_gpu_pool_end()

    override val rsdpAddress: ULong get() = boot_rsdp()

    override val framebufferPresent: UInt get() = boot_fb_present()

    override val framebufferAddress: ULong get() = boot_fb_address()

    override val framebufferWidth: ULong get() = boot_fb_width()

    override val framebufferHeight: ULong get() = boot_fb_height()

    override val framebufferPitch: ULong get() = boot_fb_pitch()

    override val framebufferRedShift: UInt get() = boot_fb_red_shift()

    override val framebufferGreenShift: UInt get() = boot_fb_green_shift()

    override val framebufferBlueShift: UInt get() = boot_fb_blue_shift()

    override val memoryMapCount: ULong get() = boot_memmap_count()

    override fun memoryMapBase(index: ULong): ULong = boot_memmap_base(index)

    override fun memoryMapLength(index: ULong): ULong = boot_memmap_length(index)

    override fun memoryMapType(index: ULong): ULong = boot_memmap_type(index)
}

object X86Platform : Platform {
    override val port: PortIo get() = X86PortIo

    override val memory: RawMemoryOps get() = X86RawMemory

    override val cpu: CpuOps get() = X86Cpu

    override val arch: ArchOps get() = X86Arch

    override val boot: BootSource get() = X86Boot
}
