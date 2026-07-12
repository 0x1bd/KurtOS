package snes.core

import kapi.state.StateReader
import kapi.state.StateWriter

class Spc700(private val memory: SpcMemory) {
    var a = 0
    var x = 0
    var y = 0
    var sp = 0xEF
    var pc = 0xFFC0
    var psw = 0x02

    var stopped = false

    private var cycles = 0

    private val flagC get() = psw and 0x01 != 0
    private val flagH get() = psw and 0x08 != 0
    private val flagP get() = psw and 0x20 != 0

    fun reset() {
        a = 0
        x = 0
        y = 0
        sp = 0xEF
        psw = 0x02
        stopped = false
        pc = memory.read(0xFFFE) or (memory.read(0xFFFF) shl 8)
    }

    fun step(): Int {
        if (stopped) return 2

        cycles = 0

        val opcode = fetch()
        cycles += CYCLES[opcode]

        execute(opcode)

        return cycles
    }

    private fun execute(opcode: Int) {
        when (opcode) {
            0x00 -> Unit
            0xEF, 0xFF -> stopped = true

            0x01, 0x11, 0x21, 0x31, 0x41, 0x51, 0x61, 0x71,
            0x81, 0x91, 0xA1, 0xB1, 0xC1, 0xD1, 0xE1, 0xF1,
            -> {
                val index = opcode ushr 4
                push16(pc)
                pc = readWord(0xFFDE - (index shl 1))
            }

            0x4F -> {
                val target = fetch()
                push16(pc)
                pc = 0xFF00 or target
            }

            0x0F -> {
                push16(pc)
                push(psw)
                psw = psw or 0x10
                psw = psw and 0x04.inv()
                pc = readWord(0xFFDE)
            }

            0x6F -> pc = pull16()

            0x7F -> {
                psw = pull()
                pc = pull16()
            }

            0x02, 0x22, 0x42, 0x62, 0x82, 0xA2, 0xC2, 0xE2 -> {
                val bit = opcode ushr 5
                val address = direct(fetch())
                write(address, read(address) or (1 shl bit))
            }

            0x12, 0x32, 0x52, 0x72, 0x92, 0xB2, 0xD2, 0xF2 -> {
                val bit = opcode ushr 5
                val address = direct(fetch())
                write(address, read(address) and (1 shl bit).inv())
            }

            0x03, 0x23, 0x43, 0x63, 0x83, 0xA3, 0xC3, 0xE3 -> {
                val bit = opcode ushr 5
                val value = read(direct(fetch()))
                branch(value and (1 shl bit) != 0)
            }

            0x13, 0x33, 0x53, 0x73, 0x93, 0xB3, 0xD3, 0xF3 -> {
                val bit = opcode ushr 5
                val value = read(direct(fetch()))
                branch(value and (1 shl bit) == 0)
            }

            0x04 -> a = or(a, read(direct(fetch())))
            0x05 -> a = or(a, read(absolute()))
            0x06 -> a = or(a, read(indirectX()))
            0x07 -> a = or(a, read(indexedIndirect()))
            0x08 -> a = or(a, fetch())
            0x09 -> memoryToMemory(OR)
            0x14 -> a = or(a, read(directIndexed(x)))
            0x15 -> a = or(a, read(absoluteIndexed(x)))
            0x16 -> a = or(a, read(absoluteIndexed(y)))
            0x17 -> a = or(a, read(indirectIndexed()))
            0x18 -> immediateToMemory(OR)
            0x19 -> indirectToIndirect(OR)

            0x24 -> a = and(a, read(direct(fetch())))
            0x25 -> a = and(a, read(absolute()))
            0x26 -> a = and(a, read(indirectX()))
            0x27 -> a = and(a, read(indexedIndirect()))
            0x28 -> a = and(a, fetch())
            0x29 -> memoryToMemory(AND)
            0x34 -> a = and(a, read(directIndexed(x)))
            0x35 -> a = and(a, read(absoluteIndexed(x)))
            0x36 -> a = and(a, read(absoluteIndexed(y)))
            0x37 -> a = and(a, read(indirectIndexed()))
            0x38 -> immediateToMemory(AND)
            0x39 -> indirectToIndirect(AND)

            0x44 -> a = eor(a, read(direct(fetch())))
            0x45 -> a = eor(a, read(absolute()))
            0x46 -> a = eor(a, read(indirectX()))
            0x47 -> a = eor(a, read(indexedIndirect()))
            0x48 -> a = eor(a, fetch())
            0x49 -> memoryToMemory(EOR)
            0x54 -> a = eor(a, read(directIndexed(x)))
            0x55 -> a = eor(a, read(absoluteIndexed(x)))
            0x56 -> a = eor(a, read(absoluteIndexed(y)))
            0x57 -> a = eor(a, read(indirectIndexed()))
            0x58 -> immediateToMemory(EOR)
            0x59 -> indirectToIndirect(EOR)

            0x64 -> compare(a, read(direct(fetch())))
            0x65 -> compare(a, read(absolute()))
            0x66 -> compare(a, read(indirectX()))
            0x67 -> compare(a, read(indexedIndirect()))
            0x68 -> compare(a, fetch())
            0x69 -> memoryToMemory(CMP)
            0x74 -> compare(a, read(directIndexed(x)))
            0x75 -> compare(a, read(absoluteIndexed(x)))
            0x76 -> compare(a, read(absoluteIndexed(y)))
            0x77 -> compare(a, read(indirectIndexed()))
            0x78 -> immediateToMemory(CMP)
            0x79 -> indirectToIndirect(CMP)

            0x84 -> a = adc(a, read(direct(fetch())))
            0x85 -> a = adc(a, read(absolute()))
            0x86 -> a = adc(a, read(indirectX()))
            0x87 -> a = adc(a, read(indexedIndirect()))
            0x88 -> a = adc(a, fetch())
            0x89 -> memoryToMemory(ADC)
            0x94 -> a = adc(a, read(directIndexed(x)))
            0x95 -> a = adc(a, read(absoluteIndexed(x)))
            0x96 -> a = adc(a, read(absoluteIndexed(y)))
            0x97 -> a = adc(a, read(indirectIndexed()))
            0x98 -> immediateToMemory(ADC)
            0x99 -> indirectToIndirect(ADC)

            0xA4 -> a = sbc(a, read(direct(fetch())))
            0xA5 -> a = sbc(a, read(absolute()))
            0xA6 -> a = sbc(a, read(indirectX()))
            0xA7 -> a = sbc(a, read(indexedIndirect()))
            0xA8 -> a = sbc(a, fetch())
            0xA9 -> memoryToMemory(SBC)
            0xB4 -> a = sbc(a, read(directIndexed(x)))
            0xB5 -> a = sbc(a, read(absoluteIndexed(x)))
            0xB6 -> a = sbc(a, read(absoluteIndexed(y)))
            0xB7 -> a = sbc(a, read(indirectIndexed()))
            0xB8 -> immediateToMemory(SBC)
            0xB9 -> indirectToIndirect(SBC)

            0x1E -> compare(x, read(absolute()))
            0x3E -> compare(x, read(direct(fetch())))
            0xC8 -> compare(x, fetch())
            0x5E -> compare(y, read(absolute()))
            0x7E -> compare(y, read(direct(fetch())))
            0xAD -> compare(y, fetch())

            0x0B -> modify(direct(fetch()), ASL)
            0x0C -> modify(absolute(), ASL)
            0x1B -> modify(directIndexed(x), ASL)
            0x1C -> a = shift(a, ASL)

            0x4B -> modify(direct(fetch()), LSR)
            0x4C -> modify(absolute(), LSR)
            0x5B -> modify(directIndexed(x), LSR)
            0x5C -> a = shift(a, LSR)

            0x2B -> modify(direct(fetch()), ROL)
            0x2C -> modify(absolute(), ROL)
            0x3B -> modify(directIndexed(x), ROL)
            0x3C -> a = shift(a, ROL)

            0x6B -> modify(direct(fetch()), ROR)
            0x6C -> modify(absolute(), ROR)
            0x7B -> modify(directIndexed(x), ROR)
            0x7C -> a = shift(a, ROR)

            0x8B -> modify(direct(fetch()), DEC)
            0x8C -> modify(absolute(), DEC)
            0x9B -> modify(directIndexed(x), DEC)
            0x9C -> a = shift(a, DEC)
            0x1D -> x = shift(x, DEC)
            0xDC -> y = shift(y, DEC)

            0xAB -> modify(direct(fetch()), INC)
            0xAC -> modify(absolute(), INC)
            0xBB -> modify(directIndexed(x), INC)
            0xBC -> a = shift(a, INC)
            0x3D -> x = shift(x, INC)
            0xFC -> y = shift(y, INC)

            0x9F -> {
                a = ((a shl 4) or (a shr 4)) and 0xFF
                setNZ(a)
            }

            0xDF -> {
                if (flagC || a > 0x99) {
                    a += 0x60
                    psw = psw or 0x01
                }
                if (flagH || (a and 0x0F) > 0x09) a += 0x06
                a = a and 0xFF
                setNZ(a)
            }

            0xBE -> {
                if (!flagC || a > 0x99) {
                    a -= 0x60
                    psw = psw and 0x01.inv()
                }
                if (!flagH || (a and 0x0F) > 0x09) a -= 0x06
                a = a and 0xFF
                setNZ(a)
            }

            0x0D -> push(psw)
            0x2D -> push(a)
            0x4D -> push(x)
            0x6D -> push(y)
            0x8E -> psw = pull()
            0xAE -> a = pull()
            0xCE -> x = pull()
            0xEE -> y = pull()

            0x0A -> bitOr(false)
            0x2A -> bitOr(true)
            0x4A -> bitAnd(false)
            0x6A -> bitAnd(true)
            0x8A -> {
                val operand = fetch16()
                val bit = (read(operand and 0x1FFF) ushr (operand ushr 13)) and 1
                setCarry((if (flagC) 1 else 0) xor bit != 0)
            }

            0xAA -> {
                val operand = fetch16()
                val bit = (read(operand and 0x1FFF) ushr (operand ushr 13)) and 1
                setCarry(bit != 0)
            }

            0xCA -> {
                val operand = fetch16()
                val address = operand and 0x1FFF
                val bit = operand ushr 13
                val value = read(address)
                write(address, if (flagC) value or (1 shl bit) else value and (1 shl bit).inv())
            }

            0xEA -> {
                val operand = fetch16()
                val address = operand and 0x1FFF
                val bit = operand ushr 13
                write(address, read(address) xor (1 shl bit))
            }

            0x0E -> testBits(true)
            0x4E -> testBits(false)

            0x10 -> branch(psw and 0x80 == 0)
            0x30 -> branch(psw and 0x80 != 0)
            0x50 -> branch(psw and 0x40 == 0)
            0x70 -> branch(psw and 0x40 != 0)
            0x90 -> branch(psw and 0x01 == 0)
            0xB0 -> branch(psw and 0x01 != 0)
            0xD0 -> branch(psw and 0x02 == 0)
            0xF0 -> branch(psw and 0x02 != 0)

            0x2F -> {
                val offset = fetch()
                pc = (pc + signed(offset)) and 0xFFFF
            }

            0x2E -> {
                val value = read(direct(fetch()))
                branch(a != value)
            }

            0xDE -> {
                val value = read(directIndexed(x))
                branch(a != value)
            }

            0x6E -> {
                val address = direct(fetch())
                val value = (read(address) - 1) and 0xFF
                write(address, value)
                branch(value != 0)
            }

            0xFE -> {
                y = (y - 1) and 0xFF
                branch(y != 0)
            }

            0x5F -> pc = fetch16()

            0x1F -> {
                val base = fetch16()
                pc = readWord((base + x) and 0xFFFF)
            }

            0x3F -> {
                val target = fetch16()
                push16(pc)
                pc = target
            }

            0x20 -> psw = psw and 0x20.inv()
            0x40 -> psw = psw or 0x20
            0x60 -> psw = psw and 0x01.inv()
            0x80 -> psw = psw or 0x01
            0xA0 -> psw = psw or 0x04
            0xC0 -> psw = psw and 0x04.inv()
            0xE0 -> psw = psw and 0x48.inv()
            0xED -> psw = psw xor 0x01

            0x1A -> incrementWord(-1)
            0x3A -> incrementWord(1)

            0x5A -> {
                val value = readWordDp(direct(fetch()))
                val ya = (y shl 8) or a
                val result = ya - value
                setCarry(result >= 0)
                psw = psw and 0x82.inv()
                if (result and 0xFFFF == 0) psw = psw or 0x02
                if (result and 0x8000 != 0) psw = psw or 0x80
            }

            0x7A -> {
                val value = readWordDp(direct(fetch()))
                val ya = (y shl 8) or a

                setCarry(false)
                val low = adc(ya and 0xFF, value and 0xFF)
                val high = adc((ya ushr 8) and 0xFF, (value ushr 8) and 0xFF)

                val result = ((high shl 8) or low) and 0xFFFF

                psw = psw and 0x02.inv()
                if (result == 0) psw = psw or 0x02

                a = result and 0xFF
                y = (result ushr 8) and 0xFF
            }

            0x9A -> {
                val value = readWordDp(direct(fetch()))
                val ya = (y shl 8) or a

                setCarry(true)
                val low = sbc(ya and 0xFF, value and 0xFF)
                val high = sbc((ya ushr 8) and 0xFF, (value ushr 8) and 0xFF)

                val result = ((high shl 8) or low) and 0xFFFF

                psw = psw and 0x02.inv()
                if (result == 0) psw = psw or 0x02

                a = result and 0xFF
                y = (result ushr 8) and 0xFF
            }

            0xBA -> {
                val value = readWordDp(direct(fetch()))
                a = value and 0xFF
                y = (value ushr 8) and 0xFF

                psw = psw and 0x82.inv()
                if (value == 0) psw = psw or 0x02
                if (value and 0x8000 != 0) psw = psw or 0x80
            }

            0xDA -> {
                val address = direct(fetch())
                read(address)
                write(address, a)
                write((address and 0xFF00) or ((address + 1) and 0xFF), y)
            }

            0xCF -> {
                val result = y * a
                a = result and 0xFF
                y = (result ushr 8) and 0xFF
                setNZ(y)
            }

            0x9E -> divide()

            0x8F -> {
                val value = fetch()
                val address = direct(fetch())
                write(address, value)
            }

            0xFA -> {
                val source = direct(fetch())
                val value = read(source)
                val target = direct(fetch())
                write(target, value)
            }

            0xC4 -> write(direct(fetch()), a)
            0xC5 -> write(absolute(), a)
            0xC6 -> write(indirectX(), a)
            0xC7 -> write(indexedIndirect(), a)
            0xC9 -> write(absolute(), x)
            0xCB -> write(direct(fetch()), y)
            0xCC -> write(absolute(), y)
            0xD4 -> write(directIndexed(x), a)
            0xD5 -> write(absoluteIndexed(x), a)
            0xD6 -> write(absoluteIndexed(y), a)
            0xD7 -> write(indirectIndexed(), a)
            0xD8 -> write(direct(fetch()), x)
            0xD9 -> write(directIndexed(y), x)
            0xDB -> write(directIndexed(x), y)

            0xAF -> {
                write(directPage(x), a)
                x = (x + 1) and 0xFF
            }

            0xBF -> {
                a = read(directPage(x))
                x = (x + 1) and 0xFF
                setNZ(a)
            }

            0xE4 -> {
                a = read(direct(fetch()))
                setNZ(a)
            }

            0xE5 -> {
                a = read(absolute())
                setNZ(a)
            }

            0xE6 -> {
                a = read(indirectX())
                setNZ(a)
            }

            0xE7 -> {
                a = read(indexedIndirect())
                setNZ(a)
            }

            0xE8 -> {
                a = fetch()
                setNZ(a)
            }

            0xF4 -> {
                a = read(directIndexed(x))
                setNZ(a)
            }

            0xF5 -> {
                a = read(absoluteIndexed(x))
                setNZ(a)
            }

            0xF6 -> {
                a = read(absoluteIndexed(y))
                setNZ(a)
            }

            0xF7 -> {
                a = read(indirectIndexed())
                setNZ(a)
            }

            0xE9 -> {
                x = read(absolute())
                setNZ(x)
            }

            0xCD -> {
                x = fetch()
                setNZ(x)
            }

            0xF8 -> {
                x = read(direct(fetch()))
                setNZ(x)
            }

            0xF9 -> {
                x = read(directIndexed(y))
                setNZ(x)
            }

            0xEB -> {
                y = read(direct(fetch()))
                setNZ(y)
            }

            0xEC -> {
                y = read(absolute())
                setNZ(y)
            }

            0x8D -> {
                y = fetch()
                setNZ(y)
            }

            0xFB -> {
                y = read(directIndexed(x))
                setNZ(y)
            }

            0x5D -> {
                x = a
                setNZ(x)
            }

            0x7D -> {
                a = x
                setNZ(a)
            }

            0x9D -> {
                x = sp
                setNZ(x)
            }

            0xBD -> sp = x

            0xDD -> {
                a = y
                setNZ(a)
            }

            0xFD -> {
                y = a
                setNZ(y)
            }

            else -> Unit
        }
    }

