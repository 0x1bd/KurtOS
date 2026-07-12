package gameboy.core

class GameBoy(rom: ByteArray) {
    val cartridge = Cartridge(rom)

    private val interrupts = Interrupts()
    private val ppu = PPU(interrupts)
    private val timer = Timer(interrupts)
    private val apu = APU()

    val joypad = Joypad(interrupts)

    private val bus = Bus(cartridge, ppu, timer, joypad, apu, interrupts)
    private val cpu = CPU(bus, interrupts)

    val frame: ByteArray get() = ppu.frame

    fun runFrame() {
        var elapsed = 0
        while (elapsed < FRAME_CYCLES) {
            val cycles = cpu.step()
            timer.step(cycles)
            ppu.step(cycles)
            elapsed += cycles
        }
    }

    companion object {
        const val FRAME_CYCLES = 70224
    }
}
