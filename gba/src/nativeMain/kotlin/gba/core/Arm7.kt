package gba.core

import kapi.state.StateReader
import kapi.state.StateWriter

class Arm7(private val bus: Bus, private val interrupts: Interrupts) {
    val r = IntArray(16)

    private val bankedRegs = Array(6) { IntArray(2) }
    private val fiqRegs = IntArray(5)
    private val usrRegs = IntArray(5)
    private val spsr = IntArray(6)

    var pc = 0x08000000
    var thumb = false
    var halted = false
    var waitFlags = 0
    private var waitReturn = 0

    private var mode = MODE_SYS
    private var negative = false
    private var zero = false
    private var carry = false
    private var overflow = false
    private var irqDisable = false
    private var fiqDisable = false

    init {
        bankedRegs[bankIndex(MODE_SVC)][0] = 0x03007FE0
        bankedRegs[bankIndex(MODE_IRQ)][0] = 0x03007FA0
        r[13] = 0x03007F00
        r[14] = 0x08000000
    }

    fun save(writer: StateWriter) {
        writer.ints(r)
        for (bank in bankedRegs) writer.ints(bank)
        writer.ints(fiqRegs)
        writer.ints(usrRegs)
        writer.ints(spsr)

        writer.int(pc)
        writer.bool(thumb)
        writer.bool(halted)
        writer.int(waitFlags)
        writer.int(waitReturn)

        writer.int(mode)
        writer.bool(negative)
        writer.bool(zero)
        writer.bool(carry)
        writer.bool(overflow)
        writer.bool(irqDisable)
        writer.bool(fiqDisable)
    }

    fun load(reader: StateReader) {
        reader.ints(r)
        for (bank in bankedRegs) reader.ints(bank)
        reader.ints(fiqRegs)
        reader.ints(usrRegs)
        reader.ints(spsr)

        pc = reader.int()
        thumb = reader.bool()
        halted = reader.bool()
        waitFlags = reader.int()
        waitReturn = reader.int()

        mode = reader.int()
        negative = reader.bool()
        zero = reader.bool()
        carry = reader.bool()
        overflow = reader.bool()
        irqDisable = reader.bool()
        fiqDisable = reader.bool()
    }

    fun step(): Int {
        if (interrupts.pending) {
            halted = false
            if (!irqDisable) enterIrq()
        }

        if (halted) return 64

        if (pc == MAGIC_IRQ_RETURN) return exitIrq()

        return if (thumb) executeThumb() else executeArm()
    }

    private fun enterIrq() {
        val returnAddress = pc + 4
        spsr[bankIndex(MODE_IRQ)] = cpsr()
        switchMode(MODE_IRQ)
        thumb = false
        irqDisable = true

        r[13] -= 24
        val sp = r[13]
        bus.write32(sp, r[0])
        bus.write32(sp + 4, r[1])
        bus.write32(sp + 8, r[2])
        bus.write32(sp + 12, r[3])
        bus.write32(sp + 16, r[12])
        bus.write32(sp + 20, returnAddress)

        r[14] = MAGIC_IRQ_RETURN
        pc = bus.read32(0x03007FFC) and 3.inv()
    }

    private fun exitIrq(): Int {
        val sp = r[13]
        r[0] = bus.read32(sp)
        r[1] = bus.read32(sp + 4)
        r[2] = bus.read32(sp + 8)
        r[3] = bus.read32(sp + 12)
        r[12] = bus.read32(sp + 16)
        val returnAddress = bus.read32(sp + 20)
        r[13] = sp + 24

        setCpsr(spsr[bankIndex(MODE_IRQ)])
        pc = (returnAddress - 4) and (if (thumb) 1 else 3).inv()

        if (waitFlags != 0) {
            val biosFlags = bus.read16(0x03007FF8)
            if (biosFlags and waitFlags != 0) {
                bus.write16(0x03007FF8, biosFlags and waitFlags.inv())
                waitFlags = 0
            } else {
                pc = waitReturn
                halted = true
            }
        }

        return 8
    }

    fun requestIntrWait(clearFirst: Boolean, flags: Int) {
        if (clearFirst) {
            val biosFlags = bus.read16(0x03007FF8)
            bus.write16(0x03007FF8, biosFlags and flags.inv())
        }

        waitFlags = flags and 0x3FFF
        waitReturn = pc
        halted = true
    }

