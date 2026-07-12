package gba.app

import gba.core.GBA
import gba.core.Keypad
import gba.core.PPU
import kapi.Application
import kapi.Audio
import kapi.Console
import kapi.Gamepad
import kapi.Graphics
import kapi.Input
import kapi.Keys
import kapi.Pad
import kapi.Surface
import kapi.Sys
import kapi.Time
import kapi.ui.Menu
import kapi.ui.MenuItem
import kapi.ui.PixelIcons

object GbaApplication : Application {
    override val name = "gba"
    override val description = "play a game boy advance game"

    private var selected = 0

    override fun run() {
        val surface = Graphics.surface()
        if (surface == null) {
            Console.println("gba: no framebuffer (${Graphics.status()})")
            return
        }

        val library = RomLibrary.list()
        if (library.isEmpty()) {
            Console.println("gba: no .gba roms found in /roms")
            return
        }

        var status: String? = null

        while (true) {
            val rom = choose(surface, library, status) ?: return
            status = play(surface, rom)
            Sys.collectGarbage()
        }
    }

    private fun choose(surface: Surface, roms: List<Rom>, status: String?): Rom? {
        if (selected >= roms.size) selected = 0

        val menu = Menu(
            title = "GBA",
            subtitle = status?.uppercase() ?: "PICK A CARTRIDGE",
            footer = "ENTER START   ESC BACK   IN GAME: Z=A X=B A=L S=R",
        )

        val items = roms.map {
            MenuItem(it.name.uppercase(), "${it.size / 1024UL} KIB", PixelIcons.CARTRIDGE)
        }

        val choice = menu.choose(surface, items, selected) ?: return null
        selected = choice
        return roms[choice]
    }

    private fun play(surface: Surface, rom: Rom): String? {
        Console.clear()
        Console.println("loading ${rom.name}...")

        val image = RomLibrary.load(rom)
        if (image == null) return "cannot read ${rom.path}"
        if (image.size < rom.size.toInt()) return "${rom.name}: short read"

        val console = GBA(image)
        if (!console.cartridge.supported) return "${rom.name}: not a valid rom"

        val bitmap = surface.createHighColorBitmap(PPU.WIDTH.toUInt(), PPU.HEIGHT.toUInt())
        if (bitmap == null) return "cannot create a ${PPU.WIDTH}x${PPU.HEIGHT} buffer"

        val scale = scaleFor(surface)
        val originX = (surface.width - PPU.WIDTH.toUInt() * scale) / 2u
        val originY = (surface.height - PPU.HEIGHT.toUInt() * scale) / 2u

        surface.clear(0x00000000u)
        surface.presentAll()

        val sound = Audio.open()

        Input.drain()
        while (Console.tryReadChar() != null) {
        }

        val started = Time.uptimeMillis()
        var frameCount = 0
        var next = started * MICROS_PER_MILLI

        while (true) {
            Input.poll()

            val padded = Gamepad.available()
            if (padded) Gamepad.poll()

            if (Input.consumePress(Keys.ESC) || Input.isKeyDown(Keys.ESC)) break
            if (padded && Gamepad.isDown(Pad.GUIDE)) break

            console.keypad.setButtons(buttons(padded))

            console.runFrame()
            frameCount++

            if (sound) {
                Audio.write(console.apu.samples, console.apu.frames)
            }
            console.apu.drain()

            console.frame.copyInto(bitmap.pixels)
            bitmap.draw(originX, originY, scale)
            surface.present()

            Input.drain()

            next += FRAME_MICROS
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

        return report(rom, started, frameCount)
    }

    private fun buttons(padded: Boolean): Int {
        var mask = 0

        if (Input.isKeyDown(Keys.Z) || (padded && Gamepad.isDown(Pad.A))) mask = mask or Keypad.A
        if (Input.isKeyDown(Keys.X) || (padded && Gamepad.isDown(Pad.B))) mask = mask or Keypad.B
        if (Input.isKeyDown(Keys.BACKSPACE) || (padded && Gamepad.isDown(Pad.SELECT))) mask = mask or Keypad.SELECT
        if (Input.isKeyDown(Keys.ENTER) || (padded && Gamepad.isDown(Pad.START))) mask = mask or Keypad.START
        if (Input.isKeyDown(Keys.RIGHT) || (padded && Gamepad.isDown(Pad.RIGHT))) mask = mask or Keypad.RIGHT
        if (Input.isKeyDown(Keys.LEFT) || (padded && Gamepad.isDown(Pad.LEFT))) mask = mask or Keypad.LEFT
        if (Input.isKeyDown(Keys.UP) || (padded && Gamepad.isDown(Pad.UP))) mask = mask or Keypad.UP
        if (Input.isKeyDown(Keys.DOWN) || (padded && Gamepad.isDown(Pad.DOWN))) mask = mask or Keypad.DOWN
        if (Input.isKeyDown(Keys.A) || (padded && Gamepad.isDown(Pad.L))) mask = mask or Keypad.L
        if (Input.isKeyDown(Keys.S) || (padded && Gamepad.isDown(Pad.R))) mask = mask or Keypad.R

        return mask
    }

    private fun report(rom: Rom, started: ULong, frameCount: Int): String? {
        val elapsed = Time.uptimeMillis() - started
        if (frameCount == 0) return "${rom.name}: exited before drawing a frame"
        if (elapsed == 0UL) return null

        val fps = frameCount.toULong() * 1000UL / elapsed
        val expected = elapsed * 5973UL / 100000UL
        val speed = if (expected == 0UL) 0UL else frameCount.toULong() * 100UL / expected

        return "${rom.name}: $fps fps, $speed% speed"
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

    private fun scaleFor(surface: Surface): UInt {
        val horizontal = surface.width / PPU.WIDTH.toUInt()
        val vertical = surface.height / PPU.HEIGHT.toUInt()
        val scale = if (horizontal < vertical) horizontal else vertical
        return if (scale < 1u) 1u else scale
    }

    private const val MICROS_PER_MILLI = 1000UL
    private const val FRAME_MICROS = 16743UL
}
