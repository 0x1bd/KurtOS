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

    private val shades = uintArrayOf(0x009BBC0Fu, 0x008BAC0Fu, 0x00306230u, 0x000F380Fu)

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

        while (true) {
            val rom = Menu.choose(library) ?: return
            play(surface, rom)
            Sys.collectGarbage()
        }
    }

    private fun play(surface: Surface, rom: Rom) {
        Console.clear()
        Console.println("loading ${rom.name}...")

        val image = RomLibrary.load(rom)
        if (image == null) {
            Console.println("gameboy: cannot read ${rom.path}")
            waitForKey()
            return
        }

        val console = GameBoy(image)
        if (!console.cartridge.supported) {
            Console.println("gameboy: ${rom.name} is not a valid rom")
            waitForKey()
            return
        }

        val bitmap = surface.createBitmap(
            PPU.WIDTH.toUInt(),
            PPU.HEIGHT.toUInt(),
            shades.size,
        )
        if (bitmap == null) {
            Console.println("gameboy: cannot create a ${PPU.WIDTH}x${PPU.HEIGHT} bitmap")
            waitForKey()
            return
        }

        for (i in shades.indices) bitmap.setPalette(i, shades[i])

        val scale = scaleFor(surface)
        val originX = (surface.width - PPU.WIDTH.toUInt() * scale) / 2u
        val originY = (surface.height - PPU.HEIGHT.toUInt() * scale) / 2u

        surface.clear(0x00000000u)
        surface.presentAll()

        Input.drain()
        while (Console.tryReadChar() != null) {
        }

        var next = Time.uptimeMillis()

        while (true) {
            Input.poll()
            if (Input.isKeyDown(Keys.ESC)) break

            for (i in buttons.indices) {
                console.joypad.setButton(buttons[i], Input.isKeyDown(keys[i]))
            }

            console.runFrame()

            console.frame.copyInto(bitmap.pixels)
            bitmap.draw(originX, originY, scale)
            surface.present()

            Input.drain()

            next += FRAME_MILLIS
            val now = Time.uptimeMillis()
            if (now > next) {
                next = now
            } else {
                while (Time.uptimeMillis() < next) Time.idle()
            }
        }

        Input.drain()
        Console.clear()
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

    private const val FRAME_MILLIS = 17UL
}
