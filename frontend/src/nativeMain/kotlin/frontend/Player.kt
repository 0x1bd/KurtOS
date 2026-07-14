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
import kapi.ui.Panels
import kapi.ui.PixelFont
import kapi.ui.SurfaceSink

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
        val chrome = SurfaceSink(surface)
        var fullscreen = false
        var repaint = true
        var clock = ""

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

        while (true) {
            Input.poll()

            val padded = Gamepad.available()
            if (padded) Gamepad.poll()

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

            session.runFrame()
            frames++

            if (sound) {
                Audio.write(session.audioSamples, session.audioFrames)
            }
            session.drainAudio()

            if (session.frameChanged) screen.draw()

            if (Settings.showFps) {
                val tock = Time.uptimeMillis()
                if (session.frameChanged) counted++

                if (tock - measured >= FPS_WINDOW_MS) {
                    rate = (counted.toULong() * 1000UL / (tock - measured)).toInt()
                    counted = 0
                    measured = tock
                }

                drawRate(surface, chrome, rate, fullscreen)
            }

            surface.present()

            Input.drain()

            val tick = Time.uptimeMillis()
            if (tick - checked >= SAVE_POLL_MS) {
                checked = tick

                val version = session.saveVersion()
                if (version != written && version == seen) {
                    if (store(game, session)) written = version
                }
                seen = version
            }

            next += session.frameMicros ?: game.emulator.frameMicros
            val now = Time.uptimeMillis() * MICROS_PER_MILLI
            if (now > next) {
                next = now
            } else {
                while (Time.uptimeMillis() * MICROS_PER_MILLI < next) Time.idle()
            }
        }

        if (sound) Audio.close()

        var failed = false
        if (session.saveVersion() != written) failed = !store(game, session)

        releaseQuitKey()

        if (!failed) return null

        return "${game.name}: could not save to ${GameLibrary.savePath(game)}"
    }

    private fun drawRate(surface: Surface, sink: SurfaceSink, rate: Int, fullscreen: Boolean) {
        val text = "$rate FPS"
        val width = PixelFont.textWidth(text, FPS_SCALE)

        val x = surface.width.toInt() - width - FPS_MARGIN * 2 - 12
        val y = if (fullscreen) FPS_MARGIN else Chrome.barHeight(surface.height.toInt()) + FPS_MARGIN

        sink.fill(x, y, width + 16, PixelFont.HEIGHT * FPS_SCALE + 12, Panels.BAR)
        PixelFont.draw(sink, x + 8, y + 6, text, Panels.GREEN, FPS_SCALE)
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
