package gba.core

import kapi.state.StateReader
import kapi.state.StateWriter

class GBA(rom: ByteArray, clock: RtcClock? = null) {
    val cartridge = Cartridge(rom, clock)

    private val interrupts = Interrupts()
    val keypad = Keypad()
    private val ppu = PPU(interrupts)
    private val bus = Bus(cartridge, ppu, interrupts, keypad)
    private val dma = DMA(interrupts)
    private val timers = Timers(interrupts)
    private val sio = Sio(interrupts)
    val apu = APU(interrupts)
    private val cpu = Arm7(bus, interrupts)

    val frame: ShortArray get() = ppu.frame

    init {
        bus.dma = dma
        bus.timers = timers
        bus.apu = apu
        bus.sio = sio
        dma.bus = bus
        timers.apu = apu
        apu.dma = dma

        ppu.onHBlank = { dma.onHBlank() }
        ppu.onVBlank = { dma.onVBlank() }
    }

    fun saveState(): ByteArray {
        val writer = StateWriter()

        writer.int(MAGIC)
        writer.int(VERSION)

        cpu.save(writer)
        bus.save(writer)
        ppu.save(writer)
        apu.save(writer)
        dma.save(writer)
        timers.save(writer)
        sio.save(writer)
        interrupts.save(writer)
        keypad.save(writer)
        cartridge.save(writer)

        return writer.toByteArray()
    }

    fun loadState(data: ByteArray): Boolean {
        val reader = StateReader(data)

        if (reader.int() != MAGIC) return false
        if (reader.int() != VERSION) return false

        cpu.load(reader)
        bus.load(reader)
        ppu.load(reader)
        apu.load(reader)
        dma.load(reader)
        timers.load(reader)
        sio.load(reader)
        interrupts.load(reader)
        keypad.load(reader)
        cartridge.load(reader)

        return reader.valid
    }

    fun runFrame() {
        var elapsed = 0

        while (elapsed < FRAME_CYCLES) {
            val cycles = cpu.step()

            if (bus.haltRequested) {
                bus.haltRequested = false
                cpu.halted = true
            }

            ppu.step(cycles)
            timers.step(cycles)
            apu.step(cycles)

            elapsed += cycles

            if (ppu.consumeFrame()) return
        }
    }

    companion object {
        const val FRAME_CYCLES = 280896

        private const val MAGIC = 0x41425347
        private const val VERSION = 1
    }
}
