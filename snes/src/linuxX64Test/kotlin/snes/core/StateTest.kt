package snes.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StateTest {
    private fun counterRom(): ByteArray {
        val program = TestRom.Assembler()
            .clc()
            .xce()
            .rep(0x30)
            .ldaImmediate16(0x0000)
            .byte(0x1A)
            .byte(0x8F).byte(0x00).byte(0x00).byte(0x7E)
            .byte(0x80).byte(0xF9)
            .build()

        return TestRom.build(program)
    }

    private fun hash(console: SNES): Long {
        var value = -0x340d631b7bdddcdbL

        for (element in console.frame) {
            value = (value xor (element.toLong() and 0xFFFF)) * 0x100000001b3L
        }

        for (i in 0 until 0x2000) {
            value = (value xor (console.bus.wram[i].toLong() and 0xFF)) * 0x100000001b3L
        }

        value = (value xor console.cpu.a.toLong()) * 0x100000001b3L
        value = (value xor console.cpu.pc.toLong()) * 0x100000001b3L

        return value
    }

    @Test
    fun programRunsAndIncrementsCounter() {
        val console = SNES(counterRom())

        console.runFrame()

        val counter = (console.bus.wram[0].toInt() and 0xFF) or ((console.bus.wram[1].toInt() and 0xFF) shl 8)

        assertTrue(counter > 0, "counter did not advance")
    }

    @Test
    fun restoredMachineMatchesOriginal() {
        val original = SNES(counterRom())

        for (i in 0 until 10) original.runFrame()

        val snapshot = original.saveState()

        for (i in 0 until 10) original.runFrame()

        val expected = hash(original)

        val restored = SNES(counterRom())
        assertTrue(restored.loadState(snapshot))

        for (i in 0 until 10) restored.runFrame()

        assertEquals(expected, hash(restored), "restored machine diverged")
    }

    @Test
    fun stateRoundTripsByteForByte() {
        val console = SNES(counterRom())

        for (i in 0 until 5) console.runFrame()

        val snapshot = console.saveState()

        val restored = SNES(counterRom())
        assertTrue(restored.loadState(snapshot))

        assertContentEquals(snapshot, restored.saveState())
    }

    @Test
    fun junkIsRejected() {
        val console = SNES(counterRom())
        assertFalse(console.loadState(ByteArray(64)))
    }

    @Test
    fun truncatedStateIsRejected() {
        val console = SNES(counterRom())

        console.runFrame()

        val snapshot = console.saveState()

        assertFalse(console.loadState(snapshot.copyOf(snapshot.size / 2)))
    }

    @Test
    fun stateFromAnotherVersionIsRejected() {
        val console = SNES(counterRom())

        console.runFrame()

        val snapshot = console.saveState()
        snapshot[4] = (snapshot[4] + 1).toByte()

        assertFalse(console.loadState(snapshot))
    }

    @Test
    fun blockMoveCopiesForwardAndSetsDataBank() {
        val program = TestRom.Assembler()
            .clc()
            .xce()
            .rep(0x30)
            .ldaImmediate16(0x0003)
            .ldxImmediate16(0x0100)
            .byte(0xA0).byte(0x00).byte(0x02)
            .byte(0x54).byte(0x7E).byte(0x7E)
            .stp()
            .build()

        val console = SNES(TestRom.build(program))

        for (i in 0 until 4) console.bus.write8(0x7E0100 + i, 0xA0 + i)

        console.runFrame()

        for (i in 0 until 4) {
            assertEquals(0xA0 + i, console.bus.read8(0x7E0200 + i), "byte $i")
        }

        assertEquals(0x7E, console.cpu.dbr)
        assertEquals(0xFFFF, console.cpu.a)
        assertEquals(0x0104, console.cpu.x)
        assertEquals(0x0204, console.cpu.y)
    }

    @Test
    fun blockMoveCopiesBackward() {
        val program = TestRom.Assembler()
            .clc()
            .xce()
            .rep(0x30)
            .ldaImmediate16(0x0003)
            .ldxImmediate16(0x0103)
            .byte(0xA0).byte(0x03).byte(0x02)
            .byte(0x44).byte(0x7E).byte(0x7E)
            .stp()
            .build()

        val console = SNES(TestRom.build(program))

        for (i in 0 until 4) console.bus.write8(0x7E0100 + i, 0xB0 + i)

        console.runFrame()

        for (i in 0 until 4) {
            assertEquals(0xB0 + i, console.bus.read8(0x7E0200 + i), "byte $i")
        }

        assertEquals(0xFFFF, console.cpu.a)
        assertEquals(0x00FF, console.cpu.x)
        assertEquals(0x01FF, console.cpu.y)
    }
}
