package snes.core

import kapi.state.StateReader
import kapi.state.StateWriter

class Bus(val cartridge: Cartridge) : Memory {
    val wram = ByteArray(0x20000)

    var mdr = 0
    var fastRom = false

    var ppu: Ppu? = null
    var apu: Apu? = null
    var regs: InternalRegs? = null
    var dma: Dma? = null
    var joypad: Joypad? = null
    var onApuAccess: (() -> Unit)? = null
    var cpu: Cpu? = null

    private var wramAddress = 0

    override fun read8(addr: Int): Int {
        val bank = addr ushr 16
        val offset = addr and 0xFFFF

        if (bank == 0x7E || bank == 0x7F) {
            mdr = wram[addr - 0x7E0000].toInt() and 0xFF
            return mdr
        }

        if (bank and 0x40 == 0 && offset < 0x8000) {
            val value = readSystem(addr, offset)
            if (value >= 0) mdr = value
            return mdr
        }

        val value = cartridge.read(addr)
        if (value >= 0) mdr = value

        return mdr
    }

    override fun write8(addr: Int, value: Int) {
        mdr = value

        val bank = addr ushr 16
        val offset = addr and 0xFFFF

        if (bank == 0x7E || bank == 0x7F) {
            wram[addr - 0x7E0000] = value.toByte()
            return
        }

        if (bank and 0x40 == 0 && offset < 0x8000) {
            writeSystem(addr, offset, value)
            return
        }

        cartridge.write(addr, value)
    }

    override fun speed(addr: Int): Int {
        val bank = addr ushr 16

        if (bank == 0x7E || bank == 0x7F) return SLOW

        if (bank and 0x40 == 0) {
            val offset = addr and 0xFFFF

            if (offset < 0x2000) return SLOW
            if (offset < 0x4000) return FAST
            if (offset < 0x4200) return XSLOW
            if (offset < 0x6000) return FAST
            if (offset < 0x8000) return SLOW
        }

        return if (bank >= 0x80 && fastRom) FAST else SLOW
    }

    private fun readSystem(addr: Int, offset: Int): Int = when {
        offset < 0x2000 -> wram[offset].toInt() and 0xFF
        offset < 0x2100 -> -1
        offset < 0x2140 -> ppu?.readReg(offset) ?: -1

        offset < 0x2180 -> {
            onApuAccess?.invoke()
            apu?.readPort(offset and 3) ?: -1
        }

        offset == 0x2180 -> readWramPort()
        offset < 0x2184 -> -1
        offset == 0x4016 -> joypad?.readSerial(0) ?: -1
        offset == 0x4017 -> joypad?.readSerial(1) ?: -1
        offset < 0x4200 -> -1
        offset < 0x4220 -> regs?.read(offset) ?: -1
        offset < 0x4300 -> -1
        offset < 0x4380 -> dma?.readReg(offset) ?: -1
        offset < 0x6000 -> -1
        else -> cartridge.read(addr)
    }

    private fun writeSystem(addr: Int, offset: Int, value: Int) {
        when {
            offset < 0x2000 -> wram[offset] = value.toByte()
            offset < 0x2100 -> Unit
            offset < 0x2140 -> ppu?.writeReg(offset, value)

            offset < 0x2180 -> {
                onApuAccess?.invoke()
                apu?.writePort(offset and 3, value)
            }

            offset == 0x2180 -> writeWramPort(value)
            offset == 0x2181 -> wramAddress = (wramAddress and 0x1FF00) or value
            offset == 0x2182 -> wramAddress = (wramAddress and 0x100FF) or (value shl 8)
            offset == 0x2183 -> wramAddress = (wramAddress and 0x0FFFF) or ((value and 1) shl 16)
            offset == 0x4016 -> joypad?.strobe(value and 1 != 0)
            offset < 0x4200 -> Unit
            offset < 0x4220 -> regs?.write(offset, value)
            offset < 0x4300 -> Unit
            offset < 0x4380 -> dma?.writeReg(offset, value)
            offset < 0x6000 -> Unit
            else -> cartridge.write(addr, value)
        }
    }

    private fun readWramPort(): Int {
        val value = wram[wramAddress and 0x1FFFF].toInt() and 0xFF
        wramAddress = (wramAddress + 1) and 0x1FFFF
        return value
    }

    private fun writeWramPort(value: Int) {
        wram[wramAddress and 0x1FFFF] = value.toByte()
        wramAddress = (wramAddress + 1) and 0x1FFFF
    }

    fun save(writer: StateWriter) {
        writer.bytes(wram)
        writer.int(mdr)
        writer.int(wramAddress)
        writer.bool(fastRom)
    }

    fun load(reader: StateReader) {
        reader.bytes(wram)
        mdr = reader.int()
        wramAddress = reader.int()
        fastRom = reader.bool()
    }

    companion object {
        const val FAST = 6
        const val SLOW = 8
        const val XSLOW = 12
    }
}
