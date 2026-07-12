package snes.core

import kotlin.test.Test
import kotlin.test.assertEquals

class PpuTest {
    private val ppu = Ppu()

    private fun reg(address: Int, value: Int) = ppu.writeReg(address, value)

    private fun bgr(red: Int, green: Int, blue: Int): Int = red or (green shl 5) or (blue shl 10)

    private fun pixel(x: Int, y: Int): Int = ppu.frame[y * Ppu.WIDTH + x].toInt() and 0xFFFF

    private fun setup() {
        ppu.reset()
        reg(0x2100, 0x0F)
    }

    private fun tile4bpp(base: Int, index: Int, rows: IntArray) {
        val address = base + index * 16

        for (row in 0 until 8) {
            val bits = rows[row]

            var plane0 = 0
            var plane1 = 0
            var plane2 = 0
            var plane3 = 0

            for (column in 0 until 8) {
                val color = (bits ushr ((7 - column) * 4)) and 0x0F
                val bit = 7 - column

                if (color and 1 != 0) plane0 = plane0 or (1 shl bit)
                if (color and 2 != 0) plane1 = plane1 or (1 shl bit)
                if (color and 4 != 0) plane2 = plane2 or (1 shl bit)
                if (color and 8 != 0) plane3 = plane3 or (1 shl bit)
            }

            ppu.vram[address + row] = (plane0 or (plane1 shl 8)).toShort()
            ppu.vram[address + 8 + row] = (plane2 or (plane3 shl 8)).toShort()
        }
    }

    @Test
    fun forcedBlankOutputsBlack() {
        setup()
        reg(0x2100, 0x80)

        ppu.cgram[0] = bgr(31, 31, 31).toShort()

        ppu.renderLine(1)

        assertEquals(0, pixel(0, 0))
    }

    @Test
    fun backdropFillsScreenWhenNoLayersEnabled() {
        setup()

        val color = bgr(3, 5, 7)
        ppu.cgram[0] = color.toShort()

        reg(0x2105, 1)
        reg(0x212C, 0x00)

        ppu.renderLine(1)

        assertEquals(color, pixel(0, 0))
        assertEquals(color, pixel(255, 0))
    }

    @Test
    fun mode1DrawsBg1TilePixels() {
        setup()

        ppu.cgram[0] = 0
        ppu.cgram[1] = bgr(31, 0, 0).toShort()
        ppu.cgram[2] = bgr(0, 31, 0).toShort()

        tile4bpp(0x0000, 1, IntArray(8) { if (it == 0) 0x12000000 else 0 })

        ppu.vram[0x0400] = 0x0001

        reg(0x2105, 0x01)
        reg(0x2107, 0x04)
        reg(0x210B, 0x00)
        reg(0x212C, 0x01)

        ppu.renderLine(1)

        assertEquals(bgr(31, 0, 0), pixel(0, 0))
        assertEquals(bgr(0, 31, 0), pixel(1, 0))
        assertEquals(0, pixel(2, 0))
    }

    @Test
    fun horizontalScrollShiftsTile() {
        setup()

        ppu.cgram[0] = 0
        ppu.cgram[1] = bgr(31, 0, 0).toShort()

        tile4bpp(0x0000, 1, IntArray(8) { if (it == 0) 0x10000000 else 0 })
        ppu.vram[0x0400] = 0x0001

        reg(0x2105, 0x01)
        reg(0x2107, 0x04)
        reg(0x210B, 0x00)
        reg(0x212C, 0x01)

        reg(0x210D, 0x00)
        reg(0x210D, 0x00)

        ppu.renderLine(1)
        assertEquals(bgr(31, 0, 0), pixel(0, 0))

        setup()
        ppu.cgram[0] = 0
        ppu.cgram[1] = bgr(31, 0, 0).toShort()
        tile4bpp(0x0000, 1, IntArray(8) { if (it == 0) 0x10000000 else 0 })
        ppu.vram[0x0400] = 0x0001

        reg(0x2105, 0x01)
        reg(0x2107, 0x04)
        reg(0x210B, 0x00)
        reg(0x212C, 0x01)

        reg(0x210D, 0xFF)
        reg(0x210D, 0x03)

        ppu.renderLine(1)

        assertEquals(bgr(31, 0, 0), pixel(1, 0))
        assertEquals(0, pixel(0, 0))
    }