    fun cpsr(): Int {
        var value = mode
        if (thumb) value = value or 0x20
        if (fiqDisable) value = value or 0x40
        if (irqDisable) value = value or 0x80
        if (overflow) value = value or 0x10000000
        if (carry) value = value or 0x20000000
        if (zero) value = value or 0x40000000
        if (negative) value = value or -0x80000000
        return value
    }

    fun setCpsr(value: Int) {
        switchMode(value and 0x1F)
        thumb = value and 0x20 != 0
        fiqDisable = value and 0x40 != 0
        irqDisable = value and 0x80 != 0
        overflow = value and 0x10000000 != 0
        carry = value and 0x20000000 != 0
        zero = value and 0x40000000 != 0
        negative = value and -0x80000000 != 0
    }

    private fun switchMode(next: Int) {
        if (next == mode) return

        val oldBank = bankIndex(mode)
        val newBank = bankIndex(next)

        if (oldBank != newBank) {
            bankedRegs[oldBank][0] = r[13]
            bankedRegs[oldBank][1] = r[14]
            r[13] = bankedRegs[newBank][0]
            r[14] = bankedRegs[newBank][1]

            if (mode == MODE_FIQ) {
                for (i in 0 until 5) {
                    fiqRegs[i] = r[8 + i]
                    r[8 + i] = usrRegs[i]
                }
            } else if (next == MODE_FIQ) {
                for (i in 0 until 5) {
                    usrRegs[i] = r[8 + i]
                    r[8 + i] = fiqRegs[i]
                }
            }
        }

        mode = next
    }

    private fun bankIndex(m: Int): Int = when (m) {
        MODE_FIQ -> 1
        MODE_IRQ -> 2
        MODE_SVC -> 3
        MODE_ABT -> 4
        MODE_UND -> 5
        else -> 0
    }

    private fun spsrIndex(): Int = bankIndex(mode)

    private fun condition(code: Int): Boolean = when (code) {
        0x0 -> zero
        0x1 -> !zero
        0x2 -> carry
        0x3 -> !carry
        0x4 -> negative
        0x5 -> !negative
        0x6 -> overflow
        0x7 -> !overflow
        0x8 -> carry && !zero
        0x9 -> !carry || zero
        0xA -> negative == overflow
        0xB -> negative != overflow
        0xC -> !zero && negative == overflow
        0xD -> zero || negative != overflow
        else -> true
    }

    private fun reg(index: Int): Int = if (index == 15) pc + 4 else r[index]

    private fun regThumb(index: Int): Int = if (index == 15) pc + 2 else r[index]

    private fun setPcThumb(value: Int) {
        pc = value and 1.inv()
    }

    private fun executeArm(): Int {
        val opcode = bus.read32(pc)
        pc += 4

        if (!condition(opcode ushr 28)) return 1

        val high = (opcode ushr 20) and 0xFF
        val low = (opcode ushr 4) and 0xF

        return when {
            high == 0x12 && low == 0x1 -> {
                val target = reg(opcode and 0xF)
                thumb = target and 1 != 0
                pc = target and (if (thumb) 1 else 3).inv()
                3
            }

            high and 0xFC == 0x00 && low == 0x9 -> armMultiply(opcode)
            high and 0xF8 == 0x08 && low == 0x9 -> armMultiplyLong(opcode)
            high and 0xFB == 0x10 && low == 0x9 -> armSwap(opcode)
            high and 0xE0 == 0x00 && low and 0x9 == 0x9 && low != 0x9 -> armHalfword(opcode)
            high and 0xF9 == 0x10 && high and 0x01 == 0 -> armPsr(opcode)
            high and 0xFB == 0x32 -> armPsr(opcode)
            high and 0xC0 == 0x00 -> armDataProcessing(opcode)
            high and 0xC0 == 0x40 -> armSingleTransfer(opcode)
            high and 0xE0 == 0x80 -> armBlockTransfer(opcode)
            high and 0xE0 == 0xA0 -> {
                val offset = (opcode shl 8) shr 6
                if (opcode and 0x01000000 != 0) r[14] = pc
                pc += offset + 4
                3
            }
            high and 0xF0 == 0xF0 -> {
                BiosHle.handle(this, bus, (opcode ushr 16) and 0xFF)
                3
            }
            else -> 1
        }
    }

    private fun armMultiply(opcode: Int): Int {
        val rd = (opcode ushr 16) and 0xF
        val rn = (opcode ushr 12) and 0xF
        val rs = (opcode ushr 8) and 0xF
        val rm = opcode and 0xF

        var result = reg(rm) * reg(rs)
        if (opcode and 0x00200000 != 0) result += reg(rn)

        r[rd] = result

        if (opcode and 0x00100000 != 0) {
            negative = result < 0
            zero = result == 0
        }

        return 3
    }

