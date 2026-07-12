package frontend

import kapi.Audio
import kapi.Console
import kapi.Files
import kapi.Gamepad
import kapi.Input
import kapi.Keys
import kapi.Pad
import kapi.Surface
import kapi.Time
import kapi.emu.Button
import kapi.emu.EmulatorSession
import kapi.emu.Video

object Player {
    fun play(surface: Surface, game: Game): String? {
        Console.clear()
        Console.println("loading ${game.name}...")

        val image = GameLibrary.load(game)
        if (image == null) return "cannot read ${game.path} (${Files.status()})"
        if (image.size < game.size.toInt()) return "${game.name}: read ${image.size} of ${game.size} bytes"

        val session = game.emulator.load(image)
        if (session == null) return "${game.name}: not a valid ${game.emulator.system.lowercase()} rom"

        val screen = screenFor(surface, session.video)
        if (screen == null) return "cannot create a ${session.video.width}x${session.video.height} buffer"

        surface.clear(0x00000000u)
        surface.presentAll()

        val sound = Audio.open()

        Input.drain()
        while (Console.tryReadChar() != null) {
        }

        val started = Time.uptimeMillis()
        var frames = 0
        var next = started * MICROS_PER_MILLI

        while (true) {
            Input.poll()

            val padded = Gamepad.available()
            if (padded) Gamepad.poll()

            if (Input.consumePress(Keys.ESC) || Input.isKeyDown(Keys.ESC)) break
            if (padded && Gamepad.isDown(Pad.GUIDE)) break

            session.setButtons(buttons(padded))

            session.runFrame()
            frames++

            if (sound) {
                Audio.write(session.audioSamples, session.audioFrames)
            }
            session.drainAudio()

            screen.present()

            Input.drain()

            next += game.emulator.frameMicros
            val now = Time.uptimeMillis() * MICROS_PER_MILLI
            if (now > next) {
                next = now
            } else {
                while (Time.uptimeMillis() * MICROS_PER_MILLI < next) Time.idle()
            }
        }

        if (sound) Audio.close()

        releaseQuitKey()
        Console.clear()

        return report(game, session, started, frames)
    }

    private interface Screen {
        fun present()
    }

    private fun screenFor(surface: Surface, video: Video): Screen? {
        val scale = scaleFor(surface, video)
        val originX = (surface.width - video.width.toUInt() * scale) / 2u
        val originY = (surface.height - video.height.toUInt() * scale) / 2u

        return when (video) {
            is Video.Indexed -> {
                val bitmap = surface.createBitmap(video.width.toUInt(), video.height.toUInt(), video.paletteSize)
                    ?: return null

                object : Screen {
                    private var paletteVersion = -1

                    override fun present() {
                        val version = video.paletteVersion()
                        if (version != paletteVersion) {
                            paletteVersion = version
                            for (i in 0 until video.paletteSize) {
                                bitmap.setPalette(i, video.palette[i].toUInt())
                            }
                        }

                        video.frame.copyInto(bitmap.pixels)
                        bitmap.draw(originX, originY, scale)
                        surface.present()
                    }
                }
            }

            is Video.HighColor -> {
                val bitmap = surface.createHighColorBitmap(video.width.toUInt(), video.height.toUInt())
                    ?: return null

                object : Screen {
                    override fun present() {
                        video.frame.copyInto(bitmap.pixels)
                        bitmap.draw(originX, originY, scale)
                        surface.present()
                    }
                }
            }
        }
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

        return mask
    }

    private fun report(game: Game, session: EmulatorSession, started: ULong, frames: Int): String? {
        val elapsed = Time.uptimeMillis() - started
        if (frames == 0) return "${game.name}: exited before drawing a frame"
        if (elapsed == 0UL) return null

        val fps = frames.toULong() * 1000UL / elapsed
        val expected = elapsed * FRAMES_PER_100K_MILLIS / 100000UL
        val speed = if (expected == 0UL) 0UL else frames.toULong() * 100UL / expected
        val detail = session.describe()

        return if (detail == null) {
            "${game.name}: $fps fps, $speed% speed"
        } else {
            "${game.name}: $fps fps, $speed% speed ($detail)"
        }
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

    private fun scaleFor(surface: Surface, video: Video): UInt {
        val horizontal = surface.width / video.width.toUInt()
        val vertical = surface.height / video.height.toUInt()
        val scale = if (horizontal < vertical) horizontal else vertical
        return if (scale < 1u) 1u else scale
    }

    private const val MICROS_PER_MILLI = 1000UL
    private const val FRAMES_PER_100K_MILLIS = 5973UL
}
