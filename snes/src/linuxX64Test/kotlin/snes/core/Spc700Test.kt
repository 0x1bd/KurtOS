package snes.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Spc700Test {
    private class FlatMemory : SpcMemory {
        val data = ByteArray(0x10000)

        override fun read(address: Int): Int = data[address and 0xFFFF].toInt() and 0xFF

        override fun write(address: Int, value: Int) {
            data[address and 0xFFFF] = value.toByte()
        }
    }

    @Test
    fun singleStepVectorsPass() {
        val data = fixture("spc700.vec") ?: return

        val reader = VectorReader(data)
        assertEquals("VSPC", reader.magic())

        val count = reader.u32()
        assertTrue(count > 0)

        val memory = FlatMemory()
        val cpu = Spc700(memory)

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

            cpu.pc = initial[0]
            cpu.a = initial[1]
            cpu.x = initial[2]
            cpu.y = initial[3]
            cpu.sp = initial[4]
            cpu.psw = initial[5]
            cpu.stopped = false

            val used = cpu.step()

            val mismatch = describe(cpu, expected, memory, output)

            val opcode = index / cases
            val halts = opcode == 0xEF || opcode == 0xFF

            if (mismatch != null) {
                failures++
                perOpcode[index / cases]++
                if (firstFailure.isEmpty()) firstFailure = "op ${hex(index / cases)}: $mismatch"
            } else if (!halts && used != cycles) {
                cycleFailures++
                perOpcode[index / cases]++
                if (firstFailure.isEmpty()) {
                    firstFailure = "op ${hex(index / cases)}: cycles $used expected $cycles"
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
            "spc700 failures ${failures + cycleFailures}/$count; first: $firstFailure; opcodes:$broken",
        )
    }

    private fun state(reader: VectorReader): IntArray {
        val values = IntArray(6)
        values[0] = reader.u16()
        values[1] = reader.u8()
        values[2] = reader.u8()
        values[3] = reader.u8()
        values[4] = reader.u8()
        values[5] = reader.u8()
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

    private fun describe(cpu: Spc700, expected: IntArray, memory: FlatMemory, output: IntArray): String? {
        if (cpu.pc != expected[0]) return "pc ${hex(cpu.pc)} expected ${hex(expected[0])}"
        if (cpu.a != expected[1]) return "a ${hex(cpu.a)} expected ${hex(expected[1])}"
        if (cpu.x != expected[2]) return "x ${hex(cpu.x)} expected ${hex(expected[2])}"
        if (cpu.y != expected[3]) return "y ${hex(cpu.y)} expected ${hex(expected[3])}"
        if (cpu.sp != expected[4]) return "sp ${hex(cpu.sp)} expected ${hex(expected[4])}"
        if (cpu.psw != expected[5]) return "psw ${hex(cpu.psw)} expected ${hex(expected[5])}"

        for (i in output.indices step 2) {
            val address = output[i]
            val value = output[i + 1]
            val actual = memory.data[address].toInt() and 0xFF
            if (actual != value) return "ram ${hex(address)} = ${hex(actual)} expected ${hex(value)}"
        }

        return null
    }

    private fun hex(value: Int): String = "0x${value.toString(16)}"
}
