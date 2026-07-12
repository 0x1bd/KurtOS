package gameboy.core

class APU {
    private val registers = IntArray(0x30)

    fun read(address: Int): Int {
        val index = address - 0xFF10
        if (index < 0 || index >= registers.size) return 0xFF
        return registers[index] or readMask(address)
    }

    fun write(address: Int, value: Int) {
        val index = address - 0xFF10
        if (index < 0 || index >= registers.size) return

        if (address == 0xFF26) {
            registers[index] = (registers[index] and 0x0F) or (value and 0x80)
            return
        }

        registers[index] = value and 0xFF

        if (address == 0xFF14 || address == 0xFF19 || address == 0xFF1E || address == 0xFF23) {
            if (value and 0x80 != 0) trigger(address)
        }
    }

    private fun trigger(address: Int) {
        val channel = when (address) {
            0xFF14 -> 0x01
            0xFF19 -> 0x02
            0xFF1E -> 0x04
            0xFF23 -> 0x08
            else -> 0
        }
        registers[0xFF26 - 0xFF10] = registers[0xFF26 - 0xFF10] or channel
    }

    private fun readMask(address: Int): Int = when (address) {
        0xFF10 -> 0x80
        0xFF11, 0xFF16 -> 0x3F
        0xFF13, 0xFF18, 0xFF1D, 0xFF1F -> 0xFF
        0xFF14, 0xFF19, 0xFF1E, 0xFF23 -> 0xBF
        0xFF15, 0xFF1A -> 0x7F
        0xFF1C -> 0x9F
        0xFF20 -> 0xC0
        0xFF26 -> 0x70
        else -> 0x00
    }
}
