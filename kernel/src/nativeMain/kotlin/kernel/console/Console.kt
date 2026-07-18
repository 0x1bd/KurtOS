package kernel.console

import hal.Cpu
import hal.Serial
import kapi.ConsoleBackend
import kapi.Gamepad
import kapi.Keys
import kernel.drivers.Keyboard
import kernel.graphics.Framebuffer
import kernel.graphics.GraphicsService
import kernel.ui.UI

private const val SCALE = 2u
private const val LEADING = 2u
private const val MARGIN = 8u

private const val COLOR_BACKGROUND: UInt = 0x00F6F4EFu
private const val COLOR_TEXT: UInt = 0x001E2430u

private const val SCROLLBACK_LINES = 400

class FramebufferConsole(private val fb: Framebuffer, private val background: UInt = COLOR_BACKGROUND) {
    private val glyphWidth = Font.ADVANCE.toUInt() * SCALE
    private val glyphHeight = (Font.HEIGHT.toUInt() + LEADING) * SCALE

    private val top = 0u

    private val columns = ((fb.width - MARGIN * 2u) / glyphWidth).toInt()
    private val rows = ((fb.height - top - MARGIN * 2u) / glyphHeight).toInt()

    private var column = 0
    private var row = 0

    private val scrollback = ArrayDeque<String>()
    private val partial = StringBuilder()
    private var viewOffset = 0

    fun clear() {
        fb.clear(background)
        column = 0
        row = 0
        scrollback.clear()
        partial.setLength(0)
        viewOffset = 0
        fb.presentAll()
    }

    fun resetCursor() {
        column = 0
        row = 0
    }

    fun putChar(c: Char) {
        if (viewOffset != 0) {
            viewOffset = 0
            redraw()
        }
        when (c) {
            '\n' -> newline()
            '\r' -> {
                column = 0
                partial.setLength(0)
            }
            '\b' -> backspace()
            else -> {
                if (c.code < 0x20) return
                drawGlyph(column, row, c)
                partial.append(c)
                column++
                if (column >= columns) newline()
            }
        }
    }

    fun print(text: String) {
        for (c in text) putChar(c)
        fb.present()
    }

    fun scroll(lines: Int) {
        val maxOffset = maxOf(0, scrollback.size + 1 - rows)
        val next = (viewOffset + lines).coerceIn(0, maxOffset)
        if (next == viewOffset) return
        viewOffset = next
        redraw()
    }

    private fun redraw() {
        fb.clear(background)

        val total = scrollback.size + 1
        val first = maxOf(0, total - rows - viewOffset)
        var line = 0

        for (i in first until total - viewOffset) {
            val text = if (i < scrollback.size) scrollback[i] else partial.toString()
            for (col in text.indices) drawGlyph(col, line, text[col])
            line++
        }

        if (viewOffset == 0) {
            row = if (line > 0) line - 1 else 0
            column = partial.length
        }

        fb.presentAll()
    }

    private fun backspace() {
        if (column == 0) return
        column--
        if (partial.isNotEmpty()) partial.deleteAt(partial.length - 1)
        fillCell(column, row, background)
    }

    private fun newline() {
        scrollback.addLast(partial.toString())
        if (scrollback.size > SCROLLBACK_LINES) scrollback.removeFirst()
        partial.setLength(0)

        column = 0
        row++
        if (row >= rows) {
            fb.scrollRegion(top, glyphHeight, background)
            row = rows - 1
        }
    }

    private fun fillCell(col: Int, line: Int, color: UInt) {
        fb.fillRect(
            MARGIN + col.toUInt() * glyphWidth,
            top + MARGIN + line.toUInt() * glyphHeight,
            glyphWidth,
            glyphHeight,
            color,
        )
    }

    private fun drawGlyph(col: Int, line: Int, c: Char) {
        fillCell(col, line, background)

        val glyph = Font.glyph(c)
        val originX = MARGIN + col.toUInt() * glyphWidth
        val originY = top + MARGIN + line.toUInt() * glyphHeight

        for (gy in 0 until Font.HEIGHT) {
            val bits = glyph[gy].toInt() and 0xFF
            for (gx in 0 until Font.WIDTH) {
                if ((bits shr (7 - gx)) and 1 == 0) continue
                fb.fillRect(
                    originX + gx.toUInt() * SCALE,
                    originY + gy.toUInt() * SCALE,
                    SCALE,
                    SCALE,
                    COLOR_TEXT,
                )
            }
        }
    }
}

object SystemConsole : ConsoleBackend {
    private const val SCROLL_STEP = 4

    private var fbConsole: FramebufferConsole? = null

    fun attachFramebuffer() {
        if (fbConsole != null) return
        val fb = GraphicsService.framebuffer() ?: return
        val console = FramebufferConsole(fb)
        console.clear()
        fbConsole = console
    }

    fun reattachFramebuffer(background: UInt) {
        val fb = GraphicsService.framebuffer() ?: return
        fbConsole = FramebufferConsole(fb, background).also { it.resetCursor() }
    }

    override fun print(text: String) {
        Serial.print(text)
        fbConsole?.print(text)
    }

    override fun println(text: String) {
        print(text)
        print("\n")
    }

    override fun clear() {
        Serial.print("\u001B[2J\u001B[H")
        fbConsole?.clear()
    }

    override fun tryReadChar(): Char? {
        Keyboard.poll()
        pumpScroll()
        Keyboard.nextChar()?.let { return it }
        return Serial.tryReadChar()
    }

    private fun pumpScroll() {
        if (!Keyboard.shiftDown) return
        if (Keyboard.consumePress(Keys.UP)) fbConsole?.scroll(SCROLL_STEP)
        if (Keyboard.consumePress(Keys.DOWN)) fbConsole?.scroll(-SCROLL_STEP)
    }

    override fun readLine(): String {
        val buffer = StringBuilder()

        while (true) {
            val c = readChar()
            when {
                c == '\n' || c == '\r' -> {
                    print("\n")
                    return buffer.toString()
                }

                c == '\b' || c.code == 0x7F -> {
                    if (buffer.isNotEmpty()) {
                        buffer.deleteAt(buffer.length - 1)
                        print("\b")
                    }
                }

                c.code < 0x20 -> Unit

                else -> {
                    buffer.append(c)
                    print(c.toString())
                }
            }
        }
    }

    private fun readChar(): Char {
        while (true) {
            tryReadChar()?.let { return it }
            Cpu.waitForInterrupt()
            Gamepad.pump()
            UI.tick()
        }
    }
}
