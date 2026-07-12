package gameboy.core

class Bus(
    private val cartridge: Cartridge,
    private val ppu: PPU,
    private val timer: Timer,
    private val joypad: Joypad,
    private val apu: APU,
    private val interrupts: Interrupts,
) {
    private val wram = ByteArray(0x2000)
    private val hram = ByteArray(0x7F)

    private var serialData = 0
    private var serialControl = 0

    fun read(address: Int): Int {
        val a = address and 0xFFFF

        return when (a shr 12) {
            0, 1, 2, 3, 4, 5, 6, 7 -> cartridge.readRom(a)
            8, 9 -> ppu.vram[a - 0x8000].toInt() and 0xFF
            10, 11 -> cartridge.readRam(a)
            12, 13 -> wram[a - 0xC000].toInt() and 0xFF
            14 -> wram[a - 0xE000].toInt() and 0xFF
            else -> readHigh(a)
        }
    }

    private fun readHigh(a: Int): Int = when {
        a < 0xFE00 -> wram[a - 0xE000].toInt() and 0xFF
        a < 0xFEA0 -> ppu.oam[a - 0xFE00].toInt() and 0xFF
        a < 0xFF00 -> 0xFF
        a == 0xFF00 -> joypad.read()
        a == 0xFF01 -> serialData
        a == 0xFF02 -> serialControl or 0x7E
        a in 0xFF04..0xFF07 -> timer.read(a)
        a == 0xFF0F -> interrupts.flags or 0xE0
        a in 0xFF10..0xFF3F -> apu.read(a)
        a in 0xFF40..0xFF4B -> ppu.read(a)
        a < 0xFF80 -> 0xFF
        a < 0xFFFF -> hram[a - 0xFF80].toInt() and 0xFF
        else -> interrupts.enable
    }

    fun write(address: Int, value: Int) {
        val a = address and 0xFFFF
        val v = value and 0xFF

        when (a shr 12) {
            0, 1, 2, 3, 4, 5, 6, 7 -> cartridge.writeControl(a, v)
            8, 9 -> ppu.vram[a - 0x8000] = v.toByte()
            10, 11 -> cartridge.writeRam(a, v)
            12, 13 -> wram[a - 0xC000] = v.toByte()
            14 -> wram[a - 0xE000] = v.toByte()
            else -> writeHigh(a, v)
        }
    }

    private fun writeHigh(a: Int, v: Int) {
        when {
            a < 0xFE00 -> wram[a - 0xE000] = v.toByte()
            a < 0xFEA0 -> ppu.oam[a - 0xFE00] = v.toByte()
            a < 0xFF00 -> return
            a == 0xFF00 -> joypad.write(v)
            a == 0xFF01 -> serialData = v
            a == 0xFF02 -> serialControl = v
            a in 0xFF04..0xFF07 -> timer.write(a, v)
            a == 0xFF0F -> interrupts.flags = v and 0x1F
            a in 0xFF10..0xFF3F -> apu.write(a, v)
            a == 0xFF46 -> transferOam(v)
            a in 0xFF40..0xFF4B -> ppu.write(a, v)
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
}