    private fun divide() {
        val ya = (y shl 8) or a

        setOverflow(y >= x)
        setHalf((y and 0x0F) >= (x and 0x0F))

        if (y < (x shl 1)) {
            a = ya / x
            y = ya % x
        } else {
            a = 255 - (ya - (x shl 9)) / (256 - x)
            y = x + (ya - (x shl 9)) % (256 - x)
        }

        a = a and 0xFF
        y = y and 0xFF

        setNZ(a)
    }

    private fun incrementWord(direction: Int) {
        val address = direct(fetch())

        var value = read(address)
        value = (value + direction) and 0xFF
        write(address, value)

        val highAddress = (address and 0xFF00) or ((address + 1) and 0xFF)
        var high = read(highAddress)

        if (direction > 0 && value == 0) high = (high + 1) and 0xFF
        if (direction < 0 && value == 0xFF) high = (high - 1) and 0xFF

        write(highAddress, high)

        val result = (high shl 8) or value

        psw = psw and 0x82.inv()
        if (result == 0) psw = psw or 0x02
        if (result and 0x8000 != 0) psw = psw or 0x80
    }

    private fun testBits(set: Boolean) {
        val address = absolute()
        val value = read(address)

        val result = (a - value) and 0xFF
        psw = psw and 0x82.inv()
        if (result == 0) psw = psw or 0x02
        if (result and 0x80 != 0) psw = psw or 0x80

        write(address, if (set) value or a else value and a.inv())
    }

