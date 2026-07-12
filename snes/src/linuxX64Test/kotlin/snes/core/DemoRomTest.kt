package snes.core

import kapi.emu.Button
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DemoRomTest {
    private fun demo(): ByteArray? = fixture("../../assets/roms/kurtos-test.sfc")

    @Test
    fun demoRomRendersBackgroundAndSprite() {
        val image = demo() ?: return

        val console = SNES(image)

        for (i in 0 until 8) console.runFrame()

        assertTrue(console.cartridge.supported)
        assertEquals("KURTOS SNES DEMO", console.cartridge.title)

        val red = 0x001F

        assertEquals(red, console.frame[0].toInt() and 0xFFFF, "background pixel")
        assertEquals(red, console.frame[100 * Ppu.WIDTH + 10].toInt() and 0xFFFF, "background pixel")

        val white = 0x7FFF
        val spriteRow = 0x68

        var spritePixels = 0
        for (x in 0x78 until 0x80) {
            if (console.frame[spriteRow * Ppu.WIDTH + x].toInt() and 0xFFFF == white) spritePixels++
        }

        assertEquals(8, spritePixels, "sprite row should be 8 white pixels")
    }

    @Test
    fun demoRomRespondsToJoypad() {
        val image = demo() ?: return

        val console = SNES(image)

        for (i in 0 until 8) console.runFrame()

        assertEquals(0x00, console.bus.wram[0].toInt() and 0xFF, "no buttons held")

        console.setButtons(Button.A or Button.X)

        for (i in 0 until 8) console.runFrame()

        assertEquals(
            Joypad.A or Joypad.X,
            console.bus.wram[0].toInt() and 0xFF,
            "A and X should reach the program through auto-joypad",
        )
    }
}
