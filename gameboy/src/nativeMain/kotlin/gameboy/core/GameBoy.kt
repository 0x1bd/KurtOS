package gameboy.core

class GameBoy(rom: ByteArray, monochromeShades: IntArray) {
    val cartridge = Cartridge(rom)

    val color: Boolean = cartridge.colorCapable

    private val interrupts = Interrupts()
    private val ppu = PPU(interrupts, color)
    private val timer = Timer(interrupts)
    val apu = APU()

    val joypad = Joypad(interrupts)

    private val bus = Bus(cartridge, ppu, timer, joypad, apu, interrupts, color)
    private val cpu = CPU(bus, interrupts, color)

    val frame: ByteArray get() = ppu.frame

    val palette: IntArray get() = ppu.colors

    val paletteVersion: Int
        get() = if (color) ppu.paletteVersion else 0

    init {
        if (!color) {
            for (i in monochromeShades.indices) {
                if (i < ppu.colors.size) ppu.colors[i] = monochromeShades[i]
            }
        }
    }

    fun runFrame() {
        var elapsed = 0

        while (elapsed < FRAME_DOTS) {
            val cycles = cpu.step()
            timer.step(cycles)

            val dots = if (bus.doubleSpeed) cycles / 2 else cycles
            ppu.step(dots)
            apu.step(dots)

            if (ppu.consumeHBlank()) bus.stepHBlank()

            elapsed += dots
        }
    }

    companion object {
        const val FRAME_DOTS = 70224
        const val PALETTE_SIZE = PPU.PALETTE_ENTRIES
    }
}