    private fun armMultiplyLong(opcode: Int): Int {
        val rdHi = (opcode ushr 16) and 0xF
        val rdLo = (opcode ushr 12) and 0xF
        val rs = (opcode ushr 8) and 0xF
        val rm = opcode and 0xF

        val signed = opcode and 0x00400000 != 0
        var result = if (signed) {
            reg(rm).toLong() * reg(rs).toLong()
        } else {
            (reg(rm).toLong() and 0xFFFFFFFFL) * (reg(rs).toLong() and 0xFFFFFFFFL)
        }

        if (opcode and 0x00200000 != 0) {
            result += (reg(rdHi).toLong() shl 32) or (reg(rdLo).toLong() and 0xFFFFFFFFL)
        }

        r[rdLo] = result.toInt()
        r[rdHi] = (result ushr 32).toInt()

        if (opcode and 0x00100000 != 0) {
            negative = result < 0
            zero = result == 0L
        }

        return 4
    }

    private fun armSwap(opcode: Int): Int {
        val rn = reg((opcode ushr 16) and 0xF)
        val rd = (opcode ushr 12) and 0xF
        val rm = reg(opcode and 0xF)

        if (opcode and 0x00400000 != 0) {
            r[rd] = bus.read8(rn)
            bus.write8(rn, rm)
        } else {
            val value = bus.read32(rn and 3.inv())
            r[rd] = value.rotateRight((rn and 3) * 8)
            bus.write32(rn and 3.inv(), rm)
        }

        return 4
    }

    private fun armHalfword(opcode: Int): Int {
        val pre = opcode and 0x01000000 != 0
        val up = opcode and 0x00800000 != 0
        val immediate = opcode and 0x00400000 != 0
        val writeback = opcode and 0x00200000 != 0
        val load = opcode and 0x00100000 != 0

        val rn = (opcode ushr 16) and 0xF
        val rd = (opcode ushr 12) and 0xF
        val sh = (opcode ushr 5) and 0x3

        val offset = if (immediate) {
            ((opcode ushr 4) and 0xF0) or (opcode and 0xF)
        } else {
            reg(opcode and 0xF)
        }

        var address = reg(rn)
        val delta = if (up) offset else -offset
        if (pre) address += delta

        if (load) {
            r[rd] = when (sh) {
                1 -> {
                    val value = bus.read16(address and 1.inv())
                    value.rotateRight((address and 1) * 8)
                }
                2 -> bus.read8(address).toByte().toInt()
                else -> {
                    if (address and 1 != 0) bus.read8(address).toByte().toInt()
                    else bus.read16(address).toShort().toInt()
                }
            }
        } else {
            bus.write16(address and 1.inv(), reg(rd) and 0xFFFF)
        }

        if (!pre) address += delta
        if ((writeback || !pre) && !(load && rn == rd)) r[rn] = address

        return 3
    }

    private fun armPsr(opcode: Int): Int {
        val spsrSelect = opcode and 0x00400000 != 0

        if (opcode and 0x00200000 == 0) {
            val rd = (opcode ushr 12) and 0xF
            r[rd] = if (spsrSelect) spsr[spsrIndex()] else cpsr()
            return 1
        }

        val value = if (opcode and 0x02000000 != 0) {
            val imm = opcode and 0xFF
            val rotate = ((opcode ushr 8) and 0xF) * 2
            imm.rotateRight(rotate)
        } else {
            reg(opcode and 0xF)
        }

        var mask = 0
        if (opcode and 0x00080000 != 0) mask = mask or 0xF0000000.toInt()
        if (opcode and 0x00010000 != 0) mask = mask or 0xFF

        if (spsrSelect) {
            val index = spsrIndex()
            spsr[index] = (spsr[index] and mask.inv()) or (value and mask)
        } else {
            if (mode == MODE_USR) mask = mask and 0xF0000000.toInt()
            setCpsr((cpsr() and mask.inv()) or (value and mask))
        }

        return 1
    }

