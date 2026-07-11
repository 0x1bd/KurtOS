package kernel.arch

import hal.Arch
import hal.BootInfo
import hal.Cpu
import hal.RawMemory

object Apic {
    private const val REG_ID: ULong = 0x020u
    private const val REG_EOI: ULong = 0x0B0u
    private const val REG_SPURIOUS: ULong = 0x0F0u
    private const val REG_LVT_TIMER: ULong = 0x320u
    private const val REG_TIMER_INITIAL: ULong = 0x380u
    private const val REG_TIMER_CURRENT: ULong = 0x390u
    private const val REG_TIMER_DIVIDE: ULong = 0x3E0u

    private const val SOFTWARE_ENABLE: UInt = 0x100u
    private const val LVT_MASKED: UInt = 0x10000u
    private const val LVT_PERIODIC: UInt = 0x20000u

    private const val MSR_APIC_BASE: UInt = 0x1Bu
    private const val TIMER_HZ = 1000u
    private const val DIVIDE_BY_16: UInt = 0x3u

    private var base: ULong = 0UL
    private var calibratedHz: UInt = 0u

    var calibrationSource: String = "none"
        private set

    val timerHz: UInt get() = calibratedHz

    fun localId(): UInt = if (base == 0UL) 0u else read(REG_ID) shr 24

    fun initialize() {
        val msr = Cpu.readMsr(MSR_APIC_BASE)
        val physical = msr and 0xFFFFFF000UL
        base = BootInfo.toVirtual(physical)

        Cpu.writeMsr(MSR_APIC_BASE, msr or (1UL shl 11))

        Arch.setLapicBase(base)

        write(REG_SPURIOUS, SOFTWARE_ENABLE or Idt.VECTOR_SPURIOUS.toUInt())

        calibratedHz = calibrate()

        var initial = calibratedHz / TIMER_HZ
        if (initial == 0u) initial = 1u

        write(REG_TIMER_DIVIDE, DIVIDE_BY_16)
        write(REG_LVT_TIMER, Idt.VECTOR_TIMER.toUInt() or LVT_PERIODIC)
        write(REG_TIMER_INITIAL, initial)
    }

    private fun calibrate(): UInt {
        if (Pit.isPresent()) {
            write(REG_TIMER_DIVIDE, DIVIDE_BY_16)
            write(REG_LVT_TIMER, LVT_MASKED)
            write(REG_TIMER_INITIAL, 0xFFFFFFFFu)

            Pit.waitMicros(10_000u)

            val remaining = read(REG_TIMER_CURRENT)
            write(REG_TIMER_INITIAL, 0u)

            val elapsed = 0xFFFFFFFFu - remaining
            if (elapsed > 0u) {
                calibrationSource = "pit"
                return elapsed * 100u
            }
        }

        val leaf = Cpu.cpuid(0u)
        if (leaf.eax >= 0x15u) {
            val crystal = Cpu.cpuid(0x15u).ecx
            if (crystal > 0u) {
                calibrationSource = "cpuid"
                return crystal / 16u
            }
        }

        calibrationSource = "fallback"
        return 100_000_000u / 16u
    }

    private fun read(register: ULong): UInt = RawMemory.read32(base + register)

    private fun write(register: ULong, value: UInt) = RawMemory.write32(base + register, value)
}
