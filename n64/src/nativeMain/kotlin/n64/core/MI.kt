package n64.core

const val MI_INIT_MODE = 0
const val MI_VERSION = 1
const val MI_INTR = 2
const val MI_INTR_MASK = 3

const val MI_INTR_SP = 1 shl 0
const val MI_INTR_SI = 1 shl 1
const val MI_INTR_AI = 1 shl 2
const val MI_INTR_VI = 1 shl 3
const val MI_INTR_PI = 1 shl 4
const val MI_INTR_DP = 1 shl 5

const val MI_INIT_MODE_BIT = 1 shl 7
const val MI_EBUS_MODE = 1 shl 8
const val MI_RDRAM_MODE = 1 shl 9
const val MI_INIT_LENGTH_MASK = 0x7F

class MI(private val n64: N64) {
    val regs = IntArray(4)
    var initMode = false

    fun reset() {
        regs.fill(0)
        regs[MI_VERSION] = 0x02020102
        initMode = false
    }

    fun read(reg: Int): Int {
        n64.cpu.addCycles(20)
        return if (reg < 4) regs[reg] else 0
    }

    fun write(reg: Int, value: Int, mask: Int) {
        when (reg) {
            MI_INIT_MODE -> writeInitMode(value)
            MI_INTR_MASK -> writeIntrMask(value)
            else -> if (reg < 4) regs[reg] = (regs[reg] and mask.inv()) or (value and mask)
        }

        if (regs[MI_INTR] and regs[MI_INTR_MASK] == 0) {
            n64.cpu.cop0[COP0_CAUSE] = n64.cpu.cop0[COP0_CAUSE] and COP0_CAUSE_IP2.inv()
        }
        n64.cpu.checkPendingInterrupts()
    }

    private fun writeInitMode(value: Int) {
        regs[MI_INIT_MODE] = (regs[MI_INIT_MODE] and MI_INIT_LENGTH_MASK.inv()) or (value and MI_INIT_LENGTH_MASK)

        if (value and (1 shl 7) != 0) {
            regs[MI_INIT_MODE] = regs[MI_INIT_MODE] and MI_INIT_MODE_BIT.inv()
            initMode = false
        }
        if (value and (1 shl 8) != 0) {
            regs[MI_INIT_MODE] = regs[MI_INIT_MODE] or MI_INIT_MODE_BIT
            initMode = true
        }
        if (value and (1 shl 9) != 0) regs[MI_INIT_MODE] = regs[MI_INIT_MODE] and MI_EBUS_MODE.inv()
        if (value and (1 shl 10) != 0) regs[MI_INIT_MODE] = regs[MI_INIT_MODE] or MI_EBUS_MODE
        if (value and (1 shl 11) != 0) clearInterrupt(MI_INTR_DP)
        if (value and (1 shl 12) != 0) regs[MI_INIT_MODE] = regs[MI_INIT_MODE] and MI_RDRAM_MODE.inv()
        if (value and (1 shl 13) != 0) regs[MI_INIT_MODE] = regs[MI_INIT_MODE] or MI_RDRAM_MODE
    }

    private fun writeIntrMask(value: Int) {
        var mask = regs[MI_INTR_MASK]
        for (bit in 0 until 6) {
            if (value and (1 shl (bit * 2)) != 0) mask = mask and (1 shl bit).inv()
            if (value and (1 shl (bit * 2 + 1)) != 0) mask = mask or (1 shl bit)
        }
        regs[MI_INTR_MASK] = mask
    }

    val debugRaised = IntArray(6)

    fun setInterrupt(interrupt: Int) {
        for (bit in 0 until 6) if (interrupt and (1 shl bit) != 0) debugRaised[bit]++
        regs[MI_INTR] = regs[MI_INTR] or interrupt
        n64.cpu.checkPendingInterrupts()
    }

    fun clearInterrupt(interrupt: Int) {
        regs[MI_INTR] = regs[MI_INTR] and interrupt.inv()
        if (regs[MI_INTR] and regs[MI_INTR_MASK] == 0) {
            n64.cpu.cop0[COP0_CAUSE] = n64.cpu.cop0[COP0_CAUSE] and COP0_CAUSE_IP2.inv()
        }
    }
}

class Ri {
    val regs = IntArray(8)
    private var ramInit = false

    fun reset() {
        regs.fill(0)
        regs[0] = 0x0E
        regs[1] = 0x40
        ramInit = false
    }

    fun read(reg: Int, n64: N64): Int {
        n64.cpu.addCycles(20)
        return when (reg) {
            3 -> {
                if (!ramInit) {
                    n64.cpu.addCycles(n64.clockRate / 2)
                    ramInit = true
                }
                0x14
            }

            4 -> 0x00063634
            else -> if (reg < 8) regs[reg] else 0
        }
    }

    fun write(reg: Int, value: Int, mask: Int) {
        if (reg == 3) ramInit = false
        if (reg < 8) regs[reg] = (regs[reg] and mask.inv()) or (value and mask)
    }
}
