package snes.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SystemTest {
    private fun console(program: ByteArray = ByteArray(0)): SNES = SNES(TestRom.build(program))

    @Test
    fun multiplyProducesProduct() {
        val snes = console()

        snes.bus.write8(0x004202, 0x10)
        snes.bus.write8(0x004203, 0x10)

        val low = snes.bus.read8(0x004216)
        val high = snes.bus.read8(0x004217)

        assertEquals(0x0100, low or (high shl 8))
    }

    @Test
    fun divideProducesQuotientAndRemainder() {
        val snes = console()

        snes.bus.write8(0x004204, 0x64)
        snes.bus.write8(0x004205, 0x00)
        snes.bus.write8(0x004206, 0x07)

        val quotient = snes.bus.read8(0x004214) or (snes.bus.read8(0x004215) shl 8)
        val remainder = snes.bus.read8(0x004216) or (snes.bus.read8(0x004217) shl 8)

        assertEquals(14, quotient)
        assertEquals(2, remainder)
    }

    @Test
    fun divideByZeroReturnsHardwareValues() {
        val snes = console()

        snes.bus.write8(0x004204, 0x34)
        snes.bus.write8(0x004205, 0x12)
        snes.bus.write8(0x004206, 0x00)

        val quotient = snes.bus.read8(0x004214) or (snes.bus.read8(0x004215) shl 8)
        val remainder = snes.bus.read8(0x004216) or (snes.bus.read8(0x004217) shl 8)

        assertEquals(0xFFFF, quotient)
        assertEquals(0x1234, remainder)
    }

    @Test
    fun joypadSerialReportsButtonsInHardwareOrder() {
        val snes = console()

        snes.setButtons(kapi.emu.Button.B or kapi.emu.Button.X)

        snes.bus.write8(0x004016, 1)
        snes.bus.write8(0x004016, 0)

        val bits = IntArray(16) { snes.bus.read8(0x004016) and 1 }

        assertEquals(1, bits[0])
        assertEquals(0, bits[1])
        assertEquals(1, bits[9])

        for (index in intArrayOf(2, 3, 4, 5, 6, 7, 8, 10, 11)) {
            assertEquals(0, bits[index], "bit $index")
        }
    }

    @Test
    fun autoJoypadFillsRegisters() {
        val snes = console()

        snes.setButtons(kapi.emu.Button.START or kapi.emu.Button.A)
        snes.bus.write8(0x004200, 0x01)

        snes.runFrame()

        val value = snes.bus.read8(0x004218) or (snes.bus.read8(0x004219) shl 8)

        assertEquals(Joypad.START or Joypad.A, value)
    }

    @Test
    fun generalDmaCopiesWramToVram() {
        val snes = console()

        for (i in 0 until 16) snes.bus.write8(0x7E0000 + i, i + 1)

        snes.bus.write8(0x002115, 0x80)
        snes.bus.write8(0x002116, 0x00)
        snes.bus.write8(0x002117, 0x00)

        snes.bus.write8(0x004300, 0x01)
        snes.bus.write8(0x004301, 0x18)
        snes.bus.write8(0x004302, 0x00)
        snes.bus.write8(0x004303, 0x00)
        snes.bus.write8(0x004304, 0x7E)
        snes.bus.write8(0x004305, 0x10)
        snes.bus.write8(0x004306, 0x00)

        snes.bus.write8(0x00420B, 0x01)

        snes.runFrame()

        assertEquals(0x0201, snes.ppu.vram[0].toInt() and 0xFFFF)
        assertEquals(0x0403, snes.ppu.vram[1].toInt() and 0xFFFF)
        assertEquals(0x100F, snes.ppu.vram[7].toInt() and 0xFFFF)
    }

    @Test
    fun nmiFiresDuringVblank() {
        val snes = console()

        assertFalse(snes.regs.vblank)

        snes.bus.write8(0x004200, 0x80)
        snes.runFrame()

        assertTrue(snes.regs.nmiFlag || snes.cpu.nmiPending)
    }

    @Test
    fun readingRdnmiClearsFlag() {
        val snes = console()

        snes.regs.nmiFlag = true

        val first = snes.bus.read8(0x004210)
        val second = snes.bus.read8(0x004210)

        assertEquals(0x80, first and 0x80)
        assertEquals(0x00, second and 0x80)
    }

    @Test
    fun wramPortReadsAndWrites() {
        val snes = console()

        snes.bus.write8(0x002181, 0x00)
        snes.bus.write8(0x002182, 0x10)
        snes.bus.write8(0x002183, 0x00)

        snes.bus.write8(0x002180, 0xAB)
        snes.bus.write8(0x002180, 0xCD)

        assertEquals(0xAB, snes.bus.wram[0x1000].toInt() and 0xFF)
        assertEquals(0xCD, snes.bus.wram[0x1001].toInt() and 0xFF)
    }

    @Test
    fun openBusReturnsLastValue() {
        val snes = console()

        snes.bus.write8(0x7E0000, 0x5A)
        snes.bus.read8(0x7E0000)

        assertEquals(0x5A, snes.bus.read8(0x002000))
    }

    @Test
    fun fastRomChangesAccessSpeed() {
        val snes = console()

        assertEquals(Bus.SLOW, snes.bus.speed(0x808000))

        snes.bus.write8(0x00420D, 0x01)

        assertEquals(Bus.FAST, snes.bus.speed(0x808000))
        assertEquals(Bus.SLOW, snes.bus.speed(0x008000))
    }
}
