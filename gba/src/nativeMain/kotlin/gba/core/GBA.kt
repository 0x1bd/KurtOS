package gba.core

class GBA(rom: ByteArray) {
    val cartridge = Cartridge(rom)

    private val interrupts = Interrupts()
    val keypad = Keypad()
    private val ppu = PPU(interrupts)
    private val bus = Bus(cartridge, ppu, interrupts, keypad)
    private val dma = DMA(interrupts)
    private val timers = Timers(interrupts)
    val apu = APU(interrupts)
    private val cpu = Arm7(bus, interrupts)

    val frame: ShortArray get() = ppu.frame

    init {
        bus.dma = dma
        bus.timers = timers
        bus.apu = apu
        dma.bus = bus
        timers.apu = apu
        apu.dma = dma

        ppu.onHBlank = { dma.onHBlank() }
        ppu.onVBlank = { dma.onVBlank() }
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
    }
}
