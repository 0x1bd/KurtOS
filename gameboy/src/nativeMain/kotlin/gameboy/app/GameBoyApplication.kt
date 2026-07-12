package gameboy.app

import gameboy.core.GameBoy
import gameboy.core.Joypad
import gameboy.core.PPU
import kapi.Application
import kapi.Console
import kapi.Graphics
import kapi.Input
import kapi.Keys
import kapi.Surface
import kapi.Sys
import kapi.Time

object GameBoyApplication : Application {
    override val name = "gameboy"
    override val description = "play a game boy game"

    private val shades = intArrayOf(0x9BBC0F, 0x8BAC0F, 0x306230, 0x0F380F)

    private val buttons = intArrayOf(
        Joypad.RIGHT, Joypad.LEFT, Joypad.UP, Joypad.DOWN,
        Joypad.A, Joypad.B, Joypad.SELECT, Joypad.START,
    )

    private val keys = ushortArrayOf(
        Keys.RIGHT, Keys.LEFT, Keys.UP, Keys.DOWN,
        Keys.Z, Keys.X, Keys.BACKSPACE, Keys.ENTER,
    )

    override fun run() {
        val surface = Graphics.surface()
        if (surface == null) {
            Console.println("gameboy: no framebuffer (${Graphics.status()})")
            return
        }

        val library = RomLibrary.list()
        if (library.isEmpty()) {
            Console.println("gameboy: no roms found in /roms")
            return
        }

        var status: String? = null

        while (true) {
            val rom = Menu.choose(library, status) ?: return
            status = play(surface, rom)
            Sys.collectGarbage()
        }
    }

    private fun play(surface: Surface, rom: Rom): String? {
        Console.clear()
        Console.println("loading ${rom.name}...")

        val image = RomLibrary.load(rom)
        if (image == null) {
            Console.println("gameboy: cannot read ${rom.path}")
            waitForKey()
            return null
        }

        val console = GameBoy(image, shades)
        if (!console.cartridge.supported) {
            Console.println("gameboy: ${rom.name} is not a valid rom")
            waitForKey()
            return null
        }

        val bitmap = surface.createBitmap(
            PPU.WIDTH.toUInt(),
            PPU.HEIGHT.toUInt(),
            GameBoy.PALETTE_SIZE,
        )
        if (bitmap == null) {
            Console.println("gameboy: cannot create a ${PPU.WIDTH}x${PPU.HEIGHT} bitmap")
            waitForKey()
            return null
        }

        var paletteVersion = -1

        val scale = scaleFor(surface)
        val originX = (surface.width - PPU.WIDTH.toUInt() * scale) / 2u
        val originY = (surface.height - PPU.HEIGHT.toUInt() * scale) / 2u

        surface.clear(0x00000000u)
        surface.presentAll()

        Input.drain()
        while (Console.tryReadChar() != null) {
        }

        val started = Time.uptimeMillis()
        var frames = 0
        var next = started * MICROS_PER_MILLI

        while (true) {
            Input.poll()
            if (Input.isKeyDown(Keys.ESC) || Input.consumePress(Keys.ESC)) break

            for (i in buttons.indices) {
                console.joypad.setButton(buttons[i], Input.isKeyDown(keys[i]))
            }

            console.runFrame()
            frames++

            if (console.paletteVersion != paletteVersion) {
                paletteVersion = console.paletteVersion
                for (i in 0 until GameBoy.PALETTE_SIZE) {
                    bitmap.setPalette(i, console.palette[i].toUInt())
                }
            }

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

        Input.drain()
        Console.clear()

        return report(rom, console, started, frames)
    }

    private fun report(rom: Rom, console: GameBoy, started: ULong, frames: Int): String? {
        val elapsed = Time.uptimeMillis() - started
        if (elapsed == 0UL || frames == 0) return null

        val fps = frames.toULong() * 1000UL / elapsed
        val expected = elapsed * FRAMES_PER_100K_MILLIS / 100000UL
        val speed = if (expected == 0UL) 0UL else frames.toULong() * 100UL / expected
        val mode = if (console.color) "cgb" else "dmg"

        return "${rom.name}: $fps fps, $speed% speed ($mode, ${console.cartridge.kindName})"
    }

    private fun scaleFor(surface: Surface): UInt {
        val horizontal = surface.width / PPU.WIDTH.toUInt()
        val vertical = surface.height / PPU.HEIGHT.toUInt()
        val scale = if (horizontal < vertical) horizontal else vertical
        return if (scale < 1u) 1u else scale
    }

    private fun waitForKey() {
        Console.println("press enter to continue")
        Console.readLine()
    }

    private const val MICROS_PER_MILLI = 1000UL
    private const val FRAME_MICROS = 16742UL
    private const val FRAMES_PER_100K_MILLIS = 5973UL
}
