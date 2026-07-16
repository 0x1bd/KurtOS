package frontend

import kapi.Audio
import kapi.Console
import kapi.Files
import kapi.Gamepad
import kapi.Input
import kapi.Keys
import kapi.Pad
import kapi.Surface
import kapi.Sys
import kapi.Time
import kapi.emu.Button
import kapi.emu.EmulatorSession
import kapi.emu.Video
import kapi.ui.Canvas
import kapi.ui.Panels

object Player {
    fun play(surface: Surface, game: Game): String? {
        val image = GameLibrary.load(game)
        if (image == null) return "cannot read ${game.path} (${Files.status()})"
        if (image.size < game.size.toInt()) return "${game.name}: read ${image.size} of ${game.size} bytes"

        val session = game.emulator.load(image)
        if (session == null) return "${game.name}: not a valid ${game.emulator.system.lowercase()} rom"

        val screen = screenFor(surface, session.video)
        if (screen == null) return "cannot create a ${session.video.width}x${session.video.height} buffer"

        val saved = GameLibrary.loadSave(game, MAX_SAVE_BYTES)
        if (saved != null) session.loadSaveData(saved)

        val title = game.name.uppercase()
        val chrome = Canvas(surface)
        var fullscreen = false
        var repaint = true
        var clock = ""
        statsWidth = 0

        var written = session.saveVersion()
        var seen = written
        var checked = Time.uptimeMillis()

        surface.clear(0x00000000u)
        surface.presentAll()

        val sound = Audio.open()

        Input.drain()
        while (Console.tryReadChar() != null) {
        }

        val started = Time.uptimeMillis()
        var frames = 0
        var next = started * MICROS_PER_MILLI
        var volumeRepeat = 0UL

        var measured = started
        var counted = 0
        var rate = 0

        var emuCycles = 0UL
        var presentCycles = 0UL
        var audioCycles = 0UL
        var inputCycles = 0UL
        var totalCycles = 0UL
        var worstCycles = 0UL
        var sliverCycles = 0UL
        var idleCycles = 0UL
        var loopEnd = 0UL
        var winFrames = 0
        var mhz = 0
        var vips = 0
        var clockRtc0 = 0L
        var clockUp0 = 0UL
        var clockTsc0 = 0UL
        var tickSkew = 0
        var tscSkew = 0
        var emuMs = 0
        var presentMs = 0
        var audioMs = 0
        var inputMs = 0
        var otherMs = 0
        var worstMs = 0
        var sliverMs = 0
        var idleMs = 0

        while (true) {
            val iterStart = Time.cycles()
            if (loopEnd != 0UL) sliverCycles += iterStart - loopEnd
            val inputStart = Time.cycles()
            Input.poll()

            val padded = Gamepad.available()
            if (padded) Gamepad.poll()
            inputCycles += Time.cycles() - inputStart

            if (Input.consumePress(Keys.ESC) || Input.isKeyDown(Keys.ESC)) break
            if (padded && quitting()) break

            if (Input.consumePress(Keys.F2) || (padded && shortcut(Pad.L))) {
                val state = session.saveState()
                if (state == null) {
                    Sys.toast("NO SAVE STATE", "${game.emulator.system} DOES NOT SUPPORT STATES")
                } else if (GameLibrary.storeState(game, state)) {
                    Sys.toast("STATE SAVED", game.name.uppercase())
                } else {
                    Sys.toast("STATE NOT SAVED", "CANNOT WRITE ${GameLibrary.statePath(game)}")
                }
            }

            if (Input.consumePress(Keys.F4) || (padded && shortcut(Pad.R))) {
                val state = GameLibrary.loadState(game, MAX_STATE_BYTES)
                if (state == null) {
                    Sys.toast("NO STATE", "NOTHING SAVED FOR ${game.name.uppercase()}")
                } else if (session.loadState(state)) {
                    Sys.toast("STATE LOADED", game.name.uppercase())
                } else {
                    Sys.toast("STATE REJECTED", "SAVED BY A DIFFERENT BUILD")
                }
            }

            if (padded) volumeRepeat = adjustVolume(volumeRepeat)

            if (padded && sticksClicked()) {
                fullscreen = !fullscreen
                repaint = true
                Audio.click()
            }

            if (repaint) {
                repaint = false
                clock = ""
                statsWidth = 0
                surface.clear(BACKDROP)
                screen.layout(surface, fullscreen, session.video)
                surface.presentAll()
            }

            if (!fullscreen) {
                val tick = Chrome.clockText()
                if (tick != clock) {
                    clock = tick
                    Chrome.drawStatusBar(chrome, surface.width.toInt(), Chrome.barHeight(surface.height.toInt()), title, tick)
                }
            }

            session.setButtons(buttons(padded))

            val emuStart = Time.cycles()
            session.runFrame()
            emuCycles += Time.cycles() - emuStart
            frames++
            winFrames++

            val audioStart = Time.cycles()
            if (sound) {
                Audio.write(session.audioSamples, session.audioFrames)
            }
            session.drainAudio()
            audioCycles += Time.cycles() - audioStart

            val presentStart = Time.cycles()
            if (session.frameChanged) screen.draw()

            if (Settings.showFps) {
                if (session.frameChanged) counted++
                drawStats(chrome, rate, vips, mhz, tickSkew, tscSkew, emuMs, presentMs, audioMs, inputMs, otherMs, worstMs, sliverMs, idleMs, fullscreen)
            }

            surface.present()
            presentCycles += Time.cycles() - presentStart

            val drainStart = Time.cycles()
            Input.drain()
            inputCycles += Time.cycles() - drainStart

            val tick = Time.uptimeMillis()
            if (tick - checked >= SAVE_POLL_MS) {
                checked = tick

                val version = session.saveVersion()
                if (version != written && version == seen) {
                    if (store(game, session)) written = version
                }
                seen = version
            }

            if (frames % GC_INTERVAL_FRAMES == 0) Sys.collectGarbage()

            val iterCycles = Time.cycles() - iterStart
            totalCycles += iterCycles
            if (iterCycles > worstCycles) worstCycles = iterCycles
            loopEnd = iterStart + iterCycles

            if (Settings.showFps) {
                val tock = Time.uptimeMillis()
                if (tock - measured >= FPS_WINDOW_MS) {
                    rate = (counted.toULong() * 1000UL / (tock - measured)).toInt()
                    vips = (winFrames.toULong() * 1000UL / (tock - measured)).toInt()
                    val perMs = Sys.tscMhz().toULong() * 1000UL
                    if (perMs > 0UL && winFrames > 0) {
                        val f = winFrames.toULong()
                        emuMs = (emuCycles / f / perMs).toInt()
                        presentMs = (presentCycles / f / perMs).toInt()
                        audioMs = (audioCycles / f / perMs).toInt()
                        inputMs = (inputCycles / f / perMs).toInt()
                        val accounted = emuCycles + presentCycles + audioCycles + inputCycles
                        val other = if (totalCycles > accounted) totalCycles - accounted else 0UL
                        otherMs = (other / f / perMs).toInt()
                        worstMs = (worstCycles / perMs).toInt()
                        sliverMs = (sliverCycles / f / perMs).toInt()
                        idleMs = (idleCycles / f / perMs).toInt()
                    }
                    mhz = Sys.cpuMhz()
                    val epoch = Time.epochSeconds()
                    if (epoch != null) {
                        if (clockRtc0 == 0L) {
                            clockRtc0 = epoch
                            clockUp0 = tock
                            clockTsc0 = Time.cycles()
                        } else {
                            val seconds = (epoch - clockRtc0).toULong()
                            if (seconds >= 10UL) {
                                tickSkew = ((tock - clockUp0) / (seconds * 10UL)).toInt()
                                val tsc = Sys.tscMhz().toULong()
                                if (tsc > 0UL) {
                                    tscSkew = ((Time.cycles() - clockTsc0) / (seconds * tsc * 10_000UL)).toInt()
                                }
                            }
                        }
                    }
                    counted = 0
                    measured = tock
                    emuCycles = 0UL
                    presentCycles = 0UL
                    audioCycles = 0UL
                    inputCycles = 0UL
                    totalCycles = 0UL
                    worstCycles = 0UL
                    sliverCycles = 0UL
                    idleCycles = 0UL
                    winFrames = 0
                }
            }

            next += session.frameMicros ?: game.emulator.frameMicros
            val now = Time.uptimeMillis() * MICROS_PER_MILLI
            if (now > next + MAX_DEBT_MICROS) {
                next = now - MAX_DEBT_MICROS
            } else if (now < next) {
                val idleStart = Time.cycles()
                while (Time.uptimeMillis() * MICROS_PER_MILLI < next) Time.idle()
                idleCycles += Time.cycles() - idleStart
            }
        }

        if (sound) Audio.close()

        var failed = false
        if (session.saveVersion() != written) failed = !store(game, session)

        releaseQuitKey()

        if (!failed) return null

        return "${game.name}: could not save to ${GameLibrary.savePath(game)}"
    }

