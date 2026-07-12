package snes.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CartridgeTest {
    private fun rom(
        size: Int,
        hi: Boolean,
        sramCode: Int = 0,
        romType: Int = 0,
        title: String = "KURTOS TEST",
    ): ByteArray {
        val image = ByteArray(size)
        val header = if (hi) 0xFFC0 else 0x7FC0

        for (i in 0 until 21) {
            image[header + i] = if (i < title.length) title[i].code.toByte() else 0x20
        }

        image[header + 0x15] = (if (hi) 0x21 else 0x20).toByte()
        image[header + 0x16] = romType.toByte()
        image[header + 0x17] = 0x0A
        image[header + 0x18] = sramCode.toByte()

        image[header + 0x1C] = 0x00
        image[header + 0x1D] = 0x00
        image[header + 0x1E] = 0xFF.toByte()
        image[header + 0x1F] = 0xFF.toByte()

        image[header + 0x3C] = 0x00
        image[header + 0x3D] = 0x80.toByte()

        val reset = if (hi) 0x8000 else 0x0000
        image[reset] = 0x78

        return image
    }

    @Test
    fun detectsLoRom() {
        val cartridge = Cartridge(rom(0x80000, false))

        assertTrue(cartridge.supported)
        assertFalse(cartridge.hiRom)
        assertEquals("KURTOS TEST", cartridge.title)
    }

    @Test
    fun detectsHiRom() {
        val cartridge = Cartridge(rom(0x100000, true))

        assertTrue(cartridge.supported)
        assertTrue(cartridge.hiRom)
    }

    @Test
    fun stripsCopierHeader() {
        val base = rom(0x80000, false)
        val image = ByteArray(base.size + 512)
        base.copyInto(image, 512)

        val cartridge = Cartridge(image)

        assertTrue(cartridge.supported)
        assertEquals(0x80000, cartridge.rom.size)
    }

    @Test
    fun rejectsGarbage() {
        val cartridge = Cartridge(ByteArray(0x80000))
        assertFalse(cartridge.supported)
    }

    @Test
    fun loRomMapsBanksToRom() {
        val image = rom(0x80000, false)
        image[0x000000] = 0x11
        image[0x008000] = 0x22
        image[0x07FFFF] = 0x33

        val cartridge = Cartridge(image)

        assertEquals(0x11, cartridge.read(0x008000))
        assertEquals(0x22, cartridge.read(0x018000))
        assertEquals(0x33, cartridge.read(0x0FFFFF))
    }

    @Test
    fun loRomMirrorsSmallRom() {
        val image = rom(0x8000, false)
        image[0x0000] = 0x5A

        val cartridge = Cartridge(image)

        assertEquals(0x5A, cartridge.read(0x008000))
        assertEquals(0x5A, cartridge.read(0x018000))
        assertEquals(0x5A, cartridge.read(0x808000))
    }

    @Test
    fun hiRomMapsBanksToRom() {
        val image = rom(0x100000, true)
        image[0x000000] = 0xAB.toByte()
        image[0x0F0000] = 0xCD.toByte()

        val cartridge = Cartridge(image)

        assertEquals(0xAB, cartridge.read(0xC00000))
        assertEquals(0xCD, cartridge.read(0xCF0000))
    }

    @Test
    fun sramReadsAndWrites() {
        val cartridge = Cartridge(rom(0x80000, false, sramCode = 3))

        assertEquals(8192, cartridge.sram.size)

        cartridge.write(0x700000, 0x42)

        assertEquals(0x42, cartridge.read(0x700000))
        assertTrue(cartridge.saveVersion > 0)
    }

    @Test
    fun sramMirrorsWithinSize() {
        val cartridge = Cartridge(rom(0x80000, false, sramCode = 1))

        assertEquals(2048, cartridge.sram.size)

        cartridge.write(0x700000, 0x77)

        assertEquals(0x77, cartridge.read(0x700800))
    }

    @Test
    fun saveDataRoundTrips() {
        val cartridge = Cartridge(rom(0x80000, false, sramCode = 3))

        cartridge.write(0x700010, 0x99)

        val data = cartridge.saveData()

        val restored = Cartridge(rom(0x80000, false, sramCode = 3))
        restored.loadSaveData(data)

        assertEquals(0x99, restored.read(0x700010))
    }

    @Test
    fun detectsDsp1() {
        val cartridge = Cartridge(rom(0x80000, false, romType = 0x03))

        assertTrue(cartridge.supported)
        assertEquals(ChipId.DSP1, cartridge.chip)
    }

    @Test
    fun mirrorHandlesNonPowerOfTwo() {
        assertEquals(0, Cartridge.mirror(0, 0x180000))
        assertEquals(0x17FFFF, Cartridge.mirror(0x17FFFF, 0x180000))
        assertEquals(0x100000, Cartridge.mirror(0x180000, 0x180000))
    }
}