    private fun bitOr(invert: Boolean) {
        val operand = fetch16()
        var bit = (read(operand and 0x1FFF) ushr (operand ushr 13)) and 1
        if (invert) bit = bit xor 1
        setCarry(flagC || bit != 0)
    }

    private fun bitAnd(invert: Boolean) {
        val operand = fetch16()
        var bit = (read(operand and 0x1FFF) ushr (operand ushr 13)) and 1
        if (invert) bit = bit xor 1
        setCarry(flagC && bit != 0)
    }

    private fun memoryToMemory(op: Int) {
        val source = direct(fetch())
        val value = read(source)
        val target = direct(fetch())
        val current = read(target)

        val result = apply(op, current, value)

        if (op != CMP) write(target, result)
    }

    private fun immediateToMemory(op: Int) {
        val value = fetch()
        val target = direct(fetch())
        val current = read(target)

        val result = apply(op, current, value)

        if (op != CMP) write(target, result)
    }

    private fun indirectToIndirect(op: Int) {
        val value = read(directPage(y))
        val target = directPage(x)
        val current = read(target)

        val result = apply(op, current, value)

        if (op != CMP) write(target, result)
    }

    private fun apply(op: Int, left: Int, right: Int): Int = when (op) {
        OR -> or(left, right)
        AND -> and(left, right)
        EOR -> eor(left, right)
        ADC -> adc(left, right)
        SBC -> sbc(left, right)
        else -> {
            compare(left, right)
            left
        }
    }