    private fun drawStats(
        canvas: Canvas,
        rate: Int,
        vips: Int,
        mhz: Int,
        tickSkew: Int,
        tscSkew: Int,
        emuMs: Int,
        presentMs: Int,
        audioMs: Int,
        inputMs: Int,
        otherMs: Int,
        worstMs: Int,
        sliverMs: Int,
        idleMs: Int,
        fullscreen: Boolean,
    ) {
        val lines = arrayOf(
            "$rate FPS V$vips",
            "$mhz MHZ T$tickSkew C$tscSkew",
            "E$emuMs P$presentMs W$worstMs",
            "A$audioMs I$inputMs O$otherMs S$sliverMs Z$idleMs",
            Input.diagnostics(),
        )

        var width = 0
        for (line in lines) {
            val w = canvas.textWidth(line, FPS_SCALE)
            if (w > width) width = w
        }
        if (width > statsWidth) statsWidth = width
        width = statsWidth

        val lineHeight = canvas.glyphHeight * FPS_SCALE + 4
        val x = canvas.width - width - FPS_MARGIN * 2 - 12
        val y = if (fullscreen) FPS_MARGIN else Chrome.barHeight(canvas.height) + FPS_MARGIN

        canvas.fill(x, y, width + 16, lineHeight * lines.size + 8, Panels.BAR)

        var textY = y + 6
        for (line in lines) {
            canvas.text(x + 8, textY, line, Panels.GREEN, FPS_SCALE)
            textY += lineHeight
        }
    }

