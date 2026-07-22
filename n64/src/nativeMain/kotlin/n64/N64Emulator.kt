package n64

import kapi.emu.Emulator
import kapi.emu.EmulatorSession
import kapi.emu.Video
import kapi.gpu.Gpu
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
        Gpu.register(n64.core.SoftwareGpu())
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

        private var snapGpuPx = 0L
        private var snapTotalPx = 0L
        private var snapDisp = 0L
        private var snapFrame = 0
        private var snapBusy = 0L
        private var snapNow = 0L
        private var snapWriteCycles = 0L
        private var snapReadCycles = 0L
        private var snapWriteWords = 0L
        private var snapReadWords = 0L
        private var snapFlush = 0
        private var snapRebind = 0
        private var snapSpans = 0L
        private var snapGroups = 0L
        private var snapBreakTmem = 0L
        private var snapBreakUniform = 0L
        private var snapBreakCapacity = 0L
        private var snapBreakForced = 0L
        private var diagLine: String? = null

        override fun diagnostics(): String? {
            val frame = console.frameCount
            if (frame - snapFrame >= DIAG_FRAMES) {
                val gpuPx = console.rdp.gpuPixels
                val totalPx = console.rdp.pixels
                val disp = console.rdp.gpuDispatches

                val dGpu = gpuPx - snapGpuPx
                val dTotal = totalPx - snapTotalPx
                val dDisp = disp - snapDisp

                snapGpuPx = gpuPx
                snapTotalPx = totalPx
                snapDisp = disp
                snapFrame = frame

                val pct = if (dTotal > 0) (dGpu * 100 / dTotal).toInt() else 0
                val backend = Gpu.backend
                val busy = backend?.busyCycles() ?: 0L
                val dBusy = (busy - snapBusy) / 1000 / DIAG_FRAMES
                snapBusy = busy

                val now = backend?.nowCycles() ?: 0L
                val wc = backend?.writeCycles() ?: 0L
                val rc = backend?.readCycles() ?: 0L
                val ww = backend?.writeWords() ?: 0L
                val rw = backend?.readWords() ?: 0L
                val dWall = now - snapNow
                val dWc = wc - snapWriteCycles
                val dRc = rc - snapReadCycles
                val dWw = ww - snapWriteWords
                val dRw = rw - snapReadWords
                snapNow = now
                snapWriteCycles = wc
                snapReadCycles = rc
                snapWriteWords = ww
                snapReadWords = rw

                val wallK = dWall / 1000 / DIAG_FRAMES
                val wK = dWc / 1000 / DIAG_FRAMES
                val rK = dRc / 1000 / DIAG_FRAMES
                val wKb = dWw / 256 / DIAG_FRAMES
                val rKb = dRw / 256 / DIAG_FRAMES
                val r2 = console.rdp
                val dFlush = (r2.spanFlushes - snapFlush) / DIAG_FRAMES
                val dRebind = (r2.spanRebinds - snapRebind) / DIAG_FRAMES
                snapFlush = r2.spanFlushes
                snapRebind = r2.spanRebinds
                val dispPer = dDisp / DIAG_FRAMES
                val dSpans = r2.spanSubmitted - snapSpans
                snapSpans = r2.spanSubmitted
                val perDisp = if (dDisp > 0) dSpans / dDisp else 0
                val dGroups = r2.spanGroups - snapGroups
                snapGroups = r2.spanGroups
                val perGroup = if (dDisp > 0) dGroups / dDisp else 0
                val dbT = (r2.spanBreakTmem - snapBreakTmem) / DIAG_FRAMES
                val dbU = (r2.spanBreakUniform - snapBreakUniform) / DIAG_FRAMES
                val dbC = (r2.spanBreakCapacity - snapBreakCapacity) / DIAG_FRAMES
                val dbX = (r2.spanBreakForced - snapBreakForced) / DIAG_FRAMES
                snapBreakTmem = r2.spanBreakTmem
                snapBreakUniform = r2.spanBreakUniform
                snapBreakCapacity = r2.spanBreakCapacity
                snapBreakForced = r2.spanBreakForced

                val state = if (console.rdp.gpuActive) r2.spanFail.toString() else "X"
                diagLine = "w$wallK B$dBusy up$wK/${wKb}K rd$rK/${rKb}K " +
                    "f$dFlush b$dRebind D$dispPer x$perDisp G$perGroup " +
                    "kT$dbT/U$dbU/C$dbC/X$dbX g$pct% F$state"
            }
            return diagLine
        }

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

    private const val DIAG_FRAMES = 30
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
