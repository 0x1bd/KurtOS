package gba.core

import kapi.state.StateReader
import kapi.state.StateWriter

class Cartridge(val rom: ByteArray, clock: RtcClock? = null) {
    val sram = ByteArray(0x20000)

    val gpio = Gpio(clock)

    val title: String = buildString {
        for (i in 0xA0 until 0xAC) {
            val c = if (i < rom.size) rom[i].toInt() and 0xFF else 0
            if (c in 0x20..0x7E) append(c.toChar())
        }
    }.trim()

    val supported: Boolean = rom.size >= 0xC0

    init {
        gpio.present = clock != null && Gpio.detect(rom)
    }

    var saveVersion = 0
        private set

    fun save(writer: StateWriter) {
        writer.bytes(sram)
        writer.int(flashState)
        writer.int(flashMode)
        writer.int(flashBank)
        gpio.save(writer)
    }

    fun load(reader: StateReader) {
        reader.bytes(sram)
        flashState = reader.int()
        flashMode = reader.int()
        flashBank = reader.int()
        gpio.load(reader)
        saveVersion++
    }

    fun saveData(): ByteArray = sram.copyOf()

    fun loadSaveData(data: ByteArray) {
        val length = if (data.size < sram.size) data.size else sram.size
        data.copyInto(sram, 0, 0, length)
    }

    private var flashState = 0
    private var flashMode = FLASH_READ
    private var flashBank = 0

    fun readBackup(address: Int): Int {
        val offset = address and 0xFFFF

        if (flashMode == FLASH_ID) {
            if (offset == 0) return 0x62
            if (offset == 1) return 0x13
        }

        return sram[(flashBank shl 16 or offset) and sram.size - 1].toInt() and 0xFF
    }

    fun writeBackup(address: Int, value: Int) {
        val offset = address and 0xFFFF

        saveVersion++

        when (flashState) {
            0 -> {
                if (offset == 0x5555 && value == 0xAA) {
                    flashState = 1
                    return
                }
                if (flashMode == FLASH_WRITE || flashMode == FLASH_READ) {
                    sram[(flashBank shl 16 or offset) and sram.size - 1] = value.toByte()
                    flashMode = FLASH_READ
                }
                if (flashMode == FLASH_BANK && offset == 0) {
                    flashBank = value and 1
                    flashMode = FLASH_READ
                }
            }

            1 -> flashState = if (offset == 0x2AAA && value == 0x55) 2 else 0

            2 -> {
                flashState = 0
                if (offset != 0x5555) {
                    if (value == 0x30) {
                        val base = (flashBank shl 16) or (offset and 0xF000)
                        for (i in 0 until 0x1000) sram[(base + i) and sram.size - 1] = 0xFF.toByte()
                    }
                    return
                }

                when (value) {
                    0x90 -> flashMode = FLASH_ID
                    0xF0 -> flashMode = FLASH_READ
                    0xA0 -> flashMode = FLASH_WRITE
                    0xB0 -> flashMode = FLASH_BANK
                    0x10 -> sram.fill(0xFF.toByte())
                    0x80 -> Unit
                }
            }
        }
    }

    private companion object {
        const val FLASH_READ = 0
        const val FLASH_ID = 1
        const val FLASH_WRITE = 2
        const val FLASH_BANK = 3
    }
}