    private fun modify(address: Int, op: Int) {
        write(address, shift(read(address), op))
    }

    private fun shift(value: Int, op: Int): Int {
        val result = when (op) {
            ASL -> {
                setCarry(value and 0x80 != 0)
                (value shl 1) and 0xFF
            }

            LSR -> {
                setCarry(value and 0x01 != 0)
                value ushr 1
            }

            ROL -> {
                val carry = if (flagC) 1 else 0
                setCarry(value and 0x80 != 0)
                ((value shl 1) or carry) and 0xFF
            }

            ROR -> {
                val carry = if (flagC) 0x80 else 0
                setCarry(value and 0x01 != 0)
                (value ushr 1) or carry
            }

            INC -> (value + 1) and 0xFF
            else -> (value - 1) and 0xFF
        }

        setNZ(result)

        return result
    }

    private fun or(left: Int, right: Int): Int {
        val result = (left or right) and 0xFF
        setNZ(result)
        return result
    }

    private fun and(left: Int, right: Int): Int {
        val result = (left and right) and 0xFF
        setNZ(result)
        return result
    }

    private fun eor(left: Int, right: Int): Int {
        val result = (left xor right) and 0xFF
        setNZ(result)
        return result
    }

    private fun adc(left: Int, right: Int): Int {
        val carry = if (flagC) 1 else 0
        val result = left + right + carry

        setOverflow((left xor right).inv() and (left xor result) and 0x80 != 0)
        setHalf((left xor right xor result) and 0x10 != 0)
        setCarry(result > 0xFF)
        setNZ(result and 0xFF)

        return result and 0xFF
    }

