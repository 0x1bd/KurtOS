package gameboy.core

class Hdma(private val bus: Bus) {
    private var source = 0
    private var destination = 0
    private var remaining = 0
    private var active = false

    fun writeSourceHigh(value: Int) {
        source = (source and 0x00FF) or ((value and 0xFF) shl 8)
    }

    fun writeSourceLow(value: Int) {
        source = (source and 0xFF00) or (value and 0xF0)
    }

    fun writeDestinationHigh(value: Int) {
        destination = (destination and 0x00FF) or ((value and 0x1F) shl 8)
    }

    fun writeDestinationLow(value: Int) {
        destination = (destination and 0xFF00) or (value and 0xF0)
    }

    fun readControl(): Int {
        if (!active) return 0xFF
        return (remaining - 1) and 0x7F
    }

    fun writeControl(value: Int) {
        val blocks = (value and 0x7F) + 1

        if (value and 0x80 == 0) {
            if (active) {
                active = false
                return
            }
            transfer(blocks)
            return
        }

        remaining = blocks
        active = true
    }

    fun stepHBlank() {
        if (!active) return

        transfer(1)
        remaining--

        if (remaining <= 0) active = false
    }

    private fun transfer(blocks: Int) {
        for (block in 0 until blocks) {
            for (i in 0 until BLOCK_SIZE) {
                val value = bus.read((source + i) and 0xFFFF)
                bus.write(0x8000 or ((destination + i) and 0x1FFF), value)
            }
            source = (source + BLOCK_SIZE) and 0xFFFF
            destination = (destination + BLOCK_SIZE) and 0x1FFF
        }
    }

    private companion object {
        const val BLOCK_SIZE = 16
    }
}