    private fun store(game: Game, session: EmulatorSession): Boolean {
        val data = session.saveData() ?: return true
        return GameLibrary.storeSave(game, data)
    }

    private abstract class Screen {
        var scale = 1u
        var originX = 0u
        var originY = 0u

        abstract fun draw()

        fun layout(surface: Surface, fullscreen: Boolean, video: Video) {
            val top = if (fullscreen) 0 else Chrome.barHeight(surface.height.toInt())
            val room = surface.height - top.toUInt()

            val horizontal = surface.width / video.width.toUInt()
            val vertical = room / video.height.toUInt()
            val fit = if (horizontal < vertical) horizontal else vertical

            scale = if (fit < 1u) 1u else fit
            originX = (surface.width - video.width.toUInt() * scale) / 2u
            originY = top.toUInt() + (room - video.height.toUInt() * scale) / 2u
        }
    }

    private fun screenFor(surface: Surface, video: Video): Screen? {
        val screen = when (video) {
            is Video.Indexed -> {
                val bitmap = surface.createBitmap(video.width.toUInt(), video.height.toUInt(), video.paletteSize)
                    ?: return null

                object : Screen() {
                    private var paletteVersion = -1

                    override fun draw() {
                        val version = video.paletteVersion()
                        if (version != paletteVersion) {
                            paletteVersion = version
                            for (i in 0 until video.paletteSize) {
                                bitmap.setPalette(i, video.palette[i].toUInt())
                            }
                        }

                        video.frame.copyInto(bitmap.pixels)
                        bitmap.draw(originX, originY, scale)
                    }
                }
            }

            is Video.HighColor -> {
                val bitmap = surface.createHighColorBitmap(video.width.toUInt(), video.height.toUInt())
                    ?: return null

                object : Screen() {
                    override fun draw() {
                        video.frame.copyInto(bitmap.pixels)
                        bitmap.draw(originX, originY, scale)
                    }
                }
            }
        }

        screen.layout(surface, false, video)

        return screen
    }

    private var statsWidth = 0

    private var sticksPrevious = false

    private fun sticksClicked(): Boolean {
        val down = Gamepad.isDown(Pad.L3) && Gamepad.isDown(Pad.R3)
        val fired = down && !sticksPrevious

        sticksPrevious = down

        return fired
    }

    private val comboPrevious = BooleanArray(Pad.COUNT)