    private fun armDataProcessing(opcode: Int): Int {
        val setFlags = opcode and 0x00100000 != 0
        val operation = (opcode ushr 21) and 0xF
        val rn = (opcode ushr 16) and 0xF
        val rd = (opcode ushr 12) and 0xF

        var shifterCarry = carry
        var cycles = 1

        val operand2: Int
        if (opcode and 0x02000000 != 0) {
            val imm = opcode and 0xFF
            val rotate = ((opcode ushr 8) and 0xF) * 2
            operand2 = imm.rotateRight(rotate)
            if (rotate != 0) shifterCarry = operand2 < 0
        } else {
            val rm = opcode and 0xF
            val shiftType = (opcode ushr 5) and 0x3

            var value: Int
            var amount: Int

            if (opcode and 0x10 != 0) {
                cycles = 2
                amount = reg((opcode ushr 8) and 0xF) and 0xFF
                value = if (rm == 15) pc + 8 else r[rm]
            } else {
                amount = (opcode ushr 7) and 0x1F
                value = reg(rm)
            }

            if (opcode and 0x10 != 0) {
                when (shiftType) {
                    0 -> when {
                        amount == 0 -> {}
                        amount < 32 -> {
                            shifterCarry = (value ushr (32 - amount)) and 1 != 0
                            value = value shl amount
                        }
                        amount == 32 -> {
                            shifterCarry = value and 1 != 0
                            value = 0
                        }
                        else -> {
                            shifterCarry = false
                            value = 0
                        }
                    }
                    1 -> when {
                        amount == 0 -> {}
                        amount < 32 -> {
                            shifterCarry = (value ushr (amount - 1)) and 1 != 0
                            value = value ushr amount
                        }
                        amount == 32 -> {
                            shifterCarry = value < 0
                            value = 0
                        }
                        else -> {
                            shifterCarry = false
                            value = 0
                        }
                    }
                    2 -> when {
                        amount == 0 -> {}
                        amount < 32 -> {
                            shifterCarry = (value shr (amount - 1)) and 1 != 0
                            value = value shr amount
                        }
                        else -> {
                            shifterCarry = value < 0
                            value = value shr 31
                        }
                    }
                    else -> if (amount != 0) {
                        val rotation = amount and 31
                        if (rotation == 0) {
                            shifterCarry = value < 0
                        } else {
                            shifterCarry = (value ushr (rotation - 1)) and 1 != 0
                            value = value.rotateRight(rotation)
                        }
                    }
                }
            } else {
                when (shiftType) {
                    0 -> if (amount != 0) {
                        shifterCarry = (value ushr (32 - amount)) and 1 != 0
                        value = value shl amount
                    }
                    1 -> {
                        val n = if (amount == 0) 32 else amount
                        shifterCarry = if (n == 32) value < 0 else (value ushr (n - 1)) and 1 != 0
                        value = if (n == 32) 0 else value ushr n
                    }
                    2 -> {
                        val n = if (amount == 0) 32 else amount
                        if (n == 32) {
                            shifterCarry = value < 0
                            value = value shr 31
                        } else {
                            shifterCarry = (value shr (n - 1)) and 1 != 0
                            value = value shr n
                        }
                    }
                    else -> if (amount == 0) {
                        val oldCarry = if (carry) 1 else 0
                        shifterCarry = value and 1 != 0
                        value = (value ushr 1) or (oldCarry shl 31)
                    } else {
                        shifterCarry = (value ushr (amount - 1)) and 1 != 0
                        value = value.rotateRight(amount)
                    }
                }
            }

            operand2 = value
        }

        val operand1 = if (rn == 15 && opcode and 0x02000000 == 0 && opcode and 0x10 != 0) pc + 8 else reg(rn)

        var result = 0
        var writeResult = true
        var logical = true

        when (operation) {
            0x0 -> result = operand1 and operand2
            0x1 -> result = operand1 xor operand2
            0x2 -> { result = sub(operand1, operand2, setFlags); logical = false }
            0x3 -> { result = sub(operand2, operand1, setFlags); logical = false }
            0x4 -> { result = add(operand1, operand2, setFlags); logical = false }
            0x5 -> { result = adc(operand1, operand2, setFlags); logical = false }
            0x6 -> { result = adc(operand1, operand2.inv(), setFlags); logical = false }
            0x7 -> { result = adc(operand2, operand1.inv(), setFlags); logical = false }
            0x8 -> { result = operand1 and operand2; writeResult = false }
            0x9 -> { result = operand1 xor operand2; writeResult = false }
            0xA -> { result = sub(operand1, operand2, true); writeResult = false; logical = false }
            0xB -> { result = add(operand1, operand2, true); writeResult = false; logical = false }
            0xC -> result = operand1 or operand2
            0xD -> result = operand2
            0xE -> result = operand1 and operand2.inv()
            0xF -> result = operand2.inv()
        }

        if (setFlags && logical) {
            negative = result < 0
            zero = result == 0
            carry = shifterCarry
        } else if (setFlags && !writeResult) {
            negative = result < 0
            zero = result == 0
        }

        if (writeResult) {
            if (rd == 15) {
                if (setFlags) setCpsr(spsr[spsrIndex()])
                pc = result and (if (thumb) 1 else 3).inv()
                cycles += 2
            } else {
                r[rd] = result
            }
        } else if (setFlags && rd == 15) {
            setCpsr(spsr[spsrIndex()])
        }

        return cycles
    }

