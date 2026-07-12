package gameboy.core

class CPU(private val bus: Bus, private val interrupts: Interrupts) {
    private val registers = Registers()
    private val alu = ALU(registers)

    private var ime = false
    private var imeDelay = 0
    private var halted = false
    private var haltBug = false

    fun step(): Int {
        val serviced = serviceInterrupt()
        if (serviced > 0) return serviced

        if (halted) return 4

        val opcode = fetch8()
        if (haltBug) {
            registers.pc = (registers.pc - 1) and 0xFFFF
            haltBug = false
        }

        val cycles = execute(opcode)

        if (imeDelay > 0) {
            imeDelay--
            if (imeDelay == 0) ime = true
        }

        return cycles
    }

    private fun serviceInterrupt(): Int {
        val pending = interrupts.pending()

        if (!ime) {
            if (halted && pending != 0) halted = false
            return 0
        }

        if (pending == 0) return 0

        halted = false
        ime = false

        val bit = pending and (-pending)
        interrupts.clear(bit)

        push(registers.pc)
        registers.pc = when (bit) {
            Interrupts.VBLANK -> 0x40
            Interrupts.LCD -> 0x48
            Interrupts.TIMER -> 0x50
            Interrupts.SERIAL -> 0x58
            else -> 0x60
        }

        return 20
    }

    private fun execute(opcode: Int): Int {
        if (opcode == Opcodes.PREFIX) return executePrefixed(fetch8())

        return when (opcode shr 6) {
            0 -> executeImmediate(opcode)
            1 -> executeLoad(opcode)
            2 -> executeArithmetic(opcode)
            else -> executeControl(opcode)
        }
    }

    private fun executeImmediate(opcode: Int): Int {
        val cycles = Opcodes.CYCLES[opcode]
        val target = (opcode shr 3) and 7

        when (opcode and 7) {
            4 -> {
                setRegister(target, alu.increment(register(target)))
                return cycles
            }

            5 -> {
                setRegister(target, alu.decrement(register(target)))
                return cycles
            }

            6 -> {
                setRegister(target, fetch8())
                return cycles
            }
        }

        when (opcode) {
            0x00 -> {}
            0x10 -> fetch8()

            0x01 -> registers.bc = fetch16()
            0x11 -> registers.de = fetch16()
            0x21 -> registers.hl = fetch16()
            0x31 -> registers.sp = fetch16()

            0x02 -> bus.write(registers.bc, registers.a)
            0x12 -> bus.write(registers.de, registers.a)
            0x22 -> {
                bus.write(registers.hl, registers.a)
                registers.hl = (registers.hl + 1) and 0xFFFF
            }

            0x32 -> {
                bus.write(registers.hl, registers.a)
                registers.hl = (registers.hl - 1) and 0xFFFF
            }

            0x0A -> registers.a = bus.read(registers.bc)
            0x1A -> registers.a = bus.read(registers.de)
            0x2A -> {
                registers.a = bus.read(registers.hl)
                registers.hl = (registers.hl + 1) and 0xFFFF
            }

            0x3A -> {
                registers.a = bus.read(registers.hl)
                registers.hl = (registers.hl - 1) and 0xFFFF
            }

            0x03 -> registers.bc = (registers.bc + 1) and 0xFFFF
            0x13 -> registers.de = (registers.de + 1) and 0xFFFF
            0x23 -> registers.hl = (registers.hl + 1) and 0xFFFF
            0x33 -> registers.sp = (registers.sp + 1) and 0xFFFF

            0x0B -> registers.bc = (registers.bc - 1) and 0xFFFF
            0x1B -> registers.de = (registers.de - 1) and 0xFFFF
            0x2B -> registers.hl = (registers.hl - 1) and 0xFFFF
            0x3B -> registers.sp = (registers.sp - 1) and 0xFFFF

            0x09 -> alu.addToHl(registers.bc)
            0x19 -> alu.addToHl(registers.de)
            0x29 -> alu.addToHl(registers.hl)
            0x39 -> alu.addToHl(registers.sp)

            0x07 -> alu.rotateLeftCircularA()
            0x0F -> alu.rotateRightCircularA()
            0x17 -> alu.rotateLeftA()
            0x1F -> alu.rotateRightA()

            0x27 -> alu.decimalAdjust()
            0x2F -> alu.complement()
            0x37 -> alu.setCarry()
            0x3F -> alu.complementCarry()

            0x08 -> {
                val address = fetch16()
                bus.write(address, registers.sp and 0xFF)
                bus.write((address + 1) and 0xFFFF, (registers.sp shr 8) and 0xFF)
            }

            0x18 -> return cycles + jumpRelative(true)
            0x20 -> return cycles + jumpRelative(!registers.zero)
            0x28 -> return cycles + jumpRelative(registers.zero)
            0x30 -> return cycles + jumpRelative(!registers.carry)
            0x38 -> return cycles + jumpRelative(registers.carry)
        }

        return cycles
    }

