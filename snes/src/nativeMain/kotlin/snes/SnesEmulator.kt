package snes

import kapi.emu.Emulator
import kapi.emu.EmulatorSession
import kapi.emu.Video
import snes.core.Ppu
import snes.core.SNES

object SnesEmulator : Emulator {
    override val id = "snes"
    override val system = "SNES"
    override val extensions = listOf(".sfc", ".smc")
    override val frameMicros = SNES.NTSC_MICROS

    override fun load(image: ByteArray): EmulatorSession? {
        val console = SNES(image)
        if (!console.cartridge.supported) return null
        return Session(console)
    }

    private class Session(private val console: SNES) : EmulatorSession {
        override val video = Video.HighColor(Ppu.WIDTH, Ppu.HEIGHT, console.frame)

        override val frameMicros = console.frameMicros

        override val audioSamples get() = console.apu.samples
        override val audioFrames get() = console.apu.frames

        override fun setButtons(buttons: Int) = console.setButtons(buttons)

        override fun runFrame() = console.runFrame()

        override fun drainAudio() = console.apu.drain()

        override fun describe(): String = console.describe()

        override fun saveData(): ByteArray = console.saveData()

        override fun loadSaveData(data: ByteArray) = console.loadSaveData(data)

        override fun saveVersion(): Int = console.saveVersion()

        override fun saveState(): ByteArray = console.saveState()

        override fun loadState(data: ByteArray): Boolean = console.loadState(data)
    }
}
