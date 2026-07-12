package gameboy.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val BANK_SIZE = 0x4000

private fun rom(cartridgeType: Int, banks: Int, ramCode: Int = 0x00): ByteArray {
    val image = ByteArray(banks * BANK_SIZE)

    image[0x147] = cartridgeType.toByte()
    image[0x148] = when (banks) {
        2 -> 0x00
        4 -> 0x01
        8 -> 0x02
        16 -> 0x03
        32 -> 0x04
        else -> 0x05
    }.toByte()
    image[0x149] = ramCode.toByte()

    for (bank in 0 until banks) {
        image[bank * BANK_SIZE + 0x1000] = bank.toByte()
    }

    return image
}

private fun readBankMarker(cartridge: Cartridge): Int = cartridge.readRom(0x5000)

class CartridgeTest {
    @Test
    fun mbc2SwitchesRomBanks() {
        val cartridge = Cartridge(rom(0x05, banks = 4))

        assertEquals("MBC2", cartridge.kindName)
        assertEquals(1, readBankMarker(cartridge))

        cartridge.writeControl(0x2100, 0x02)
        assertEquals(2, readBankMarker(cartridge))

        cartridge.writeControl(0x2100, 0x03)
        assertEquals(3, readBankMarker(cartridge))

        cartridge.writeControl(0x2100, 0x00)
        assertEquals(1, readBankMarker(cartridge))
    }

    @Test
    fun mbc2IgnoresBankWritesWithoutAddressBitEight() {
        val cartridge = Cartridge(rom(0x06, banks = 4))

        cartridge.writeControl(0x2100, 0x02)
        assertEquals(2, readBankMarker(cartridge))

        cartridge.writeControl(0x2000, 0x03)
        assertEquals(2, readBankMarker(cartridge))
    }

    @Test
    fun mbc2RamHoldsFourBitValuesAndEchoes() {
        val cartridge = Cartridge(rom(0x06, banks = 4))

        cartridge.writeControl(0x0000, 0x0A)
        cartridge.writeRam(0xA000, 0xAB)

        assertEquals(0xFB, cartridge.readRam(0xA000))
        assertEquals(0xFB, cartridge.readRam(0xA200))

        cartridge.writeControl(0x0000, 0x00)
        assertEquals(0xFF, cartridge.readRam(0xA000))
    }

    @Test
    fun mbc1SwitchesRomBanks() {
        val cartridge = Cartridge(rom(0x01, banks = 8))

        cartridge.writeControl(0x2000, 0x05)
        assertEquals(5, readBankMarker(cartridge))

        cartridge.writeControl(0x2000, 0x00)
        assertEquals(1, readBankMarker(cartridge))
    }

    @Test
    fun mbc5SwitchesRomBanksIncludingZero() {
        val cartridge = Cartridge(rom(0x19, banks = 8))

        cartridge.writeControl(0x2000, 0x03)
        assertEquals(3, readBankMarker(cartridge))

        cartridge.writeControl(0x2000, 0x00)
        assertEquals(0, readBankMarker(cartridge))
    }

    @Test
    fun romOnlyCartridgeIsRecognised() {
        val cartridge = Cartridge(rom(0x00, banks = 2))

        assertEquals("ROM", cartridge.kindName)
        assertTrue(cartridge.supported)
    }
}