    private fun shortcut(button: Int): Boolean {
        val down = Gamepad.isDown(Pad.SELECT) && Gamepad.isDown(button)
        val fired = down && !comboPrevious[button]

        comboPrevious[button] = down

        return fired
    }

    private fun combo(): Boolean =
        Gamepad.isDown(Pad.SELECT) && (Gamepad.isDown(Pad.L) || Gamepad.isDown(Pad.R))

    private fun quitting(): Boolean {
        if (Gamepad.isDown(Pad.GUIDE)) return true

        if (!Gamepad.isDown(Pad.START) || !Gamepad.isDown(Pad.SELECT)) return false

        return !Gamepad.isDown(Pad.A) && !Gamepad.isDown(Pad.B)
    }

    private fun adjustVolume(repeatAt: ULong): ULong {
        val louder = Gamepad.isDown(Pad.RT)
        val quieter = Gamepad.isDown(Pad.LT)

        if (louder == quieter) return 0UL

        val now = Time.uptimeMillis()
        if (now < repeatAt) return repeatAt

        Settings.setVolume(Settings.volume + if (louder) VOLUME_STEP else -VOLUME_STEP)
        Audio.showVolume()

        return now + if (repeatAt == 0UL) VOLUME_DELAY_MS else VOLUME_REPEAT_MS
    }

    private fun buttons(padded: Boolean): Int {
        var mask = 0

        if (Input.isKeyDown(Keys.Z) || (padded && Gamepad.isDown(Pad.A))) mask = mask or Button.A
        if (Input.isKeyDown(Keys.X) || (padded && Gamepad.isDown(Pad.B))) mask = mask or Button.B
        if (Input.isKeyDown(Keys.BACKSPACE) || (padded && Gamepad.isDown(Pad.SELECT))) mask = mask or Button.SELECT
        if (Input.isKeyDown(Keys.ENTER) || (padded && Gamepad.isDown(Pad.START))) mask = mask or Button.START
        if (Input.isKeyDown(Keys.RIGHT) || (padded && Gamepad.isDown(Pad.RIGHT))) mask = mask or Button.RIGHT
        if (Input.isKeyDown(Keys.LEFT) || (padded && Gamepad.isDown(Pad.LEFT))) mask = mask or Button.LEFT
        if (Input.isKeyDown(Keys.UP) || (padded && Gamepad.isDown(Pad.UP))) mask = mask or Button.UP
        if (Input.isKeyDown(Keys.DOWN) || (padded && Gamepad.isDown(Pad.DOWN))) mask = mask or Button.DOWN
        if (Input.isKeyDown(Keys.A) || (padded && Gamepad.isDown(Pad.L))) mask = mask or Button.L
        if (Input.isKeyDown(Keys.S) || (padded && Gamepad.isDown(Pad.R))) mask = mask or Button.R
        if (Input.isKeyDown(Keys.D) || (padded && Gamepad.isDown(Pad.X))) mask = mask or Button.X
        if (Input.isKeyDown(Keys.C) || (padded && Gamepad.isDown(Pad.Y))) mask = mask or Button.Y

        if (padded && combo()) mask = mask and (Button.SELECT or Button.L or Button.R).inv()

        return mask
    }

    private fun releaseQuitKey() {
        while (true) {
            Input.poll()
            if (Gamepad.available()) Gamepad.poll()

            if (Input.isKeyDown(Keys.ESC)) {
                Time.idle()
                continue
            }

            if (Gamepad.available() && Gamepad.isDown(Pad.GUIDE)) {
                Time.idle()
                continue
            }

            break
        }

        Input.drain()
    }

    private const val MICROS_PER_MILLI = 1000UL
    private const val MAX_DEBT_MICROS = 60000UL

    private const val GC_INTERVAL_FRAMES = 600

    private const val SAVE_POLL_MS = 2000UL
    private const val MAX_SAVE_BYTES = 262144u
    private const val MAX_STATE_BYTES = 4194304u

    private const val VOLUME_STEP = 5
    private const val VOLUME_DELAY_MS = 400UL
    private const val VOLUME_REPEAT_MS = 120UL

    private const val FPS_WINDOW_MS = 500UL
    private const val FPS_SCALE = 2
    private const val FPS_MARGIN = 16
    private const val BACKDROP: UInt = 0x000E1116u
}
