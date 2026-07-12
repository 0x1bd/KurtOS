package snes.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApuTest {
    @Test
    fun iplRomCompletesBootHandshake() {
        val apu = Apu()
        apu.reset()

        apu.catchUp(MASTER_PER_FRAME)

        assertEquals(0xAA, apu.readPort(0))
        assertEquals(0xBB, apu.readPort(1))
    }

    @Test
    fun uploadThroughIplTransfersProgram() {
        val apu = Apu()
        apu.reset()

        apu.catchUp(MASTER_PER_FRAME)

        val target = 0x0200
        val program = byteArrayOf(0xE8.toByte(), 0x42, 0x5D, 0xEF.toByte())

        var clock = MASTER_PER_FRAME

        apu.writePort(1, 0x01)
        apu.writePort(2, target and 0xFF)
        apu.writePort(3, (target shr 8) and 0xFF)
        apu.writePort(0, 0xCC)

        clock = wait(apu, clock) { apu.readPort(0) == 0xCC }

        for (index in program.indices) {
            apu.writePort(1, program[index].toInt() and 0xFF)
            apu.writePort(0, index and 0xFF)

            clock = wait(apu, clock) { apu.readPort(0) == (index and 0xFF) }
        }

        apu.writePort(2, target and 0xFF)
        apu.writePort(3, (target shr 8) and 0xFF)
        apu.writePort(1, 0x00)
        apu.writePort(0, ((program.size + 2) or 1) and 0xFF)

        clock = wait(apu, clock) { apu.spc.pc == target || apu.spc.stopped }

        for (index in program.indices) {
            assertEquals(program[index], apu.ram[target + index], "byte $index")
        }
    }

    @Test
    fun timersCountAtConfiguredRate() {
        val apu = Apu()
        apu.reset()

        apu.write(0x00FA, 0x10)
        apu.write(0x00F1, 0x01)

        apu.catchUp(MASTER_PER_FRAME)

        val value = apu.read(0x00FD)

        assertTrue(value > 0, "timer 0 did not advance")
        assertEquals(0, apu.read(0x00FD), "timer output did not clear on read")
    }

    @Test
    fun portsAreBidirectional() {
        val apu = Apu()
        apu.reset()

        apu.writePort(0, 0x12)
        assertEquals(0x12, apu.read(0x00F4))

        apu.write(0x00F5, 0x34)
        assertEquals(0x34, apu.readPort(1))
    }

    @Test
    fun audioResamplesToFortyEightKilohertz() {
        val apu = Apu()
        apu.reset()

        apu.catchUp(MASTER_PER_FRAME)

        val expected = Apu.SAMPLE_RATE / 60

        assertTrue(
            apu.frames in (expected - 40)..(expected + 40),
            "produced ${apu.frames} frames, expected about $expected",
        )
    }

    private fun voice(apu: Apu, header: Int, nibble: Int) {
        val directory = 0x1000
        val sample = 0x1100

        apu.ram[directory] = (sample and 0xFF).toByte()
        apu.ram[directory + 1] = ((sample shr 8) and 0xFF).toByte()
        apu.ram[directory + 2] = (sample and 0xFF).toByte()
        apu.ram[directory + 3] = ((sample shr 8) and 0xFF).toByte()

        apu.ram[sample] = header.toByte()

        val packed = ((nibble shl 4) or nibble).toByte()
        for (i in 1 until 9) apu.ram[sample + i] = packed

        val dsp = apu.dsp

        dsp.write(Sdsp.DIR, directory shr 8)
        dsp.write(Sdsp.FLG, 0x20)
        dsp.write(Sdsp.SRCN, 0)
        dsp.write(Sdsp.ADSR0, 0x00)
        dsp.write(Sdsp.GAIN, 0x7F)
        dsp.write(Sdsp.PITCH_LOW, 0x00)
        dsp.write(Sdsp.PITCH_HIGH, 0x10)
        dsp.write(Sdsp.VOL_LEFT, 0x7F)
        dsp.write(Sdsp.VOL_RIGHT, 0x7F)
        dsp.write(Sdsp.MVOL_LEFT, 0x7F)
        dsp.write(Sdsp.MVOL_RIGHT, 0x7F)
        dsp.write(Sdsp.KON, 0x01)
    }

    @Test
    fun directGainOpensEnvelopeToProgrammedLevel() {
        val apu = Apu()
        apu.reset()

        voice(apu, header = 0xC0, nibble = 7)

        for (i in 0 until 8) apu.dsp.sample()

        assertEquals(0x7F, apu.dsp.regs[Sdsp.ENVX])
    }

    @Test
    fun brrDecodesDcBlockToExpectedLevel() {
        val apu = Apu()
        apu.reset()

        voice(apu, header = 0xC0, nibble = 7)

        for (i in 0 until 12) apu.dsp.sample()

        val decoded = ((7 shl 12) shr 1) * 2
        val expected = (decoded * 0x7F0) shr 11

        val outx = (expected shr 8) and 0xFF

        assertTrue(
            apu.dsp.regs[Sdsp.OUTX] in (outx - 2)..(outx + 2),
            "outx ${apu.dsp.regs[Sdsp.OUTX]} expected about $outx",
        )
    }

    @Test
    fun brrEndWithoutLoopReleasesVoice() {
        val apu = Apu()
        apu.reset()

        voice(apu, header = 0xC1, nibble = 7)

        for (i in 0 until 24) apu.dsp.sample()

        assertEquals(1, apu.dsp.regs[Sdsp.ENDX] and 1)
        assertEquals(0, apu.dsp.regs[Sdsp.ENVX])
    }

    @Test
    fun brrEndWithLoopKeepsVoicePlaying() {
        val apu = Apu()
        apu.reset()

        voice(apu, header = 0xC3, nibble = 7)

        for (i in 0 until 24) apu.dsp.sample()

        assertEquals(1, apu.dsp.regs[Sdsp.ENDX] and 1)
        assertEquals(0x7F, apu.dsp.regs[Sdsp.ENVX])
    }

    private inline fun wait(apu: Apu, start: Long, condition: () -> Boolean): Long {
        var clock = start

        for (step in 0 until 20000) {
            clock += 64
            apu.catchUp(clock)
            if (condition()) return clock
        }

        return clock
    }

    companion object {
        private const val MASTER_PER_FRAME = 357_368L
    }
}
