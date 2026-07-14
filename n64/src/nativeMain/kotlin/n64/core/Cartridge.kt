package n64.core

class Cartridge(private val n64: N64) {
    val sram = ByteArray(0x8000)
    val flash = ByteArray(0x20000)

    var saveDirty = 0
        private set

    private var latch = 0

    private var flashMode = FLASH_IDLE
    private var flashOffset = 0
    private var flashStatus = 0L
    private val flashBuffer = ByteArray(128)

    fun readRom(addr: Int): Int {
        val at = addr and 0x0FFFFFFF
        if (n64.pi.regs[PI_STATUS] and PI_STATUS_IO_BUSY != 0) return latch
        n64.cpu.addCycles(n64.pi.cycles(4))
        return n64.rom.word(at)
    }

    fun writeRom(addr: Int, value: Int, mask: Int) {
        latch = value and mask
        n64.pi.regs[PI_STATUS] = n64.pi.regs[PI_STATUS] or PI_STATUS_IO_BUSY
        n64.createEvent(EVENT_PI, n64.pi.cycles(4))
    }

    fun readSave(addr: Int): Int {
        if (n64.rom.save == SaveKind.FLASH) {
            return if (addr and 0x1FFFF < 4) (flashStatus ushr 32).toInt() else flashStatus.toInt()
        }

        val at = addr and 0x7FFF
        return ((sram[at].toInt() and 0xFF) shl 24) or
            ((sram[at + 1].toInt() and 0xFF) shl 16) or
            ((sram[at + 2].toInt() and 0xFF) shl 8) or
            (sram[at + 3].toInt() and 0xFF)
    }

    fun writeSave(addr: Int, value: Int, mask: Int) {
        if (n64.rom.save == SaveKind.FLASH) {
            flashCommand(value and mask)
            return
        }

        val at = addr and 0x7FFF
        val current = readSave(addr)
        val updated = (current and mask.inv()) or (value and mask)
        sram[at] = (updated ushr 24).toByte()
        sram[at + 1] = (updated ushr 16).toByte()
        sram[at + 2] = (updated ushr 8).toByte()
        sram[at + 3] = updated.toByte()
        saveDirty++
    }

    fun dmaWrite(cartAddr: Int, dramAddr: Int, length: Int) {
        if (cartAddr in 0x08000000..0x0FFFFFFF) {
            saveDmaWrite(cartAddr, dramAddr, length)
            return
        }

        val base = cartAddr and 0x0FFFFFFF
        var i = 0
        while (i < length) {
            n64.ramWrite8(dramAddr + i, romByte(base + i))
            i++
        }
    }

    fun dmaRead(cartAddr: Int, dramAddr: Int, length: Int) {
        if (cartAddr in 0x08000000..0x0FFFFFFF) {
            saveDmaRead(cartAddr, dramAddr, length)
        }
    }

    private fun saveDmaWrite(cartAddr: Int, dramAddr: Int, length: Int) {
        if (n64.rom.save == SaveKind.FLASH) {
            if (flashMode == FLASH_READ) {
                var i = 0
                while (i < length) {
                    val at = (flashOffset + i) and 0x1FFFF
                    n64.ramWrite8(dramAddr + i, flash[at].toInt() and 0xFF)
                    i++
                }
            } else if (flashMode == FLASH_STATUS) {
                var i = 0
                while (i < length && i < 8) {
                    n64.ramWrite8(dramAddr + i, ((flashStatus ushr (56 - i * 8)) and 0xFF).toInt())
                    i++
                }
            }
            return
        }

        val base = cartAddr and 0x7FFF
        var i = 0
        while (i < length) {
            val at = (base + i) and 0x7FFF
            n64.ramWrite8(dramAddr + i, sram[at].toInt() and 0xFF)
            i++
        }
    }

    private fun saveDmaRead(cartAddr: Int, dramAddr: Int, length: Int) {
        if (n64.rom.save == SaveKind.FLASH) {
            var i = 0
            while (i < length && i < flashBuffer.size) {
                flashBuffer[i] = n64.ramRead8(dramAddr + i).toByte()
                i++
            }
            saveDirty++
            return
        }

        val base = cartAddr and 0x7FFF
        var i = 0
        while (i < length) {
            val at = (base + i) and 0x7FFF
            sram[at] = n64.ramRead8(dramAddr + i).toByte()
            i++
        }
        saveDirty++
    }

    private fun romByte(at: Int): Int {
        val word = n64.rom.word(at and 3.inv())
        val shift = 24 - ((at and 3) shl 3)
        return (word ushr shift) and 0xFF
    }

    private fun flashCommand(value: Int) {
        when (value ushr 24) {
            0xD2 -> if (flashMode == FLASH_WRITE) {
                val base = flashOffset and 0x1FFFF
                for (i in 0 until 128) {
                    val at = (base + i) and 0x1FFFF
                    flash[at] = flashBuffer[i]
                }
                saveDirty++
            }

            0xE1 -> {
                flashMode = FLASH_STATUS
                flashStatus = 0x1111800100C2001EuL.toLong()
            }

            0xF0 -> {
                flashMode = FLASH_READ
                flashStatus = 0x11118004F0000000uL.toLong()
            }

            0xB4 -> flashMode = FLASH_WRITE

            0xA5 -> {
                flashOffset = (value and 0xFFFF) * 128
                flashStatus = 0x1111800400C2001EuL.toLong()
            }

            0x78 -> {
                flashMode = FLASH_ERASE
                flashStatus = 0x1111800800C2001EuL.toLong()
            }

            0xD4 -> {}
        }
    }

    fun saveData(): ByteArray? = when (n64.rom.save) {
        SaveKind.EEPROM_4K -> n64.pif.eeprom.copyOf(512)
        SaveKind.EEPROM_16K -> n64.pif.eeprom.copyOf(2048)
        SaveKind.SRAM -> sram.copyOf()
        SaveKind.FLASH -> flash.copyOf()
        SaveKind.NONE -> null
    }

    fun loadSaveData(data: ByteArray) {
        when (n64.rom.save) {
            SaveKind.EEPROM_4K, SaveKind.EEPROM_16K ->
                data.copyInto(n64.pif.eeprom, 0, 0, minOf(data.size, n64.pif.eeprom.size))

            SaveKind.SRAM -> data.copyInto(sram, 0, 0, minOf(data.size, sram.size))
            SaveKind.FLASH -> data.copyInto(flash, 0, 0, minOf(data.size, flash.size))
            SaveKind.NONE -> {}
        }
    }

    fun saveVersion(): Int = saveDirty + n64.pif.eepromDirty

    companion object {
        const val FLASH_IDLE = 0
        const val FLASH_STATUS = 1
        const val FLASH_READ = 2
        const val FLASH_WRITE = 3
        const val FLASH_ERASE = 4
    }
}
