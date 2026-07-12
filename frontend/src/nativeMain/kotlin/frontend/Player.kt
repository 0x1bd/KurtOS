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

        val saved = GameLibrary.loadSave(game, MAX_SAVE_BYTES)
        if (saved != null) {
            session.loadSaveData(saved)
            Console.println("restored ${saved.size} byte save from ${GameLibrary.savePath(game)}")
        }

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

        var emulateCycles = 0UL
        var audioCycles = 0UL
        var videoCycles = 0UL
        var idleCycles = 0UL

        while (true) {
            Input.poll()

            val padded = Gamepad.available()
            if (padded) Gamepad.poll()

            if (Input.consumePress(Keys.ESC) || Input.isKeyDown(Keys.ESC)) break
            if (padded && Gamepad.isDown(Pad.GUIDE)) break

            session.setButtons(buttons(padded))

            var mark = Time.timestamp()

            session.runFrame()
            frames++

            var stamp = Time.timestamp()
            emulateCycles += stamp - mark
            mark = stamp

            if (sound) {
                Audio.write(session.audioSamples, session.audioFrames)
            }
            session.drainAudio()

            stamp = Time.timestamp()
            audioCycles += stamp - mark
            mark = stamp

            screen.present()

            stamp = Time.timestamp()
            videoCycles += stamp - mark

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

            next += game.emulator.frameMicros
            val now = Time.uptimeMillis() * MICROS_PER_MILLI
            if (now > next) {
                next = now
            } else {
                val waiting = Time.timestamp()
                while (Time.uptimeMillis() * MICROS_PER_MILLI < next) Time.idle()
                idleCycles += Time.timestamp() - waiting
            }
        }

        if (sound) Audio.close()

        var failed = false
        if (session.saveVersion() != written) failed = !store(game, session)

        releaseQuitKey()
        Console.clear()

        val summary = report(game, session, started, frames, emulateCycles, audioCycles, videoCycles, idleCycles)
        if (!failed) return summary

        return "${summary ?: game.name}: could not save to ${GameLibrary.savePath(game)}"
    }

    private fun store(game: Game, session: EmulatorSession): Boolean {
        val data = session.saveData() ?: return true
        return GameLibrary.storeSave(game, data)
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

    private fun report(
        game: Game,
        session: EmulatorSession,
        started: ULong,
        frames: Int,
        emulateCycles: ULong,
        audioCycles: ULong,
        videoCycles: ULong,
        idleCycles: ULong,
    ): String? {
        val elapsed = Time.uptimeMillis() - started
        if (frames == 0) return "${game.name}: exited before drawing a frame"
        if (elapsed == 0UL) return null

        val fps = frames.toULong() * 1000UL / elapsed
        val expected = elapsed * FRAMES_PER_100K_MILLIS / 100000UL
        val speed = if (expected == 0UL) 0UL else frames.toULong() * 100UL / expected
        val detail = session.describe()

        val head = if (detail == null) {
            "${game.name}: $fps fps, $speed% speed"
        } else {
            "${game.name}: $fps fps, $speed% speed ($detail)"
        }

        val counted = emulateCycles + audioCycles + videoCycles + idleCycles
        if (counted == 0UL) return head

        val perFrame = elapsed * 100UL / frames.toULong()

        return "$head\n  cpu ${micros(emulateCycles, counted, perFrame)}" +
            ", video ${micros(videoCycles, counted, perFrame)}" +
            ", audio ${micros(audioCycles, counted, perFrame)}" +
            ", idle ${micros(idleCycles, counted, perFrame)} per frame"
    }

    private fun micros(cycles: ULong, total: ULong, perFrameCentimillis: ULong): String {
        val share = cycles * perFrameCentimillis / total
        return "${share / 100UL}.${(share % 100UL).toString().padStart(2, '0')}ms"
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

    private const val SAVE_POLL_MS = 2000UL
    private const val MAX_SAVE_BYTES = 262144u
}