    private fun add(a: Int, b: Int, setFlags: Boolean): Int {
        val result = a + b
        if (setFlags) {
            negative = result < 0
            zero = result == 0
            carry = (a.toLong() and 0xFFFFFFFFL) + (b.toLong() and 0xFFFFFFFFL) > 0xFFFFFFFFL
            overflow = (a xor result) and (b xor result) < 0
        }
        return result
    }

    private fun sub(a: Int, b: Int, setFlags: Boolean): Int {
        val result = a - b
        if (setFlags) {
            negative = result < 0
            zero = result == 0
            carry = (a.toLong() and 0xFFFFFFFFL) >= (b.toLong() and 0xFFFFFFFFL)
            overflow = (a xor b) and (a xor result) < 0
        }
        return result
    }

    private fun adc(a: Int, b: Int, setFlags: Boolean): Int {
        val carryIn = if (carry) 1L else 0L
        val wide = (a.toLong() and 0xFFFFFFFFL) + (b.toLong() and 0xFFFFFFFFL) + carryIn
        val result = wide.toInt()
        if (setFlags) {
            negative = result < 0
            zero = result == 0
            carry = wide > 0xFFFFFFFFL
            overflow = (a xor result) and (b xor result) < 0
        }
        return result
    }

    private fun armSingleTransfer(opcode: Int): Int {
        val immediate = opcode and 0x02000000 == 0
        val pre = opcode and 0x01000000 != 0
        val up = opcode and 0x00800000 != 0
        val byte = opcode and 0x00400000 != 0
        val writeback = opcode and 0x00200000 != 0
        val load = opcode and 0x00100000 != 0

        val rn = (opcode ushr 16) and 0xF
        val rd = (opcode ushr 12) and 0xF

        val offset = if (immediate) {
            opcode and 0xFFF
        } else {
            val rm = reg(opcode and 0xF)
            val amount = (opcode ushr 7) and 0x1F
            when ((opcode ushr 5) and 0x3) {
                0 -> rm shl amount
                1 -> if (amount == 0) 0 else rm ushr amount
                2 -> if (amount == 0) rm shr 31 else rm shr amount
                else -> if (amount == 0) {
                    ((if (carry) 1 else 0) shl 31) or (rm ushr 1)
                } else {
                    rm.rotateRight(amount)
                }
            }
        }

        var address = reg(rn)
        val delta = if (up) offset else -offset
        if (pre) address += delta

        if (load) {
            val value = if (byte) {
                bus.read8(address)
            } else {
                bus.read32(address and 3.inv()).rotateRight((address and 3) * 8)
            }

            if (!pre) address += delta
            if ((writeback || !pre) && rn != rd) r[rn] = address

            if (rd == 15) pc = value and 3.inv() else r[rd] = value
        } else {
            val value = if (rd == 15) pc + 8 else r[rd]
            if (byte) bus.write8(address, value and 0xFF) else bus.write32(address and 3.inv(), value)

            if (!pre) address += delta
            if (writeback || !pre) r[rn] = address
        }

        return 3
    }

