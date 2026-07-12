package gameboy.core

class ALU(private val registers: Registers) {
    fun add(value: Int) {
        val a = registers.a
        val result = a + value
        registers.a = result and 0xFF
        registers.setFlags(
            zero = result and 0xFF == 0,
            negative = false,
            halfCarry = (a and 0x0F) + (value and 0x0F) > 0x0F,
            carry = result > 0xFF,
        )
    }

    fun adc(value: Int) {
        val a = registers.a
        val carry = if (registers.carry) 1 else 0
        val result = a + value + carry
        registers.a = result and 0xFF
        registers.setFlags(
            zero = result and 0xFF == 0,
            negative = false,
            halfCarry = (a and 0x0F) + (value and 0x0F) + carry > 0x0F,
            carry = result > 0xFF,
        )
    }

    fun sub(value: Int) {
        val a = registers.a
        val result = a - value
        registers.a = result and 0xFF
        registers.setFlags(
            zero = result and 0xFF == 0,
            negative = true,
            halfCarry = (a and 0x0F) < (value and 0x0F),
            carry = result < 0,
        )
    }

    fun sbc(value: Int) {
        val a = registers.a
        val carry = if (registers.carry) 1 else 0
        val result = a - value - carry
        registers.a = result and 0xFF
        registers.setFlags(
            zero = result and 0xFF == 0,
            negative = true,
            halfCarry = (a and 0x0F) < (value and 0x0F) + carry,
            carry = result < 0,
        )
    }

    fun and(value: Int) {
        registers.a = registers.a and value
        registers.setFlags(registers.a == 0, false, true, false)
    }

    fun xor(value: Int) {
        registers.a = (registers.a xor value) and 0xFF
        registers.setFlags(registers.a == 0, false, false, false)
    }

    fun or(value: Int) {
        registers.a = (registers.a or value) and 0xFF
        registers.setFlags(registers.a == 0, false, false, false)
    }

    fun compare(value: Int) {
        val a = registers.a
        val result = a - value
        registers.setFlags(
            zero = result and 0xFF == 0,
            negative = true,
            halfCarry = (a and 0x0F) < (value and 0x0F),
            carry = result < 0,
        )
    }

    fun increment(value: Int): Int {
        val result = (value + 1) and 0xFF
        registers.setFlags(result == 0, false, value and 0x0F == 0x0F, registers.carry)
        return result
    }

    fun decrement(value: Int): Int {
        val result = (value - 1) and 0xFF
        registers.setFlags(result == 0, true, value and 0x0F == 0x00, registers.carry)
        return result
    }

    fun addToHl(value: Int) {
        val current = registers.hl
        val result = current + value
        registers.setFlags(
            zero = registers.zero,
            negative = false,
            halfCarry = (current and 0x0FFF) + (value and 0x0FFF) > 0x0FFF,
            carry = result > 0xFFFF,
        )
        registers.hl = result and 0xFFFF
    }

    fun addSigned(base: Int, operand: Int): Int {
        val offset = operand.toByte().toInt()
        registers.setFlags(
            zero = false,
            negative = false,
            halfCarry = (base and 0x0F) + (operand and 0x0F) > 0x0F,
            carry = (base and 0xFF) + (operand and 0xFF) > 0xFF,
        )
        return (base + offset) and 0xFFFF
    }

    fun decimalAdjust() {
        var value = registers.a
        var carry = registers.carry

        if (!registers.negative) {
            if (carry || value > 0x99) {
                value = (value + 0x60) and 0xFF
                carry = true
            }
            if (registers.halfCarry || (value and 0x0F) > 0x09) {
                value = (value + 0x06) and 0xFF
            }
        } else {
            if (carry) value = (value - 0x60) and 0xFF
            if (registers.halfCarry) value = (value - 0x06) and 0xFF
        }

        registers.a = value and 0xFF
        registers.setFlags(registers.a == 0, registers.negative, false, carry)
    }

    fun complement() {
        registers.a = registers.a.inv() and 0xFF
        registers.setFlags(registers.zero, true, true, registers.carry)
    }

    fun setCarry() {
        registers.setFlags(registers.zero, false, false, true)
    }

    fun complementCarry() {
        registers.setFlags(registers.zero, false, false, !registers.carry)
    }

    fun rotateLeftCircularA() {
        val carry = (registers.a shr 7) and 1
        registers.a = ((registers.a shl 1) or carry) and 0xFF
        registers.setFlags(false, false, false, carry != 0)
    }

    fun rotateRightCircularA() {
        val carry = registers.a and 1
        registers.a = ((registers.a shr 1) or (carry shl 7)) and 0xFF
        registers.setFlags(false, false, false, carry != 0)
    }

    fun rotateLeftA() {
        val carry = if (registers.carry) 1 else 0
        val out = (registers.a shr 7) and 1
        registers.a = ((registers.a shl 1) or carry) and 0xFF
        registers.setFlags(false, false, false, out != 0)
    }

    fun rotateRightA() {
        val carry = if (registers.carry) 1 else 0
        val out = registers.a and 1
        registers.a = ((registers.a shr 1) or (carry shl 7)) and 0xFF
        registers.setFlags(false, false, false, out != 0)
    }

    fun rotateLeftCircular(value: Int): Int {
        val carry = (value shr 7) and 1
        val result = ((value shl 1) or carry) and 0xFF
        registers.setFlags(result == 0, false, false, carry != 0)
        return result
    }

    fun rotateRightCircular(value: Int): Int {
        val carry = value and 1
        val result = ((value shr 1) or (carry shl 7)) and 0xFF
        registers.setFlags(result == 0, false, false, carry != 0)
        return result
    }

    fun rotateLeft(value: Int): Int {
        val carry = if (registers.carry) 1 else 0
        val out = (value shr 7) and 1
        val result = ((value shl 1) or carry) and 0xFF
        registers.setFlags(result == 0, false, false, out != 0)
        return result
    }

    fun rotateRight(value: Int): Int {
        val carry = if (registers.carry) 1 else 0
        val out = value and 1
        val result = ((value shr 1) or (carry shl 7)) and 0xFF
        registers.setFlags(result == 0, false, false, out != 0)
        return result
    }

    fun shiftLeft(value: Int): Int {
        val out = (value shr 7) and 1
        val result = (value shl 1) and 0xFF
        registers.setFlags(result == 0, false, false, out != 0)
        return result
    }

    fun shiftRightArithmetic(value: Int): Int {
        val out = value and 1
        val result = ((value shr 1) or (value and 0x80)) and 0xFF
        registers.setFlags(result == 0, false, false, out != 0)
        return result
    }

    fun shiftRightLogical(value: Int): Int {
        val out = value and 1
        val result = (value shr 1) and 0xFF
        registers.setFlags(result == 0, false, false, out != 0)
        return result
    }

    fun swap(value: Int): Int {
        val result = ((value shl 4) or (value shr 4)) and 0xFF
        registers.setFlags(result == 0, false, false, false)
        return result
    }

    fun testBit(value: Int, bit: Int) {
        registers.setFlags(
            zero = value and (1 shl bit) == 0,
            negative = false,
            halfCarry = true,
            carry = registers.carry,
        )
    }
}
