package gameboy.core

class Bus(
    private val cartridge: Cartridge,
    private val ppu: PPU,
    private val timer: Timer,
    private val joypad: Joypad,
    private val apu: APU,
    private val interrupts: Interrupts,
    private val color: Boolean,
) {
    private val wram = ByteArray(WRAM_BANKS * WRAM_BANK_SIZE)
    private val hram = ByteArray(0x7F)

    private val hdma = Hdma(this)

    private var wramBank = 1
    private var serialData = 0
    private var serialControl = 0

    var doubleSpeed = false
        private set

    private var speedSwitchArmed = false

    fun stop() {
        if (!color || !speedSwitchArmed) return

        doubleSpeed = !doubleSpeed
        speedSwitchArmed = false
    }

    fun stepHBlank() {
        if (color) hdma.stepHBlank()
    }

    private fun wramIndex(address: Int): Int =
        if (address < 0xD000) address - 0xC000 else wramBank * WRAM_BANK_SIZE + (address - 0xD000)

    fun read(address: Int): Int {
        val a = address and 0xFFFF

        return when (a shr 12) {
            0, 1, 2, 3, 4, 5, 6, 7 -> cartridge.readRom(a)
            8, 9 -> ppu.readVram(a)
            10, 11 -> cartridge.readRam(a)
            12, 13 -> wram[wramIndex(a)].toInt() and 0xFF
            14 -> wram[wramIndex(a - 0x2000)].toInt() and 0xFF
            else -> readHigh(a)
        }
    }

    private fun readHigh(a: Int): Int = when {
        a < 0xFE00 -> wram[wramIndex(a - 0x2000)].toInt() and 0xFF
        a < 0xFEA0 -> ppu.oam[a - 0xFE00].toInt() and 0xFF
        a < 0xFF00 -> 0xFF
        a == 0xFF00 -> joypad.read()
        a == 0xFF01 -> serialData
        a == 0xFF02 -> serialControl or 0x7E
        a in 0xFF04..0xFF07 -> timer.read(a)
        a == 0xFF0F -> interrupts.flags or 0xE0
        a in 0xFF10..0xFF3F -> apu.read(a)
        a == 0xFF4D -> readSpeed()
        a in 0xFF51..0xFF54 -> 0xFF
        a == 0xFF55 -> if (color) hdma.readControl() else 0xFF
        a == 0xFF70 -> if (color) wramBank or 0xF8 else 0xFF
        a in 0xFF40..0xFF6B -> ppu.read(a)
        a < 0xFF80 -> 0xFF
        a < 0xFFFF -> hram[a - 0xFF80].toInt() and 0xFF
        else -> interrupts.enable
    }

    private fun readSpeed(): Int {
        if (!color) return 0xFF

        val current = if (doubleSpeed) 0x80 else 0x00
        val armed = if (speedSwitchArmed) 0x01 else 0x00
        return current or armed or 0x7E
    }

    fun write(address: Int, value: Int) {
        val a = address and 0xFFFF
        val v = value and 0xFF

        when (a shr 12) {
            0, 1, 2, 3, 4, 5, 6, 7 -> cartridge.writeControl(a, v)
            8, 9 -> ppu.writeVram(a, v)
            10, 11 -> cartridge.writeRam(a, v)
            12, 13 -> wram[wramIndex(a)] = v.toByte()
            14 -> wram[wramIndex(a - 0x2000)] = v.toByte()
            else -> writeHigh(a, v)
        }
    }

    private fun writeHigh(a: Int, v: Int) {
        when {
            a < 0xFE00 -> wram[wramIndex(a - 0x2000)] = v.toByte()
            a < 0xFEA0 -> ppu.oam[a - 0xFE00] = v.toByte()
            a < 0xFF00 -> return
            a == 0xFF00 -> joypad.write(v)
            a == 0xFF01 -> serialData = v
            a == 0xFF02 -> serialControl = v
            a in 0xFF04..0xFF07 -> timer.write(a, v)
            a == 0xFF0F -> interrupts.flags = v and 0x1F
            a in 0xFF10..0xFF3F -> apu.write(a, v)
            a == 0xFF46 -> transferOam(v)
            a == 0xFF4D -> if (color) speedSwitchArmed = v and 0x01 != 0
            a == 0xFF51 -> if (color) hdma.writeSourceHigh(v)
            a == 0xFF52 -> if (color) hdma.writeSourceLow(v)
            a == 0xFF53 -> if (color) hdma.writeDestinationHigh(v)
            a == 0xFF54 -> if (color) hdma.writeDestinationLow(v)
            a == 0xFF55 -> if (color) hdma.writeControl(v)
            a == 0xFF70 -> if (color) wramBank = if (v and 0x07 == 0) 1 else v and 0x07
            a in 0xFF40..0xFF6B -> ppu.write(a, v)
            a < 0xFF80 -> return
            a < 0xFFFF -> hram[a - 0xFF80] = v.toByte()
            else -> interrupts.enable = v and 0x1F
        }
    }

    private fun transferOam(page: Int) {
        val source = page shl 8
        for (i in 0 until 0xA0) {
            ppu.oam[i] = read(source + i).toByte()
        }
    }

    private companion object {
        const val WRAM_BANKS = 8
        const val WRAM_BANK_SIZE = 0x1000
    }
}