    private fun armBlockTransfer(opcode: Int): Int {
        val pre = opcode and 0x01000000 != 0
        val up = opcode and 0x00800000 != 0
        val psrUser = opcode and 0x00400000 != 0
        val writeback = opcode and 0x00200000 != 0
        val load = opcode and 0x00100000 != 0

        val rn = (opcode ushr 16) and 0xF
        var list = opcode and 0xFFFF

        val empty = list == 0
        if (empty) list = 0x8000

        val count = if (empty) 16 else list.countOneBits()
        val base = r[rn]

        var address = if (up) base else base - count * 4
        val finalBase = if (up) base + count * 4 else base - count * 4

        val userTransfer = psrUser && !(load && list and 0x8000 != 0)
        val savedMode = mode
        if (userTransfer) switchMode(MODE_USR)

        var first = true
        for (i in 0 until 16) {
            if (list and (1 shl i) == 0) continue

            if (pre == up) address += 4

            if (load) {
                val value = bus.read32(address and 3.inv())
                if (i == 15) {
                    pc = value and 3.inv()
                    if (psrUser) setCpsr(spsr[spsrIndex()])
                } else {
                    r[i] = value
                }
            } else {
                val value = when {
                    i == 15 -> pc + 8
                    i == rn && !first -> finalBase
                    i == rn -> base
                    else -> r[i]
                }
                bus.write32(address and 3.inv(), value)
            }

            if (pre != up) address += 4
            first = false
        }

        if (userTransfer) switchMode(savedMode)

        if (writeback && !(load && list and (1 shl rn) != 0)) {
            r[rn] = finalBase
        }

        return 2 + count
    }

    private fun executeThumb(): Int {
        val opcode = bus.read16(pc)
        pc += 2

        return when ((opcode ushr 12) and 0xF) {
            0x0, 0x1 -> thumbShiftAddSub(opcode)
            0x2, 0x3 -> thumbImmediate(opcode)
            0x4 -> when {
                opcode and 0x0C00 == 0x0000 -> thumbAlu(opcode)
                opcode and 0x0C00 == 0x0400 -> thumbHiReg(opcode)
                else -> {
                    val rd = (opcode ushr 8) and 0x7
                    val address = ((pc + 2) and 3.inv()) + (opcode and 0xFF) * 4
                    r[rd] = bus.read32(address)
                    3
                }
            }
            0x5 -> thumbRegisterTransfer(opcode)
            0x6, 0x7 -> thumbImmediateTransfer(opcode)
            0x8 -> {
                val offset = ((opcode ushr 6) and 0x1F) * 2
                val rb = (opcode ushr 3) and 0x7
                val rd = opcode and 0x7
                val address = r[rb] + offset
                if (opcode and 0x0800 != 0) {
                    val value = bus.read16(address and 1.inv())
                    r[rd] = value.rotateRight((address and 1) * 8)
                } else {
                    bus.write16(address and 1.inv(), r[rd] and 0xFFFF)
                }
                3
            }
            0x9 -> {
                val rd = (opcode ushr 8) and 0x7
                val address = r[13] + (opcode and 0xFF) * 4
                if (opcode and 0x0800 != 0) {
                    r[rd] = bus.read32(address and 3.inv()).rotateRight((address and 3) * 8)
                } else {
                    bus.write32(address and 3.inv(), r[rd])
                }
                3
            }
            0xA -> {
                val rd = (opcode ushr 8) and 0x7
                val offset = (opcode and 0xFF) * 4
                r[rd] = if (opcode and 0x0800 != 0) r[13] + offset else ((pc + 2) and 3.inv()) + offset
                1
            }
            0xB -> thumbMisc(opcode)
            0xC -> thumbBlockTransfer(opcode)
            0xD -> {
                val cond = (opcode ushr 8) and 0xF
                if (cond == 0xF) {
                    BiosHle.handle(this, bus, opcode and 0xFF)
                    3
                } else if (condition(cond)) {
                    pc += ((opcode and 0xFF).toByte().toInt() * 2) + 2
                    3
                } else {
                    1
                }
            }
            0xE -> {
                pc += ((opcode shl 21) shr 20) + 2
                3
            }
            else -> {
                if (opcode and 0x0800 == 0) {
                    r[14] = pc + 2 + ((opcode shl 21) shr 9)
                    1
                } else {
                    val next = r[14] + ((opcode and 0x7FF) shl 1)
                    r[14] = pc or 1
                    setPcThumb(next)
                    3
                }
            }
        }
    }

