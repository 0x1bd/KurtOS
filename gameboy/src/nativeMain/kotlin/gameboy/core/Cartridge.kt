package gameboy.core

class Cartridge(private val rom: ByteArray) {
    val title: String = readTitle()

    private val cartridgeType: Int = byteAt(0x147)
    private val romBanks: Int = romBankCount()
    private val ramBanks: Int = ramBankCount()

    private val ram = ByteArray(ramBanks * 0x2000)

    private val kind: Int = when (cartridgeType) {
        0x00, 0x08, 0x09 -> NONE
        0x01, 0x02, 0x03 -> MBC1
        0x0F, 0x10, 0x11, 0x12, 0x13 -> MBC3
        0x19, 0x1A, 0x1B, 0x1C, 0x1D, 0x1E -> MBC5
        else -> NONE
    }

    val kindName: String = when (kind) {
        MBC1 -> "MBC1"
        MBC3 -> "MBC3"
        MBC5 -> "MBC5"
        else -> "ROM"
    }

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
        val offset = currentRamBank() * 0x2000 + (address - 0xA000)
        if (offset < 0 || offset >= ram.size) return 0xFF
        return ram[offset].toInt() and 0xFF
    }

    fun writeRam(address: Int, value: Int) {
        if (!ramEnabled || ram.isEmpty()) return
        val offset = currentRamBank() * 0x2000 + (address - 0xA000)
        if (offset < 0 || offset >= ram.size) return
        ram[offset] = value.toByte()
    }

    fun writeControl(address: Int, value: Int) {
        when (kind) {
            NONE -> return
            MBC1 -> writeMbc1(address, value)
            MBC3 -> writeMbc3(address, value)
            MBC5 -> writeMbc5(address, value)
        }
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
        else -> if (kind() == MBC2) 1 else 0
    }

    private fun kind(): Int = when (byteAt(0x147)) {
        0x05, 0x06 -> MBC2
        else -> NONE
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
    }
}
