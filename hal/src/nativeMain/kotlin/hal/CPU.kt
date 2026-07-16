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
import mmio.cpu_invlpg
import mmio.cpu_read_cr2
import mmio.cpu_read_cr3
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

    fun readCr3(): ULong = cpu_read_cr3()

    fun invalidatePage(address: ULong) = cpu_invlpg(address)

    fun readMsr(msr: UInt): ULong = msr_read(msr)

    fun writeMsr(msr: UInt, value: ULong) = msr_write(msr, value)

    private fun isAmd(): Boolean {
        val v = cpuid(0u)
        return v.ebx == 0x68747541u && v.ecx == 0x444D4163u && v.edx == 0x69746E65u
    }

    private fun isHypervisor(): Boolean = (cpuid(1u).ecx and (1u shl 31)) != 0u

    fun requestMaxPerformance() {
        if (!isAmd() || isHypervisor()) return
        val hwcr = readMsr(MSR_HWCR)
        writeMsr(MSR_HWCR, hwcr and (1UL shl 25).inv())
        writeMsr(MSR_PSTATE_CTL, 0UL)
    }

    fun hasEffectiveFrequency(): Boolean {
        val highBasic = cpuid(0u).eax
        if (highBasic >= 0x6u && (cpuid(0x6u).ecx and 1u) != 0u) return true
        val highExt = cpuid(0x80000000u).eax
        if (highExt >= 0x80000007u && (cpuid(0x80000007u).edx and (1u shl 10)) != 0u) return true
        return false
    }

    fun activeCycles(): ULong = readMsr(MSR_APERF)

    fun referenceCycles(): ULong = readMsr(MSR_MPERF)

    private const val MSR_HWCR: UInt = 0xC0010015u
    private const val MSR_PSTATE_CTL: UInt = 0xC0010062u
    private const val MSR_APERF: UInt = 0xE8u
    private const val MSR_MPERF: UInt = 0xE7u

    fun cpuid(leaf: UInt): CpuidResult = memScoped {
        val a = alloc<UIntVar>()
        val b = alloc<UIntVar>()
        val c = alloc<UIntVar>()
        val d = alloc<UIntVar>()
        cpu_cpuid(leaf, a.ptr, b.ptr, c.ptr, d.ptr)
        CpuidResult(a.value, b.value, c.value, d.value)
    }

    fun brand(): String {
        val highest = cpuid(0x80000000u).eax
        if (highest < 0x80000004u) return "unknown cpu"

        val builder = StringBuilder()
        for (leaf in 0x80000002u..0x80000004u) {
            val result = cpuid(leaf)
            appendRegister(builder, result.eax)
            appendRegister(builder, result.ebx)
            appendRegister(builder, result.ecx)
            appendRegister(builder, result.edx)
        }
        return builder.toString().trim()
    }

    private fun appendRegister(builder: StringBuilder, value: UInt) {
        for (shift in 0 until 4) {
            val code = ((value shr (shift * 8)) and 0xFFu).toInt()
            if (code in 0x20..0x7E) builder.append(code.toChar())
        }
    }
}