    private fun executeLoad(opcode: Int): Int {
        if (opcode == 0x76) {
            halt()
            return Opcodes.CYCLES[opcode]
        }

        setRegister((opcode shr 3) and 7, register(opcode and 7))
        return Opcodes.CYCLES[opcode]
    }

    private fun executeArithmetic(opcode: Int): Int {
        arithmetic((opcode shr 3) and 7, register(opcode and 7))
        return Opcodes.CYCLES[opcode]
    }

    private fun executeControl(opcode: Int): Int {
        val cycles = Opcodes.CYCLES[opcode]

        when (opcode) {
            0xF3 -> {
                ime = false
                imeDelay = 0
            }

            0xFB -> imeDelay = 2

            0xC3 -> registers.pc = fetch16()
            0xC2 -> return cycles + jumpAbsolute(!registers.zero)
            0xCA -> return cycles + jumpAbsolute(registers.zero)
            0xD2 -> return cycles + jumpAbsolute(!registers.carry)
            0xDA -> return cycles + jumpAbsolute(registers.carry)
            0xE9 -> registers.pc = registers.hl

            0xCD -> call(fetch16())
            0xC4 -> return cycles + callConditional(!registers.zero)
            0xCC -> return cycles + callConditional(registers.zero)
            0xD4 -> return cycles + callConditional(!registers.carry)
            0xDC -> return cycles + callConditional(registers.carry)

            0xC9 -> registers.pc = pop()
            0xC0 -> return cycles + returnConditional(!registers.zero)
            0xC8 -> return cycles + returnConditional(registers.zero)
            0xD0 -> return cycles + returnConditional(!registers.carry)
            0xD8 -> return cycles + returnConditional(registers.carry)
            0xD9 -> {
                registers.pc = pop()
                ime = true
                imeDelay = 0
            }

            0xC7, 0xCF, 0xD7, 0xDF, 0xE7, 0xEF, 0xF7, 0xFF -> call(opcode and 0x38)

            0xC1 -> registers.bc = pop()
            0xD1 -> registers.de = pop()
            0xE1 -> registers.hl = pop()
            0xF1 -> registers.af = pop()

            0xC5 -> push(registers.bc)
            0xD5 -> push(registers.de)
            0xE5 -> push(registers.hl)
            0xF5 -> push(registers.af)

            0xE0 -> bus.write(0xFF00 or fetch8(), registers.a)
            0xF0 -> registers.a = bus.read(0xFF00 or fetch8())
            0xE2 -> bus.write(0xFF00 or registers.c, registers.a)
            0xF2 -> registers.a = bus.read(0xFF00 or registers.c)
            0xEA -> bus.write(fetch16(), registers.a)
            0xFA -> registers.a = bus.read(fetch16())

            0xE8 -> registers.sp = alu.addSigned(registers.sp, fetch8())
            0xF8 -> registers.hl = alu.addSigned(registers.sp, fetch8())
            0xF9 -> registers.sp = registers.hl

            0xC6 -> arithmetic(0, fetch8())
            0xCE -> arithmetic(1, fetch8())
            0xD6 -> arithmetic(2, fetch8())
            0xDE -> arithmetic(3, fetch8())
            0xE6 -> arithmetic(4, fetch8())
            0xEE -> arithmetic(5, fetch8())
            0xF6 -> arithmetic(6, fetch8())
            0xFE -> arithmetic(7, fetch8())
        }

        return cycles
    }

