package n64

import kapi.emu.Emulator
import kapi.emu.EmulatorSession
import kapi.emu.Video
import n64.core.N64
import n64.core.VI_ORIGIN
import n64.core.VI

object N64Emulator : Emulator {
    override val id = "n64"
    override val system = "Nintendo 64"
    override val extensions = listOf(".z64", ".n64", ".v64")
    override val frameMicros = 16667uL
    override val players = 4

    override fun load(image: ByteArray): EmulatorSession? {
        val console = N64(image)
        if (!console.rom.valid) return null
        return Session(console)
    }

    private class Session(private val console: N64) : EmulatorSession {
        override val video = Video.HighColor(VI.WIDTH, VI.HEIGHT, console.vi.frame)

        override val frameMicros get() = console.frameMicros

        override val audioSamples get() = console.ai.samples
        override val audioFrames get() = console.ai.frames

        private var lastOrigin = -1
        private var swapped = true
        override val frameChanged get() = swapped

        override fun setButtons(buttons: Int) = setInput(0, buttons, 0, 0)

        override fun setInput(
            player: Int,
            buttons: Int,
            stickX: Int,
            stickY: Int,
            rightX: Int,
            rightY: Int,
            connected: Boolean,
        ) {
            if (player < 0 || player >= 4) return

            var value = translate(buttons)

            if (rightX > C_THRESHOLD) value = value or N64_C_RIGHT
            if (rightX < -C_THRESHOLD) value = value or N64_C_LEFT
            if (rightY > C_THRESHOLD) value = value or N64_C_UP
            if (rightY < -C_THRESHOLD) value = value or N64_C_DOWN

            var x = if (stickX > -STICK_DEADZONE && stickX < STICK_DEADZONE) 0 else stickX * 80 / 32767
            var y = if (stickY > -STICK_DEADZONE && stickY < STICK_DEADZONE) 0 else stickY * 80 / 32767

            if (x == 0 && y == 0) {
                x = when {
                    buttons and kapi.emu.Button.LEFT != 0 -> -80
                    buttons and kapi.emu.Button.RIGHT != 0 -> 80
                    else -> 0
                }
                y = when {
                    buttons and kapi.emu.Button.UP != 0 -> 80
                    buttons and kapi.emu.Button.DOWN != 0 -> -80
                    else -> 0
                }
            }

            console.setInput(player, value, x, y, connected)
        }

        override fun runFrame() {
            console.runFrame()
            val origin = console.vi.regs[VI_ORIGIN] and 0xFFFFFF
            swapped = origin != lastOrigin
            lastOrigin = origin
        }

        override fun drainAudio() = console.ai.drain()

        override fun describe(): String = console.describe()

        override fun saveData(): ByteArray? = console.cartridge.saveData()

        override fun loadSaveData(data: ByteArray) = console.cartridge.loadSaveData(data)

        override fun saveVersion(): Int = console.cartridge.saveVersion()

        private fun translate(buttons: Int): Int {
            var value = 0
            if (buttons and kapi.emu.Button.A != 0) value = value or N64_A
            if (buttons and kapi.emu.Button.B != 0) value = value or N64_B
            if (buttons and kapi.emu.Button.START != 0) value = value or N64_START
            if (buttons and kapi.emu.Button.SELECT != 0) value = value or N64_Z
            if (buttons and kapi.emu.Button.L != 0) value = value or N64_L
            if (buttons and kapi.emu.Button.R != 0) value = value or N64_R
            if (buttons and kapi.emu.Button.UP != 0) value = value or N64_UP
            if (buttons and kapi.emu.Button.DOWN != 0) value = value or N64_DOWN
            if (buttons and kapi.emu.Button.LEFT != 0) value = value or N64_LEFT
            if (buttons and kapi.emu.Button.RIGHT != 0) value = value or N64_RIGHT
            if (buttons and kapi.emu.Button.X != 0) value = value or N64_C_DOWN
            if (buttons and kapi.emu.Button.Y != 0) value = value or N64_C_LEFT

            return value
        }
    }

    private const val C_THRESHOLD = 16384
    private const val STICK_DEADZONE = 4096

    private const val N64_A = 0x8000
    private const val N64_B = 0x4000
    private const val N64_Z = 0x2000
    private const val N64_START = 0x1000
    private const val N64_UP = 0x0800
    private const val N64_DOWN = 0x0400
    private const val N64_LEFT = 0x0200
    private const val N64_RIGHT = 0x0100
    private const val N64_L = 0x0020
    private const val N64_R = 0x0010
    private const val N64_C_UP = 0x0008
    private const val N64_C_DOWN = 0x0004
    private const val N64_C_LEFT = 0x0002
    private const val N64_C_RIGHT = 0x0001
}
