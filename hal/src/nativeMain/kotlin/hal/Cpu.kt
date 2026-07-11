package hal

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.cinterop.UIntVar
import mmio.cpu_cpuid
import mmio.cpu_disable_interrupts
import mmio.cpu_enable_interrupts
import mmio.cpu_halt
import mmio.cpu_hang
import mmio.cpu_rdtsc
import mmio.cpu_read_cr2
import mmio.msr_read
import mmio.msr_write

data class CpuidResult(val eax: UInt, val ebx: UInt, val ecx: UInt, val edx: UInt)

@OptIn(ExperimentalForeignApi::class)
object Cpu {
    fun enableInterrupts() = cpu_enable_interrupts()

    fun disableInterrupts() = cpu_disable_interrupts()

    fun waitForInterrupt() = cpu_halt()

    fun hang(): Nothing {
        cpu_hang()
        while (true) {
        }
    }

    fun timestamp(): ULong = cpu_rdtsc()

    fun readCr2(): ULong = cpu_read_cr2()

    fun readMsr(msr: UInt): ULong = msr_read(msr)

    fun writeMsr(msr: UInt, value: ULong) = msr_write(msr, value)

    fun cpuid(leaf: UInt): CpuidResult = memScoped {
        val a = alloc<UIntVar>()
        val b = alloc<UIntVar>()
        val c = alloc<UIntVar>()
        val d = alloc<UIntVar>()
        cpu_cpuid(leaf, a.ptr, b.ptr, c.ptr, d.ptr)
        CpuidResult(a.value, b.value, c.value, d.value)
    }
}