    private fun sbc(left: Int, right: Int): Int {
        val borrow = if (flagC) 0 else 1
        val result = left - right - borrow

        setOverflow((left xor right) and (left xor result) and 0x80 != 0)
        setHalf((left xor right xor result) and 0x10 == 0)
        setCarry(result >= 0)
        setNZ(result and 0xFF)

        return result and 0xFF
    }

    private fun compare(left: Int, right: Int) {
        val result = left - right
        setCarry(result >= 0)
        setNZ(result and 0xFF)
    }

    private fun branch(taken: Boolean) {
        val offset = fetch()
        if (!taken) return

        cycles += 2
        pc = (pc + signed(offset)) and 0xFFFF
    }

    private fun direct(offset: Int): Int = (if (flagP) 0x100 else 0) or offset

    private fun directPage(offset: Int): Int = (if (flagP) 0x100 else 0) or (offset and 0xFF)

    private fun directIndexed(index: Int): Int = direct((fetch() + index) and 0xFF)

    private fun absolute(): Int = fetch16()

    private fun absoluteIndexed(index: Int): Int = (fetch16() + index) and 0xFFFF

    private fun indirectX(): Int = directPage(x)

    private fun indexedIndirect(): Int {
        val pointer = direct((fetch() + x) and 0xFF)
        return readWordDp(pointer)
    }