    private fun executePrefixed(opcode: Int): Int {
        val index = opcode and 7
        val operation = opcode shr 3
        val value = register(index)

        if (operation in 8..15) {
            alu.testBit(value, operation and 7)
            return if (index == 6) 12 else 8
        }

        val result = when (operation) {
            0 -> alu.rotateLeftCircular(value)
            1 -> alu.rotateRightCircular(value)
            2 -> alu.rotateLeft(value)
            3 -> alu.rotateRight(value)
            4 -> alu.shiftLeft(value)
            5 -> alu.shiftRightArithmetic(value)
            6 -> alu.swap(value)
            7 -> alu.shiftRightLogical(value)
            in 16..23 -> value and (1 shl (operation and 7)).inv()
            else -> value or (1 shl (operation and 7))
        }

        setRegister(index, result)
        return if (index == 6) 16 else 8
    }

    private fun halt() {
        if (!ime && interrupts.pending() != 0) {
            haltBug = true
        } else {
            halted = true
        }
    }

    private fun arithmetic(operation: Int, value: Int) {
        when (operation) {
            0 -> alu.add(value)
            1 -> alu.adc(value)
            2 -> alu.sub(value)
            3 -> alu.sbc(value)
            4 -> alu.and(value)
            5 -> alu.xor(value)
            6 -> alu.or(value)
            else -> alu.compare(value)
        }
    }

    private fun register(index: Int): Int = when (index) {
        0 -> registers.b
        1 -> registers.c
        2 -> registers.d
        3 -> registers.e
        4 -> registers.h
        5 -> registers.l
        6 -> bus.read(registers.hl)
        else -> registers.a
    }

    private fun setRegister(index: Int, value: Int) {
        val v = value and 0xFF
        when (index) {
            0 -> registers.b = v
            1 -> registers.c = v
            2 -> registers.d = v
            3 -> registers.e = v
            4 -> registers.h = v
            5 -> registers.l = v
            6 -> bus.write(registers.hl, v)
            else -> registers.a = v
        }
    }

    private fun jumpRelative(condition: Boolean): Int {
        val offset = fetch8()
        if (!condition) return 0

        registers.pc = (registers.pc + offset.toByte().toInt()) and 0xFFFF
        return Opcodes.BRANCH_TAKEN
    }

    private fun jumpAbsolute(condition: Boolean): Int {
        val address = fetch16()
        if (!condition) return 0

        registers.pc = address
        return Opcodes.BRANCH_TAKEN
    }

    private fun callConditional(condition: Boolean): Int {
        val address = fetch16()
        if (!condition) return 0

        call(address)
        return Opcodes.CALL_TAKEN
    }

    private fun returnConditional(condition: Boolean): Int {
        if (!condition) return 0

        registers.pc = pop()
        return Opcodes.RETURN_TAKEN
    }

    private fun call(address: Int) {
        push(registers.pc)
        registers.pc = address and 0xFFFF
    }

    private fun push(value: Int) {
        registers.sp = (registers.sp - 1) and 0xFFFF
        bus.write(registers.sp, (value shr 8) and 0xFF)
        registers.sp = (registers.sp - 1) and 0xFFFF
        bus.write(registers.sp, value and 0xFF)
    }

    private fun pop(): Int {
        val low = bus.read(registers.sp)
        registers.sp = (registers.sp + 1) and 0xFFFF
        val high = bus.read(registers.sp)
        registers.sp = (registers.sp + 1) and 0xFFFF
        return ((high shl 8) or low) and 0xFFFF
    }

    private fun fetch8(): Int {
        val value = bus.read(registers.pc)
        registers.pc = (registers.pc + 1) and 0xFFFF
        return value and 0xFF
    }

    private fun fetch16(): Int {
        val low = fetch8()
        val high = fetch8()
        return (high shl 8) or low
    }
}
