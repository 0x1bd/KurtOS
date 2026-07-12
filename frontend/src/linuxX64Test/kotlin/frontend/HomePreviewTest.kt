package frontend

import kapi.HighColorBitmap
import kapi.IndexedBitmap
import kapi.Surface
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite
import platform.posix.getenv
import kotlin.test.Test

class Canvas(override val width: UInt, override val height: UInt) : Surface {
    val pixels = IntArray(width.toInt() * height.toInt())

    override fun clear(color: UInt) = pixels.fill(color.toInt())

    override fun fillRect(x: UInt, y: UInt, w: UInt, h: UInt, color: UInt) {
        val x0 = x.toInt()
        val y0 = y.toInt()

        for (row in y0 until minOf(y0 + h.toInt(), height.toInt())) {
            if (row < 0) continue
            for (col in x0 until minOf(x0 + w.toInt(), width.toInt())) {
                if (col < 0) continue
                pixels[row * width.toInt() + col] = color.toInt()
            }
        }
    }

    override fun createBitmap(width: UInt, height: UInt, paletteSize: Int): IndexedBitmap? = null
    override fun createHighColorBitmap(width: UInt, height: UInt): HighColorBitmap? = null
    override fun present() {}
    override fun presentAll() {}
}

class HomePreviewTest {
    @OptIn(ExperimentalForeignApi::class)
    private fun dump(canvas: Canvas, name: String) {
        val out = getenv("KURTOS_DUMP")?.toKString() ?: return
        val handle = fopen("$out/$name.ppm", "wb") ?: return

        val header = "P6\n${canvas.width} ${canvas.height}\n255\n"
        val bytes = ByteArray(header.length + canvas.pixels.size * 3)
        for (i in header.indices) bytes[i] = header[i].code.toByte()

        var at = header.length
        for (p in canvas.pixels) {
            bytes[at++] = ((p shr 16) and 0xFF).toByte()
            bytes[at++] = ((p shr 8) and 0xFF).toByte()
            bytes[at++] = (p and 0xFF).toByte()
        }

        bytes.usePinned { fwrite(it.addressOf(0), 1u, bytes.size.toULong(), handle) }
        fclose(handle)
    }

    private fun games(): List<Game> = listOf(
        Game("SUPER MARIO KART", "/roms/smk.sfc", 524288UL, Emulators.all.first { it.id == "snes" }),
        Game("ZELDA LINK TO THE PAST", "/roms/alttp.sfc", 1048576UL, Emulators.all.first { it.id == "snes" }),
        Game("SUPER METROID", "/roms/sm.smc", 3145728UL, Emulators.all.first { it.id == "snes" }),
        Game("TETRIS", "/roms/tetris.gb", 32768UL, Emulators.all.first { it.id == "gameboy" }),
        Game("POKEMON CRYSTAL", "/roms/crystal.gbc", 2097152UL, Emulators.all.first { it.id == "gameboy" }),
        Game("POKEMON EMERALD", "/roms/emerald.gba", 16777216UL, Emulators.all.first { it.id == "gba" }),
    )

    @OptIn(ExperimentalForeignApi::class)
    @Test
    fun preview() {
        if (getenv("KURTOS_DUMP") == null) return

        for ((name, screen) in listOf(
            "home" to Home.PREVIEW_HOME,
            "library" to Home.PREVIEW_LIBRARY,
            "settings" to Home.PREVIEW_SETTINGS,
            "system" to Home.PREVIEW_SYSTEM,
        )) {
            val canvas = Canvas(1280u, 800u)
            Home.preview(canvas, screen, 0, games(), "12:00 PM", 40)
            dump(canvas, "ui-$name")
        }

        val gb = Canvas(1280u, 800u)
        Home.preview(gb, Home.PREVIEW_HOME, 1, games(), "12:00 PM", 40)
        dump(gb, "ui-home-gb")
    }
}