    private fun indirectIndexed(): Int {
        val pointer = direct(fetch())
        return (readWordDp(pointer) + y) and 0xFFFF
    }

    private fun fetch(): Int {
        val value = memory.read(pc)
        pc = (pc + 1) and 0xFFFF
        return value
    }

    private fun fetch16(): Int = fetch() or (fetch() shl 8)

    private fun read(address: Int): Int = memory.read(address and 0xFFFF)

    private fun write(address: Int, value: Int) = memory.write(address and 0xFFFF, value and 0xFF)

    private fun readWord(address: Int): Int {
        val low = read(address)
        val high = read((address + 1) and 0xFFFF)
        return low or (high shl 8)
    }

    private fun readWordDp(address: Int): Int {
        val low = read(address)
        val high = read((address and 0xFF00) or ((address + 1) and 0xFF))
        return low or (high shl 8)
    }

    private fun push(value: Int) {
        memory.write(0x0100 or sp, value and 0xFF)
        sp = (sp - 1) and 0xFF
    }

    private fun pull(): Int {
        sp = (sp + 1) and 0xFF
        return memory.read(0x0100 or sp)
    }

    private fun push16(value: Int) {
        push((value ushr 8) and 0xFF)
        push(value and 0xFF)
    }

    private fun pull16(): Int = pull() or (pull() shl 8)

