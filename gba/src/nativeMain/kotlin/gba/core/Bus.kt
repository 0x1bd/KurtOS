package gba.core

import kapi.state.StateReader
import kapi.state.StateWriter

class Bus(
    val cartridge: Cartridge,
    val ppu: PPU,
    val interrupts: Interrupts,
    val keypad: Keypad,
) {
    val ewram = ByteArray(0x40000)
    val iwram = ByteArray(0x8000)

    lateinit var dma: DMA
    lateinit var timers: Timers
    lateinit var apu: APU
    lateinit var sio: Sio

    var haltRequested = false
    var waitControl = 0
    var postFlag = 0

    fun save(writer: StateWriter) {
        writer.bytes(ewram)
        writer.bytes(iwram)
        writer.bool(haltRequested)
        writer.int(waitControl)
        writer.int(postFlag)
    }

    fun load(reader: StateReader) {
        reader.bytes(ewram)
        reader.bytes(iwram)
        haltRequested = reader.bool()
        waitControl = reader.int()
        postFlag = reader.int()
    }

    fun read8(address: Int): Int = when ((address ushr 24) and 0xF) {
        0x0 -> 0
        0x2 -> ewram[address and 0x3FFFF].toInt() and 0xFF
        0x3 -> iwram[address and 0x7FFF].toInt() and 0xFF
        0x4 -> {
            val half = ioRead16(address and 0x3FE)
            if (address and 1 == 0) half and 0xFF else (half ushr 8) and 0xFF
        }
        0x5 -> ppu.palette[address and 0x3FF].toInt() and 0xFF
        0x6 -> ppu.vram[vramIndex(address)].toInt() and 0xFF
        0x7 -> ppu.oam[address and 0x3FF].toInt() and 0xFF
        0xE, 0xF -> cartridge.readBackup(address)
        else -> {
            val offset = address and 0x1FFFFFF
            if (isGpio(offset)) {
                val half = cartridge.gpio.read(offset and 1.inv())
                if (address and 1 == 0) half and 0xFF else (half ushr 8) and 0xFF
            } else if (offset < cartridge.rom.size) {
                cartridge.rom[offset].toInt() and 0xFF
            } else {
                ((offset shr 1) ushr ((address and 1) * 8)) and 0xFF
            }
        }
    }

    fun read16(address: Int): Int = when ((address ushr 24) and 0xF) {
        0x0 -> 0
        0x2 -> ram16(ewram, address and 0x3FFFE)
        0x3 -> ram16(iwram, address and 0x7FFE)
        0x4 -> ioRead16(address and 0x3FE)
        0x5 -> ram16(ppu.palette, address and 0x3FE)
        0x6 -> ram16(ppu.vram, vramIndex(address) and 1.inv())
        0x7 -> ram16(ppu.oam, address and 0x3FE)
        0xE, 0xF -> {
            val value = cartridge.readBackup(address)
            value or (value shl 8)
        }
        else -> {
            val offset = address and 0x1FFFFFE
            if (isGpio(offset)) cartridge.gpio.read(offset)
            else if (offset + 1 < cartridge.rom.size) ram16(cartridge.rom, offset)
            else (offset shr 1) and 0xFFFF
        }
    }

    private fun isGpio(offset: Int): Boolean =
        cartridge.gpio.present && cartridge.gpio.readable && (offset and 1.inv()) in 0xC4..0xC8

    fun read32(address: Int): Int {
        val aligned = address and 3.inv()
        return read16(aligned) or (read16(aligned + 2) shl 16)
    }

    fun write8(address: Int, value: Int) {
        when ((address ushr 24) and 0xF) {
            0x2 -> ewram[address and 0x3FFFF] = value.toByte()
            0x3 -> iwram[address and 0x7FFF] = value.toByte()
            0x4 -> {
                val offset = address and 0x3FF
                if (offset == 0x301) {
                    haltRequested = true
                    return
                }
                val half = ioRead16(offset and 0x3FE)
                val merged = if (offset and 1 == 0) (half and 0xFF00.toInt()) or (value and 0xFF)
                else (half and 0xFF) or ((value and 0xFF) shl 8)
                ioWrite16(offset and 0x3FE, merged)
            }
            0x5 -> {
                val index = address and 0x3FE
                ppu.palette[index] = value.toByte()
                ppu.palette[index + 1] = value.toByte()
            }
            0x6 -> {
                val index = vramIndex(address) and 1.inv()
                if (index < 0x14000) {
                    ppu.vram[index] = value.toByte()
                    ppu.vram[index + 1] = value.toByte()
                }
            }
            0xE, 0xF -> cartridge.writeBackup(address, value and 0xFF)
        }
    }

    fun write16(address: Int, value: Int) {
        when ((address ushr 24) and 0xF) {
            0x8 -> {
                val offset = address and 0x1FFFFFE
                if (cartridge.gpio.present && offset in 0xC4..0xC8) cartridge.gpio.write(offset, value)
            }
            0x2 -> ram16Write(ewram, address and 0x3FFFE, value)
            0x3 -> ram16Write(iwram, address and 0x7FFE, value)
            0x4 -> ioWrite16(address and 0x3FE, value and 0xFFFF)
            0x5 -> ram16Write(ppu.palette, address and 0x3FE, value)
            0x6 -> ram16Write(ppu.vram, vramIndex(address) and 1.inv(), value)
            0x7 -> ram16Write(ppu.oam, address and 0x3FE, value)
            0xE, 0xF -> cartridge.writeBackup(address, value and 0xFF)
        }
    }

    fun write32(address: Int, value: Int) {
        val aligned = address and 3.inv()

        if ((address ushr 24) and 0xF == 0x4) {
            val offset = aligned and 0x3FF
            if (offset == 0xA0 || offset == 0xA4) {
                apu.fifoWrite(offset == 0xA4, value)
                return
            }
        }

        write16(aligned, value and 0xFFFF)
        write16(aligned + 2, value ushr 16)
    }

    private fun vramIndex(address: Int): Int {
        var index = address and 0x1FFFF
        if (index >= 0x18000) index -= 0x8000
        return index
    }

    private fun ram16(array: ByteArray, index: Int): Int =
        (array[index].toInt() and 0xFF) or ((array[index + 1].toInt() and 0xFF) shl 8)

    private fun ram16Write(array: ByteArray, index: Int, value: Int) {
        array[index] = value.toByte()
        array[index + 1] = (value ushr 8).toByte()
    }

    private fun ioRead16(offset: Int): Int = when {
        offset < 0x60 -> ppu.ioRead(offset)
        offset < 0xB0 -> apu.ioRead(offset)
        offset < 0xE0 -> dma.ioRead(offset)
        offset in 0x100..0x10E -> timers.ioRead(offset)
        offset in 0x120..0x12A -> sio.ioRead(offset)
        offset == 0x130 -> keypad.state
        offset == 0x132 -> keypad.control
        offset == 0x134 -> sio.ioRead(offset)
        offset == 0x200 -> interrupts.enabled
        offset == 0x202 -> interrupts.flags
        offset == 0x204 -> waitControl
        offset == 0x208 -> if (interrupts.master) 1 else 0
        offset == 0x300 -> postFlag
        else -> 0
    }

    private fun ioWrite16(offset: Int, value: Int) {
        when {
            offset < 0x60 -> ppu.ioWrite(offset, value)
            offset < 0xB0 -> apu.ioWrite(offset, value)
            offset < 0xE0 -> dma.ioWrite(offset, value)
            offset in 0x100..0x10E -> timers.ioWrite(offset, value)
            offset in 0x120..0x12A -> sio.ioWrite(offset, value)
            offset == 0x132 -> keypad.control = value
            offset == 0x134 -> sio.ioWrite(offset, value)
            offset == 0x200 -> interrupts.enabled = value and 0x3FFF
            offset == 0x202 -> interrupts.acknowledge(value)
            offset == 0x204 -> waitControl = value
            offset == 0x208 -> interrupts.master = value and 1 != 0
            offset == 0x300 -> postFlag = value and 1
        }
    }
}