    @Test
    fun brightnessScalesOutput() {
        setup()

        ppu.cgram[0] = bgr(31, 31, 31).toShort()

        reg(0x2105, 1)
        reg(0x2100, 0x07)

        ppu.renderLine(1)

        val expected = (31 * 7 + 7) / 15

        assertEquals(bgr(expected, expected, expected), pixel(0, 0))
    }

    @Test
    fun spriteDrawsOverBackdrop() {
        setup()

        ppu.cgram[0] = 0
        ppu.cgram[128 + 1] = bgr(0, 0, 31).toShort()

        tile4bpp(0x4000, 0, IntArray(8) { 0x11111111 })

        ppu.oam[0] = 10
        ppu.oam[1] = 0
        ppu.oam[2] = 0
        ppu.oam[3] = 0x00

        reg(0x2105, 0x01)
        reg(0x2101, 0x02)
        reg(0x212C, 0x10)

        ppu.renderLine(1)

        assertEquals(bgr(0, 0, 31), pixel(10, 0))
        assertEquals(0, pixel(9, 0))
        assertEquals(bgr(0, 0, 31), pixel(17, 0))
        assertEquals(0, pixel(18, 0))
    }

    @Test
    fun colorMathAddsSubScreen() {
        setup()

        ppu.cgram[0] = 0
        ppu.cgram[1] = bgr(10, 0, 0).toShort()
        ppu.cgram[17] = bgr(5, 0, 0).toShort()

        tile4bpp(0x0000, 1, IntArray(8) { if (it == 0) 0x10000000 else 0 })
        tile4bpp(0x0000, 2, IntArray(8) { if (it == 0) 0x10000000 else 0 })

        ppu.vram[0x0400] = 0x0001
        ppu.vram[0x0800] = 0x0402

        reg(0x2105, 0x01)
        reg(0x2107, 0x04)
        reg(0x2108, 0x08)
        reg(0x210B, 0x00)

        reg(0x212C, 0x01)
        reg(0x212D, 0x02)

        reg(0x2130, 0x02)
        reg(0x2131, 0x01)

        ppu.renderLine(1)

        assertEquals(bgr(15, 0, 0), pixel(0, 0))
    }

    @Test
    fun windowMasksLayer() {
        setup()

        ppu.cgram[0] = 0
        ppu.cgram[1] = bgr(31, 0, 0).toShort()

        tile4bpp(0x0000, 1, IntArray(8) { 0x11111111 })

        for (i in 0 until 32) ppu.vram[0x0400 + i] = 0x0001

        reg(0x2105, 0x01)
        reg(0x2107, 0x04)
        reg(0x210B, 0x00)
        reg(0x212C, 0x01)

        reg(0x2123, 0x02)
        reg(0x2126, 4)
        reg(0x2127, 8)
        reg(0x212E, 0x01)

        ppu.renderLine(1)

        assertEquals(bgr(31, 0, 0), pixel(3, 0))
        assertEquals(0, pixel(4, 0))
        assertEquals(0, pixel(8, 0))
        assertEquals(bgr(31, 0, 0), pixel(9, 0))
    }

    @Test
    fun mode7SamplesCharacterData() {
        setup()

        ppu.cgram[0] = 0
        ppu.cgram[1] = bgr(31, 31, 0).toShort()

        for (i in 0 until 128 * 128) ppu.vram[i] = 0

        ppu.vram[0] = 1

        for (row in 0 until 8) {
            for (column in 0 until 8) {
                ppu.vram[1 * 64 + row * 8 + column] = (1 shl 8).toShort()
            }
        }

        reg(0x2105, 0x07)

        reg(0x211B, 0x00)
        reg(0x211B, 0x01)
        reg(0x211C, 0x00)
        reg(0x211C, 0x00)
        reg(0x211D, 0x00)
        reg(0x211D, 0x00)
        reg(0x211E, 0x00)
        reg(0x211E, 0x01)

        reg(0x211F, 0x00)
        reg(0x211F, 0x00)
        reg(0x2120, 0x00)
        reg(0x2120, 0x00)

        reg(0x212C, 0x01)

        ppu.renderLine(1)

        assertEquals(bgr(31, 31, 0), pixel(0, 0))
        assertEquals(bgr(31, 31, 0), pixel(7, 0))
    }
}