    private fun setCarry(value: Boolean) {
        psw = if (value) psw or 0x01 else psw and 0x01.inv()
    }

    private fun setHalf(value: Boolean) {
        psw = if (value) psw or 0x08 else psw and 0x08.inv()
    }

    private fun setOverflow(value: Boolean) {
        psw = if (value) psw or 0x40 else psw and 0x40.inv()
    }

    private fun setNZ(value: Int) {
        psw = (psw and 0x82.inv()) or (if (value and 0xFF == 0) 0x02 else 0) or (value and 0x80)
    }

    private fun signed(value: Int): Int = if (value and 0x80 != 0) value - 0x100 else value

    fun save(writer: StateWriter) {
        writer.int(a)
        writer.int(x)
        writer.int(y)
        writer.int(sp)
        writer.int(pc)
        writer.int(psw)
        writer.bool(stopped)
    }

    fun load(reader: StateReader) {
        a = reader.int()
        x = reader.int()
        y = reader.int()
        sp = reader.int()
        pc = reader.int()
        psw = reader.int()
        stopped = reader.bool()
    }

    companion object {
        private const val OR = 0
        private const val AND = 1
        private const val EOR = 2
        private const val CMP = 3
        private const val ADC = 4
        private const val SBC = 5

        private const val ASL = 0
        private const val LSR = 1
        private const val ROL = 2
        private const val ROR = 3
        private const val INC = 4
        private const val DEC = 5

        private val CYCLES = intArrayOf(
            2, 8, 4, 5, 3, 4, 3, 6, 2, 6, 5, 4, 5, 4, 6, 8,
            2, 8, 4, 5, 4, 5, 5, 6, 5, 5, 6, 5, 2, 2, 4, 6,
            2, 8, 4, 5, 3, 4, 3, 6, 2, 6, 5, 4, 5, 4, 5, 4,
            2, 8, 4, 5, 4, 5, 5, 6, 5, 5, 6, 5, 2, 2, 3, 8,
            2, 8, 4, 5, 3, 4, 3, 6, 2, 6, 4, 4, 5, 4, 6, 6,
            2, 8, 4, 5, 4, 5, 5, 6, 5, 5, 4, 5, 2, 2, 4, 3,
            2, 8, 4, 5, 3, 4, 3, 6, 2, 6, 4, 4, 5, 4, 5, 5,
            2, 8, 4, 5, 4, 5, 5, 6, 5, 5, 5, 5, 2, 2, 3, 6,
            2, 8, 4, 5, 3, 4, 3, 6, 2, 6, 5, 4, 5, 2, 4, 5,
            2, 8, 4, 5, 4, 5, 5, 6, 5, 5, 5, 5, 2, 2, 12, 5,
            3, 8, 4, 5, 3, 4, 3, 6, 2, 6, 4, 4, 5, 2, 4, 4,
            2, 8, 4, 5, 4, 5, 5, 6, 5, 5, 5, 5, 2, 2, 3, 4,
            3, 8, 4, 5, 4, 5, 4, 7, 2, 5, 6, 4, 5, 2, 4, 9,
            2, 8, 4, 5, 5, 6, 6, 7, 4, 5, 5, 5, 2, 2, 6, 3,
            2, 8, 4, 5, 3, 4, 3, 6, 2, 4, 5, 3, 4, 3, 4, 3,
            2, 8, 4, 5, 4, 5, 5, 6, 3, 4, 5, 4, 2, 2, 4, 3,
        )
    }
}
