package gameboy.core

class Cartridge(private val rom: ByteArray) {
    val title: String = readTitle()

    private val cartridgeType: Int = byteAt(0x147)

    private val kind: Int = when (cartridgeType) {
        0x00, 0x08, 0x09 -> NONE
        0x01, 0x02, 0x03 -> MBC1
        0x05, 0x06 -> MBC2
        0x0F, 0x10, 0x11, 0x12, 0x13 -> MBC3
        0x19, 0x1A, 0x1B, 0x1C, 0x1D, 0x1E -> MBC5
        else -> NONE
    }

    val kindName: String = when (kind) {
        MBC1 -> "MBC1"
        MBC2 -> "MBC2"
        MBC3 -> "MBC3"
        MBC5 -> "MBC5"
        else -> "ROM"
    }

    private val romBanks: Int = romBankCount()
    private val ramBanks: Int = if (kind == MBC2) 0 else ramBankCount()

    private val ram = ByteArray(if (kind == MBC2) MBC2_RAM_BYTES else ramBanks * 0x2000)

    val battery: Boolean = when (cartridgeType) {
        0x03, 0x06, 0x09, 0x0F, 0x10, 0x13, 0x1B, 0x1E -> true
        else -> false
    }

    var saveVersion = 0
        private set

    val supported: Boolean = rom.size >= 0x8000

    val colorCapable: Boolean = byteAt(0x143) and 0x80 != 0

    private var romBank = 1
    private var ramBank = 0
    private var ramEnabled = false
    private var bankingMode = 0

    fun readRom(address: Int): Int {
        val offset = if (address < 0x4000) {
            if (kind == MBC1 && bankingMode == 1) {
                (highBank() * 0x4000) + address
            } else {
                address
            }
        } else {
            (currentRomBank() * 0x4000) + (address - 0x4000)
        }
        if (offset < 0 || offset >= rom.size) return 0xFF
        return rom[offset].toInt() and 0xFF
    }

    fun readRam(address: Int): Int {
        if (!ramEnabled || ram.isEmpty()) return 0xFF

        if (kind == MBC2) {
            val offset = (address - 0xA000) and MBC2_RAM_MASK
            return (ram[offset].toInt() and 0x0F) or 0xF0
        }

        val offset = currentRamBank() * 0x2000 + (address - 0xA000)
        if (offset < 0 || offset >= ram.size) return 0xFF
        return ram[offset].toInt() and 0xFF
    }

    fun writeRam(address: Int, value: Int) {
        if (!ramEnabled || ram.isEmpty()) return

        if (kind == MBC2) {
            val offset = (address - 0xA000) and MBC2_RAM_MASK
            ram[offset] = (value and 0x0F).toByte()
            saveVersion++
            return
        }

        val offset = currentRamBank() * 0x2000 + (address - 0xA000)
        if (offset < 0 || offset >= ram.size) return
        ram[offset] = value.toByte()
        saveVersion++
    }

    fun saveData(): ByteArray? {
        if (!battery || ram.isEmpty()) return null
        return ram.copyOf()
    }

    fun loadSaveData(data: ByteArray) {
        if (!battery || ram.isEmpty()) return

        val length = if (data.size < ram.size) data.size else ram.size
        data.copyInto(ram, 0, 0, length)
    }

    fun writeControl(address: Int, value: Int) {
        when (kind) {
            NONE -> return
            MBC1 -> writeMbc1(address, value)
            MBC2 -> writeMbc2(address, value)
            MBC3 -> writeMbc3(address, value)
            MBC5 -> writeMbc5(address, value)
        }
    }

    private fun writeMbc2(address: Int, value: Int) {
        if (address >= 0x4000) return

        if (address and 0x0100 == 0) {
            ramEnabled = (value and 0x0F) == 0x0A
            return
        }

        val bank = value and 0x0F
        romBank = if (bank == 0) 1 else bank
    }

    private fun writeMbc1(address: Int, value: Int) {
        when (address shr 13) {
            0 -> ramEnabled = (value and 0x0F) == 0x0A
            1 -> {
                val low = value and 0x1F
                romBank = (romBank and 0x60) or if (low == 0) 1 else low
            }
            2 -> romBank = (romBank and 0x1F) or ((value and 0x03) shl 5)
            3 -> bankingMode = value and 0x01
        }
    }

    private fun writeMbc3(address: Int, value: Int) {
        when (address shr 13) {
            0 -> ramEnabled = (value and 0x0F) == 0x0A
            1 -> {
                val bank = value and 0x7F
                romBank = if (bank == 0) 1 else bank
            }
            2 -> ramBank = value and 0x0F
        }
    }

    private fun writeMbc5(address: Int, value: Int) {
        when {
            address < 0x2000 -> ramEnabled = (value and 0x0F) == 0x0A
            address < 0x3000 -> romBank = (romBank and 0x100) or (value and 0xFF)
            address < 0x4000 -> romBank = (romBank and 0xFF) or ((value and 0x01) shl 8)
            address < 0x6000 -> ramBank = value and 0x0F
        }
    }

    private fun highBank(): Int = (romBank shr 5) and 0x03

    private fun currentRomBank(): Int {
        val bank = if (romBank == 0 && kind != MBC5) 1 else romBank
        return if (romBanks == 0) 0 else bank % romBanks
    }

    private fun currentRamBank(): Int {
        if (ramBanks == 0) return 0
        val bank = if (kind == MBC1 && bankingMode == 0) 0 else ramBank
        return bank % ramBanks
    }

    private fun byteAt(index: Int): Int {
        if (index < 0 || index >= rom.size) return 0
        return rom[index].toInt() and 0xFF
    }

    private fun romBankCount(): Int {
        val code = byteAt(0x148)
        val banks = if (code <= 8) 2 shl code else 2
        val actual = rom.size / 0x4000
        return if (actual in 1 until banks) actual else banks
    }

    private fun ramBankCount(): Int = when (byteAt(0x149)) {
        0x02 -> 1
        0x03 -> 4
        0x04 -> 16
        0x05 -> 8
        else -> 0
    }

    private fun readTitle(): String {
        val builder = StringBuilder()
        for (i in 0x134..0x142) {
            val c = byteAt(i)
            if (c == 0) break
            if (c in 32..126) builder.append(c.toChar())
        }
        return builder.toString().trim().ifEmpty { "UNTITLED" }
    }

    private companion object {
        const val NONE = 0
        const val MBC1 = 1
        const val MBC2 = 2
        const val MBC3 = 3
        const val MBC5 = 5

        const val MBC2_RAM_BYTES = 512
        const val MBC2_RAM_MASK = 0x1FF
    }
}