    private fun thumbShiftAddSub(opcode: Int): Int {
        if (opcode and 0x1800 == 0x1800) {
            val rd = opcode and 0x7
            val rs = (opcode ushr 3) and 0x7
            val value = (opcode ushr 6) and 0x7
            val operand = if (opcode and 0x0400 != 0) value else r[value]

            r[rd] = if (opcode and 0x0200 != 0) {
                sub(r[rs], operand, true)
            } else {
                add(r[rs], operand, true)
            }
            return 1
        }

        val rd = opcode and 0x7
        val rs = (opcode ushr 3) and 0x7
        val amount = (opcode ushr 6) and 0x1F
        var value = r[rs]

        when ((opcode ushr 11) and 0x3) {
            0 -> if (amount != 0) {
                carry = (value ushr (32 - amount)) and 1 != 0
                value = value shl amount
            }
            1 -> {
                val n = if (amount == 0) 32 else amount
                carry = if (n == 32) value < 0 else (value ushr (n - 1)) and 1 != 0
                value = if (n == 32) 0 else value ushr n
            }
            else -> {
                val n = if (amount == 0) 32 else amount
                if (n == 32) {
                    carry = value < 0
                    value = value shr 31
                } else {
                    carry = (value shr (n - 1)) and 1 != 0
                    value = value shr n
                }
            }
        }

        r[rd] = value
        negative = value < 0
        zero = value == 0
        return 1
    }

    private fun thumbImmediate(opcode: Int): Int {
        val rd = (opcode ushr 8) and 0x7
        val imm = opcode and 0xFF

        when ((opcode ushr 11) and 0x3) {
            0 -> {
                r[rd] = imm
                negative = false
                zero = imm == 0
            }
            1 -> sub(r[rd], imm, true)
            2 -> r[rd] = add(r[rd], imm, true)
            else -> r[rd] = sub(r[rd], imm, true)
        }

        return 1
    }

    private fun thumbAlu(opcode: Int): Int {
        val rd = opcode and 0x7
        val rs = (opcode ushr 3) and 0x7
        val a = r[rd]
        val b = r[rs]

        when ((opcode ushr 6) and 0xF) {
            0x0 -> r[rd] = logic(a and b)
            0x1 -> r[rd] = logic(a xor b)
            0x2 -> {
                val amount = b and 0xFF
                var value = a
                if (amount in 1..31) {
                    carry = (value ushr (32 - amount)) and 1 != 0
                    value = value shl amount
                } else if (amount == 32) {
                    carry = value and 1 != 0
                    value = 0
                } else if (amount > 32) {
                    carry = false
                    value = 0
                }
                r[rd] = logic(value)
            }
            0x3 -> {
                val amount = b and 0xFF
                var value = a
                if (amount in 1..31) {
                    carry = (value ushr (amount - 1)) and 1 != 0
                    value = value ushr amount
                } else if (amount == 32) {
                    carry = value < 0
                    value = 0
                } else if (amount > 32) {
                    carry = false
                    value = 0
                }
                r[rd] = logic(value)
            }
            0x4 -> {
                val amount = b and 0xFF
                var value = a
                if (amount in 1..31) {
                    carry = (value shr (amount - 1)) and 1 != 0
                    value = value shr amount
                } else if (amount >= 32) {
                    carry = value < 0
                    value = value shr 31
                }
                r[rd] = logic(value)
            }
            0x5 -> r[rd] = adc(a, b, true)
            0x6 -> r[rd] = adc(a, b.inv(), true)
            0x7 -> {
                val amount = b and 0xFF
                var value = a
                if (amount != 0) {
                    val rotation = amount and 31
                    if (rotation == 0) {
                        carry = value < 0
                    } else {
                        carry = (value ushr (rotation - 1)) and 1 != 0
                        value = value.rotateRight(rotation)
                    }
                }
                r[rd] = logic(value)
            }
            0x8 -> logic(a and b)
            0x9 -> r[rd] = sub(0, b, true)
            0xA -> sub(a, b, true)
            0xB -> add(a, b, true)
            0xC -> r[rd] = logic(a or b)
            0xD -> {
                val result = a * b
                r[rd] = result
                negative = result < 0
                zero = result == 0
            }
            0xE -> r[rd] = logic(a and b.inv())
            else -> r[rd] = logic(b.inv())
        }

        return 1
    }

    private fun logic(value: Int): Int {
        negative = value < 0
        zero = value == 0
        return value
    }

    private fun thumbHiReg(opcode: Int): Int {
        val rd = (opcode and 0x7) or ((opcode ushr 4) and 0x8)
        val rs = ((opcode ushr 3) and 0x7) or ((opcode ushr 3) and 0x8)

        when ((opcode ushr 8) and 0x3) {
            0 -> {
                val result = regThumb(rd) + regThumb(rs)
                if (rd == 15) setPcThumb(result) else r[rd] = result
            }
            1 -> sub(regThumb(rd), regThumb(rs), true)
            2 -> {
                if (rd == 15) setPcThumb(regThumb(rs)) else r[rd] = regThumb(rs)
            }
            else -> {
                val target = regThumb(rs)
                thumb = target and 1 != 0
                pc = target and (if (thumb) 1 else 3).inv()
            }
        }

        return 2
    }

