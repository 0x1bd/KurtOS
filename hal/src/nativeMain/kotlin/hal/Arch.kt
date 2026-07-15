package hal

import kotlinx.cinterop.ExperimentalForeignApi
import mmio.isr_stub
import mmio.kbd_irq_count
import mmio.kbd_ring_overflows
import mmio.kbd_ring_pop
import mmio.lapic_base_set
import mmio.lgdt_load
import mmio.lidt_load
import mmio.ltr_load
import mmio.timer_ticks
import mmio.usb_irq_count

@OptIn(ExperimentalForeignApi::class)
object Arch {
    fun isrStub(vector: Int): ULong = isr_stub(vector.toULong())

    fun loadGdt(descriptorAddress: ULong, codeSelector: UShort, dataSelector: UShort) =
        lgdt_load(descriptorAddress.toCPointer(), codeSelector, dataSelector)

    fun loadTaskRegister(selector: UShort) = ltr_load(selector)

    fun loadIdt(descriptorAddress: ULong) = lidt_load(descriptorAddress.toCPointer())

    fun setLapicBase(base: ULong) = lapic_base_set(base)

    fun ticks(): ULong = timer_ticks()

    fun nextScancode(): Int = kbd_ring_pop()

    fun droppedScancodes(): ULong = kbd_ring_overflows()

    fun keyboardInterrupts(): ULong = kbd_irq_count()

    fun usbInterrupts(): ULong = usb_irq_count()
}

@OptIn(ExperimentalForeignApi::class)
private fun ULong.toCPointer(): kotlinx.cinterop.COpaquePointer? =
    kotlinx.cinterop.interpretCPointer(kotlinx.cinterop.NativePtr.NULL.plus(this.toLong()))
