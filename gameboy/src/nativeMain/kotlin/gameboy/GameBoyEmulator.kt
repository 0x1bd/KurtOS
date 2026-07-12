package gameboy

import gameboy.core.GameBoy
import gameboy.core.Joypad
import gameboy.core.PPU
import kapi.emu.Button
import kapi.emu.Emulator
import kapi.emu.EmulatorSession
import kapi.emu.Video

object GameBoyEmulator : Emulator {
    override val id = "gameboy"
    override val system = "GAME BOY"
    override val extensions = listOf(".gb", ".gbc")
    override val frameMicros = 16742UL

    private val shades = intArrayOf(0x9BBC0F, 0x8BAC0F, 0x306230, 0x0F380F)

    override fun load(image: ByteArray): EmulatorSession? {
        val console = GameBoy(image, shades)
        if (!console.cartridge.supported) return null
        return Session(console)
    }

    private class Session(private val console: GameBoy) : EmulatorSession {
        override val video = Video.Indexed(
            PPU.WIDTH,
            PPU.HEIGHT,
            GameBoy.PALETTE_SIZE,
            console.frame,
            console.palette,
        ) { console.paletteVersion }

        override val audioSamples get() = console.apu.samples
        override val audioFrames get() = console.apu.frames

        override fun setButtons(buttons: Int) {
            console.joypad.setButton(Joypad.RIGHT, buttons and Button.RIGHT != 0)
            console.joypad.setButton(Joypad.LEFT, buttons and Button.LEFT != 0)
            console.joypad.setButton(Joypad.UP, buttons and Button.UP != 0)
            console.joypad.setButton(Joypad.DOWN, buttons and Button.DOWN != 0)
            console.joypad.setButton(Joypad.A, buttons and Button.A != 0)
            console.joypad.setButton(Joypad.B, buttons and Button.B != 0)
            console.joypad.setButton(Joypad.SELECT, buttons and Button.SELECT != 0)
            console.joypad.setButton(Joypad.START, buttons and Button.START != 0)
        }

        override fun runFrame() = console.runFrame()

        override fun drainAudio() = console.apu.drain()

        override fun describe(): String {
            val mode = if (console.color) "cgb" else "dmg"
            return "$mode, ${console.cartridge.kindName}"
        }
    }
}
