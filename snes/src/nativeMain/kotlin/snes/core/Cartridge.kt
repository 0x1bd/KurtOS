package snes.core

import kapi.state.StateReader
import kapi.state.StateWriter

class Cartridge(image: ByteArray) {
    val rom: ByteArray
    val supported: Boolean
    val hiRom: Boolean
    val fastRom: Boolean
    val title: String
    val chip: Int
    val sram: ByteArray

    var coProcessor: CoProcessor? = null
        private set

    var saveVersion = 0
        private set

    private val kind = ByteArray(PAGES)
    private val base = IntArray(PAGES)
    private val sramMask: Int

    init {
        rom = strip(image)

        val loScore = if (rom.size >= LO_HEADER + HEADER_SIZE) score(LO_HEADER, false) else -1
        val hiScore = if (rom.size >= HI_HEADER + HEADER_SIZE) score(HI_HEADER, true) else -1

        supported = rom.size >= MIN_SIZE && (loScore >= 0 || hiScore >= 0) && maxOf(loScore, hiScore) >= MIN_SCORE
        hiRom = hiScore > loScore

        val header = if (hiRom) HI_HEADER else LO_HEADER

        title = if (supported) readTitle(header) else ""

        val mapMode = if (supported) byte(header + MAP_MODE) else 0
        fastRom = mapMode and 0x10 != 0

        val romType = if (supported) byte(header + ROM_TYPE) else 0
        chip = if (supported && romType and 0x0F in 3..5) ChipId.DSP1 else ChipId.NONE

        val sramCode = if (supported) byte(header + SRAM_SIZE) else 0
        val sramBytes = if (sramCode == 0 || sramCode > MAX_SRAM_CODE) 0 else 1024 shl sramCode

        sram = ByteArray(sramBytes)
        sramMask = if (sramBytes == 0) 0 else sramBytes - 1

        if (chip == ChipId.DSP1) coProcessor = Dsp1(hiRom)

        if (supported) map()
    }

    fun reset() {
        coProcessor?.reset()
    }

    fun read(addr: Int): Int {
        val page = addr ushr PAGE_SHIFT

        return when (kind[page].toInt()) {
            ROM -> rom[base[page] + (addr and PAGE_MASK)].toInt() and 0xFF
            SRAM -> sram[(base[page] + (addr and PAGE_MASK)) and sramMask].toInt() and 0xFF
            CHIP -> coProcessor?.read(addr) ?: -1
            else -> -1
        }
    }

    fun write(addr: Int, value: Int) {
        val page = addr ushr PAGE_SHIFT

        when (kind[page].toInt()) {
            SRAM -> {
                if (sram.isEmpty()) return
                sram[(base[page] + (addr and PAGE_MASK)) and sramMask] = value.toByte()
                saveVersion++
            }

            CHIP -> coProcessor?.write(addr, value)
        }
    }

    fun saveData(): ByteArray = sram.copyOf()

    fun loadSaveData(data: ByteArray) {
        if (data.size != sram.size) return
        data.copyInto(sram)
    }

    fun save(writer: StateWriter) {
        writer.int(chip)
        writer.bytes(sram)
        coProcessor?.save(writer)
    }

    fun load(reader: StateReader) {
        if (reader.int() != chip) {
            reader.bytes(ByteArray(0))
            return
        }

        reader.bytes(sram)
        coProcessor?.load(reader)
        saveVersion++
    }

    private fun map() {
        if (hiRom) mapHiRom() else mapLoRom()
        mapSram()
        mapChip()
    }

    private fun mapLoRom() {
        for (bank in 0x00..0xFF) {
            if (bank == 0x7E || bank == 0x7F) continue

            for (page in 4 until PAGES_PER_BANK) {
                val offset = page shl PAGE_SHIFT
                val linear = (bank and 0x7F) * 0x8000 + (offset and 0x7FFF)
                assign(bank, page, ROM, mirror(linear, rom.size))
            }
        }
    }

    private fun mapHiRom() {
        for (bank in 0x00..0xFF) {
            if (bank == 0x7E || bank == 0x7F) continue

            val full = (bank in 0x40..0x7D) || bank >= 0xC0
            val first = if (full) 0 else 4

            for (page in first until PAGES_PER_BANK) {
                val offset = page shl PAGE_SHIFT
                val linear = ((bank and 0x3F) shl 16) or offset
                assign(bank, page, ROM, mirror(linear, rom.size))
            }
        }
    }