    private fun thumbRegisterTransfer(opcode: Int): Int {
        val ro = (opcode ushr 6) and 0x7
        val rb = (opcode ushr 3) and 0x7
        val rd = opcode and 0x7
        val address = r[rb] + r[ro]

        if (opcode and 0x0200 == 0) {
            when ((opcode ushr 10) and 0x3) {
                0 -> bus.write32(address and 3.inv(), r[rd])
                1 -> bus.write8(address, r[rd] and 0xFF)
                2 -> r[rd] = bus.read32(address and 3.inv()).rotateRight((address and 3) * 8)
                else -> r[rd] = bus.read8(address)
            }
        } else {
            when ((opcode ushr 10) and 0x3) {
                0 -> bus.write16(address and 1.inv(), r[rd] and 0xFFFF)
                1 -> r[rd] = bus.read8(address).toByte().toInt()
                2 -> {
                    val value = bus.read16(address and 1.inv())
                    r[rd] = value.rotateRight((address and 1) * 8)
                }
                else -> {
                    r[rd] = if (address and 1 != 0) {
                        bus.read8(address).toByte().toInt()
                    } else {
                        bus.read16(address).toShort().toInt()
                    }
                }
            }
        }

        return 3
    }

    private fun thumbImmediateTransfer(opcode: Int): Int {
        val rb = (opcode ushr 3) and 0x7
        val rd = opcode and 0x7
        val offset = (opcode ushr 6) and 0x1F

        if (opcode and 0x1000 == 0) {
            val address = r[rb] + offset * 4
            if (opcode and 0x0800 != 0) {
                r[rd] = bus.read32(address and 3.inv()).rotateRight((address and 3) * 8)
            } else {
                bus.write32(address and 3.inv(), r[rd])
            }
        } else {
            val address = r[rb] + offset
            if (opcode and 0x0800 != 0) {
                r[rd] = bus.read8(address)
            } else {
                bus.write8(address, r[rd] and 0xFF)
            }
        }

        return 3
    }

    private fun thumbMisc(opcode: Int): Int {
        if (opcode and 0x0F00 == 0x0000) {
            val offset = (opcode and 0x7F) * 4
            r[13] += if (opcode and 0x80 != 0) -offset else offset
            return 1
        }

        if (opcode and 0x0600 == 0x0400) {
            val load = opcode and 0x0800 != 0
            val extra = opcode and 0x0100 != 0
            var list = opcode and 0xFF

            if (load) {
                var address = r[13]
                for (i in 0 until 8) {
                    if (list and (1 shl i) == 0) continue
                    r[i] = bus.read32(address and 3.inv())
                    address += 4
                }
                if (extra) {
                    setPcThumb(bus.read32(address and 3.inv()))
                    address += 4
                }
                r[13] = address
            } else {
                var count = list.countOneBits()
                if (extra) count++
                var address = r[13] - count * 4
                r[13] = address
                for (i in 0 until 8) {
                    if (list and (1 shl i) == 0) continue
                    bus.write32(address and 3.inv(), r[i])
                    address += 4
                }
                if (extra) bus.write32(address and 3.inv(), r[14])
            }

            return 3
        }

        return 1
    }

    private fun thumbBlockTransfer(opcode: Int): Int {
        val load = opcode and 0x0800 != 0
        val rb = (opcode ushr 8) and 0x7
        val list = opcode and 0xFF

        var address = r[rb]

        if (list == 0) {
            if (load) setPcThumb(bus.read32(address and 3.inv())) else bus.write32(address and 3.inv(), pc + 4)
            r[rb] = address + 0x40
            return 3
        }

        for (i in 0 until 8) {
            if (list and (1 shl i) == 0) continue
            if (load) {
                r[i] = bus.read32(address and 3.inv())
            } else {
                bus.write32(address and 3.inv(), r[i])
            }
            address += 4
        }

        if (!(load && list and (1 shl rb) != 0)) r[rb] = address

        return 3
    }

    companion object {
        const val MODE_USR = 0x10
        const val MODE_FIQ = 0x11
        const val MODE_IRQ = 0x12
        const val MODE_SVC = 0x13
        const val MODE_ABT = 0x17
        const val MODE_UND = 0x1B
        const val MODE_SYS = 0x1F

        const val MAGIC_IRQ_RETURN = 0x00000138
    }
}
