package snes.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Cpu65816Test {
    @Test
    fun singleStepVectorsPass() {
        val data = fixture("65816.vec") ?: return

        val reader = VectorReader(data)
        assertEquals("V651", reader.magic())

        val count = reader.u32()
        assertTrue(count > 0)

        val memory = TestMemory(0x1000000)
        val cpu = Cpu(memory)

        var failures = 0
        var cycleFailures = 0
        var firstFailure = ""

        val perOpcode = IntArray(256)
        val cases = count / 256

        for (index in 0 until count) {
            val initial = state(reader)
            val expected = state(reader)
            val cycles = reader.u16()

            val input = ram(reader)
            val output = ram(reader)

            for (i in input.indices step 2) memory.data[input[i]] = input[i + 1].toByte()

            apply(cpu, initial)

            val used = cpu.step()

            val mismatch = describe(cpu, expected, memory, output)

            val opcode = index / cases
            val mode = if (initial[9] != 0) "e" else "n"

            if (opcode in UNBOUNDED) {
                for (i in input.indices step 2) memory.data[input[i]] = 0
                for (i in output.indices step 2) memory.data[output[i]] = 0
                continue
            }

            if (mismatch != null) {
                failures++
                perOpcode[opcode]++
                if (firstFailure.isEmpty()) {
                    firstFailure = "op ${hex(opcode)}$mode: $mismatch" +
                        " [init pc=${hex(initial[0])} s=${hex(initial[1])} a=${hex(initial[2])}" +
                        " x=${hex(initial[3])} y=${hex(initial[4])} d=${hex(initial[5])}" +
                        " dbr=${hex(initial[6])} p=${hex(initial[8])}]"
                }
            } else if (used / Cpu.INTERNAL != cycles) {
                cycleFailures++
                perOpcode[opcode]++
                if (firstFailure.isEmpty()) {
                    firstFailure = "op ${hex(opcode)}$mode: cycles ${used / Cpu.INTERNAL} expected $cycles" +
                        " [init pc=${hex(initial[0])} s=${hex(initial[1])} a=${hex(initial[2])}" +
                        " x=${hex(initial[3])} y=${hex(initial[4])} d=${hex(initial[5])}" +
                        " dbr=${hex(initial[6])} p=${hex(initial[8])}]"
                }
            }

            for (i in input.indices step 2) memory.data[input[i]] = 0
            for (i in output.indices step 2) memory.data[output[i]] = 0
        }

        val broken = StringBuilder()
        for (opcode in 0 until 256) {
            if (perOpcode[opcode] > 0) broken.append(" ${hex(opcode)}=${perOpcode[opcode]}")
        }

        assertEquals(
            0,
            failures + cycleFailures,
            "65816 failures ${failures + cycleFailures}/$count; first: $firstFailure; opcodes:$broken",
        )
    }

    private fun state(reader: VectorReader): IntArray {
        val values = IntArray(10)
        values[0] = reader.u16()
        values[1] = reader.u16()
        values[2] = reader.u16()
        values[3] = reader.u16()
        values[4] = reader.u16()
        values[5] = reader.u16()
        values[6] = reader.u8()
        values[7] = reader.u8()
        values[8] = reader.u8()
        values[9] = reader.u8()
        return values
    }

    private fun ram(reader: VectorReader): IntArray {
        val count = reader.u16()
        val entries = IntArray(count * 2)

        for (i in 0 until count) {
            entries[i * 2] = reader.u32()
            entries[i * 2 + 1] = reader.u8()
        }

        return entries
    }

    private fun apply(cpu: Cpu, values: IntArray) {
        cpu.pc = values[0]
        cpu.s = values[1]
        cpu.a = values[2]
        cpu.x = values[3]
        cpu.y = values[4]
        cpu.d = values[5]
        cpu.dbr = values[6]
        cpu.pbr = values[7]
        cpu.p = values[8]
        cpu.emulation = values[9] != 0
        cpu.waiting = false
        cpu.stopped = false
        cpu.nmiPending = false
        cpu.irqLine = false
    }

    private fun describe(cpu: Cpu, expected: IntArray, memory: TestMemory, output: IntArray): String? {
        if (cpu.pc != expected[0]) return "pc ${hex(cpu.pc)} expected ${hex(expected[0])}"
        if (cpu.s != expected[1]) return "s ${hex(cpu.s)} expected ${hex(expected[1])}"
        if (cpu.a != expected[2]) return "a ${hex(cpu.a)} expected ${hex(expected[2])}"
        if (cpu.x != expected[3]) return "x ${hex(cpu.x)} expected ${hex(expected[3])}"
        if (cpu.y != expected[4]) return "y ${hex(cpu.y)} expected ${hex(expected[4])}"
        if (cpu.d != expected[5]) return "d ${hex(cpu.d)} expected ${hex(expected[5])}"
        if (cpu.dbr != expected[6]) return "dbr ${hex(cpu.dbr)} expected ${hex(expected[6])}"
        if (cpu.pbr != expected[7]) return "pbr ${hex(cpu.pbr)} expected ${hex(expected[7])}"
        if (cpu.p != expected[8]) return "p ${hex(cpu.p)} expected ${hex(expected[8])}"
        if ((if (cpu.emulation) 1 else 0) != expected[9]) return "e ${cpu.emulation}"

        for (i in output.indices step 2) {
            val address = output[i]
            val value = output[i + 1]
            val actual = memory.data[address].toInt() and 0xFF
            if (actual != value) return "ram ${hex(address)} = ${hex(actual)} expected ${hex(value)}"
        }

        return null
    }

    private fun hex(value: Int): String = "0x${value.toString(16)}"

    companion object {
        private val UNBOUNDED = intArrayOf(0x44, 0x54, 0xCB, 0xDB)
    }
}