    private fun mapSram() {
        if (sram.isEmpty()) return

        if (hiRom) {
            for (bank in 0x20..0x3F) {
                assign(bank, 3, SRAM, ((bank - 0x20) * 0x2000))
                assign(bank + 0x80, 3, SRAM, ((bank - 0x20) * 0x2000))
            }
            return
        }

        for (bank in 0x70..0x7D) {
            for (page in 0 until 4) {
                assign(bank, page, SRAM, (bank - 0x70) * 0x8000 + (page shl PAGE_SHIFT))
            }
        }

        for (bank in 0xF0..0xFF) {
            for (page in 0 until 4) {
                assign(bank, page, SRAM, (bank - 0xF0) * 0x8000 + (page shl PAGE_SHIFT))
            }
        }
    }

    private fun mapChip() {
        if (coProcessor == null) return

        if (hiRom) {
            for (bank in 0x00..0x1F) {
                assign(bank, 3, CHIP, 0)
                assign(bank + 0x80, 3, CHIP, 0)
            }
            return
        }

        for (bank in 0x30..0x3F) {
            for (page in 4 until PAGES_PER_BANK) {
                assign(bank, page, CHIP, 0)
            }
            for (page in 4 until PAGES_PER_BANK) {
                assign(bank + 0x80, page, CHIP, 0)
            }
        }
    }

    private fun assign(bank: Int, page: Int, type: Int, offset: Int) {
        val index = (bank shl BANK_SHIFT) or page
        kind[index] = type.toByte()
        base[index] = offset
    }

    private fun score(header: Int, hi: Boolean): Int {
        var value = 0

        val mapMode = byte(header + MAP_MODE) and 0xEF
        val expected = if (hi) 0x21 else 0x20

        if (mapMode == expected) value += 4

        val checksum = word(header + CHECKSUM)
        val complement = word(header + COMPLEMENT)

        if (checksum + complement == 0xFFFF && checksum != 0) value += 8

        val reset = word(header + RESET_VECTOR)
        if (reset < 0x8000) return -1
        value += 4

        val opcode = byte(mapReset(hi, reset))
        if (opcode in RESET_OPCODES) value += 2

        var printable = 0
        for (i in 0 until TITLE_SIZE) {
            val character = byte(header + i)
            if (character in 0x20..0x7E) printable++
        }
        if (printable == TITLE_SIZE) value += 2

        val romCode = byte(header + ROM_SIZE)
        if (romCode in 8..13) value += 1

        return value
    }

    private fun mapReset(hi: Boolean, reset: Int): Int =
        mirror(if (hi) reset else reset and 0x7FFF, rom.size)

    private fun readTitle(header: Int): String {
        val builder = StringBuilder()

        for (i in 0 until TITLE_SIZE) {
            val character = byte(header + i)
            if (character in 0x20..0x7E) builder.append(character.toChar())
        }

        return builder.toString().trim()
    }

    private fun byte(offset: Int): Int {
        if (offset < 0 || offset >= rom.size) return 0
        return rom[offset].toInt() and 0xFF
    }

    private fun word(offset: Int): Int = byte(offset) or (byte(offset + 1) shl 8)

    companion object {
        const val PAGE_SHIFT = 13
        const val PAGE_MASK = (1 shl PAGE_SHIFT) - 1
        const val PAGES_PER_BANK = 0x10000 shr PAGE_SHIFT
        const val BANK_SHIFT = 3
        const val PAGES = 1 shl 11

        const val NONE = 0
        const val ROM = 1
        const val SRAM = 2
        const val CHIP = 3

        private const val LO_HEADER = 0x7FC0
        private const val HI_HEADER = 0xFFC0
        private const val HEADER_SIZE = 0x40
        private const val TITLE_SIZE = 21
        private const val MAP_MODE = 0x15
        private const val ROM_TYPE = 0x16
        private const val ROM_SIZE = 0x17
        private const val SRAM_SIZE = 0x18
        private const val COMPLEMENT = 0x1C
        private const val CHECKSUM = 0x1E
        private const val RESET_VECTOR = 0x3C

        private const val MIN_SIZE = 0x8000
        private const val MIN_SCORE = 8
        private const val MAX_SRAM_CODE = 9
        private const val COPIER_HEADER = 512

        private val RESET_OPCODES = intArrayOf(0x78, 0x18, 0x38, 0x9C, 0x4C, 0x5C, 0x20, 0x22, 0xA2, 0xA9, 0xC2, 0xE2, 0xFB)

        fun strip(image: ByteArray): ByteArray {
            if (image.size % 0x8000 == COPIER_HEADER) return image.copyOfRange(COPIER_HEADER, image.size)
            return image
        }

        fun mirror(addr: Int, size: Int): Int {
            if (size == 0) return 0
            if (addr < size) return addr

            var base = 0
            var mask = 1 shl 23
            var value = addr

            while (value >= size) {
                while (mask != 0 && (value and mask) == 0) mask = mask shr 1
                if (mask == 0) return base

                value -= mask

                if (size > mask) {
                    val remainder = size - mask
                    value %= remainder
                    base += mask
                }

                mask = mask shr 1
            }

            return base + value
        }
    }
}
