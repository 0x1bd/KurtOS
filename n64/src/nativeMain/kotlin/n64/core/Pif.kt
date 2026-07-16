package n64.core

class Pif(private val n64: N64) {
    val ram = ByteArray(64)

    private val buttons = IntArray(CHANNELS)
    private val stickX = IntArray(CHANNELS)
    private val stickY = IntArray(CHANNELS)
    private var present = 1

    val eeprom = ByteArray(2048)
    var eepromDirty = 0
        private set

    fun reset() {
        ram.fill(0)
        buttons.fill(0)
        stickX.fill(0)
        stickY.fill(0)
        present = 1

        val romType = 0
        val s7 = 0
        val resetType = 0
        val word = (romType shl 19) or (s7 shl 18) or (resetType shl 17) or (n64.rom.cicSeed shl 8) or 0x3F
        writeRamWord(0x24, word)
    }

    fun setButtons(channel: Int, mask: Int) {
        if (channel < 0 || channel >= CHANNELS) return
        buttons[channel] = mask
    }

    fun setStick(channel: Int, x: Int, y: Int) {
        if (channel < 0 || channel >= CHANNELS) return
        stickX[channel] = x.coerceIn(-80, 80)
        stickY[channel] = y.coerceIn(-80, 80)
    }

    fun setInput(channel: Int, mask: Int, x: Int, y: Int, connected: Boolean) {
        setButtons(channel, mask)
        setStick(channel, x, y)
        if (connected) present = present or (1 shl channel)
    }

    fun readRamWord(at: Int): Int =
        ((ram[at].toInt() and 0xFF) shl 24) or
            ((ram[at + 1].toInt() and 0xFF) shl 16) or
            ((ram[at + 2].toInt() and 0xFF) shl 8) or
            (ram[at + 3].toInt() and 0xFF)

    fun writeRamWord(at: Int, value: Int) {
        ram[at] = (value ushr 24).toByte()
        ram[at + 1] = (value ushr 16).toByte()
        ram[at + 2] = (value ushr 8).toByte()
        ram[at + 3] = value.toByte()
    }

    fun readMem(addr: Int): Int {
        n64.cpu.addCycles(3000)
        val offset = addr and 0xFFFF
        if (offset < 0x7C0 || offset >= 0x800) return 0
        return readRamWord(offset - 0x7C0)
    }

    fun writeMem(addr: Int, value: Int, mask: Int) {
        val offset = addr and 0xFFFF
        if (offset < 0x7C0 || offset >= 0x800) return
        val at = offset - 0x7C0
        val current = readRamWord(at)
        writeRamWord(at, (current and mask.inv()) or (value and mask))
        n64.si.startWrite()
    }

    fun processRam() {
        var clear = 0
        val command = ram[0x3F].toInt() and 0xFF

        if (command and 0x01 != 0) clear = clear or 0x01
        if (command and 0x02 != 0) clear = clear or 0x02
        if (command and 0x08 != 0) clear = clear or 0x08
        if (command and 0x20 != 0) ram[0x3F] = 0x80.toByte()

        ram[0x3F] = ((ram[0x3F].toInt() and 0xFF) and clear.inv()).toByte()
    }

    val debugCommands = IntArray(8)
    var debugReads = 0
        private set

    fun updateRam(): Long {
        debugReads++
        var active = 0
        var channel = 0
        var i = 0

        while (i < 64 && channel < 5) {
            val head = ram[i].toInt() and 0xFF
            when (head) {
                0x00 -> {
                    channel++
                    i++
                }

                0xFF -> i++

                0xFE -> i = 64

                0xFD -> {
                    channel++
                    i++
                }

                else -> {
                    if (i + 1 < 64 && (ram[i + 1].toInt() and 0xFF) == 0xFE) {
                        i++
                        continue
                    }
                    if (i + 2 >= 64) {
                        i = 64
                        continue
                    }

                    val tx = ram[i].toInt() and 0x3F
                    val rx = ram[i + 1].toInt() and 0x3F
                    ram[i] = (ram[i].toInt() and 0x3F).toByte()
                    ram[i + 1] = (ram[i + 1].toInt() and 0x3F).toByte()

                    if (process(channel, i + 2, tx, i + 2 + tx, rx)) active++

                    i += 2 + tx + rx
                    channel++
                }
            }
        }

        return 24000L + active * 30000L
    }

    private fun process(channel: Int, txAt: Int, tx: Int, rxAt: Int, rx: Int): Boolean {
        if (tx == 0) return false

        val command = ram[txAt].toInt() and 0xFF
        if (command < 8) debugCommands[command]++

        if (channel in 0 until CHANNELS) {
            if (present and (1 shl channel) == 0) {
                ram[txAt - 1] = ((ram[txAt - 1].toInt() and 0x3F) or 0x80).toByte()
                return false
            }
            return controller(channel, command, rxAt, rx)
        }

        if (channel == 4) return eeprom(command, txAt, tx, rxAt, rx)

        return false
    }

    private companion object {
        const val CHANNELS = 4
    }

    private fun controller(channel: Int, command: Int, rxAt: Int, rx: Int): Boolean {
        when (command) {
            0x00, 0xFF -> {
                if (rx < 3) return false
                ram[rxAt] = 0x05
                ram[rxAt + 1] = 0x00
                ram[rxAt + 2] = 0x02
                return true
            }

            0x01 -> {
                if (rx < 4) return false
                ram[rxAt] = (buttons[channel] ushr 8).toByte()
                ram[rxAt + 1] = buttons[channel].toByte()
                ram[rxAt + 2] = stickX[channel].toByte()
                ram[rxAt + 3] = stickY[channel].toByte()
                return true
            }

            0x02, 0x03 -> {
                for (i in 0 until rx) if (rxAt + i < 64) ram[rxAt + i] = 0
                return true
            }
        }
        return false
    }

    private fun eeprom(command: Int, txAt: Int, tx: Int, rxAt: Int, rx: Int): Boolean {
        if (n64.rom.save != SaveKind.EEPROM_4K && n64.rom.save != SaveKind.EEPROM_16K) return false

        when (command) {
            0x00, 0xFF -> {
                if (rx < 3) return false
                ram[rxAt] = 0x00
                ram[rxAt + 1] = if (n64.rom.save == SaveKind.EEPROM_16K) 0xC0.toByte() else 0x80.toByte()
                ram[rxAt + 2] = 0x00
                return true
            }

            0x04 -> {
                if (tx < 2 || rx < 8) return false
                val block = ram[txAt + 1].toInt() and 0xFF
                for (i in 0 until 8) {
                    val at = block * 8 + i
                    ram[rxAt + i] = if (at < eeprom.size) eeprom[at] else 0
                }
                return true
            }

            0x05 -> {
                if (tx < 10) return false
                val block = ram[txAt + 1].toInt() and 0xFF
                for (i in 0 until 8) {
                    val at = block * 8 + i
                    if (at < eeprom.size) eeprom[at] = ram[txAt + 2 + i]
                }
                eepromDirty++
                if (rx >= 1) ram[rxAt] = 0
                return true
            }
        }
        return false
    }
}
