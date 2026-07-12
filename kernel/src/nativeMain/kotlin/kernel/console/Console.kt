package kernel.console

import hal.Cpu
import hal.Serial
import kapi.ConsoleBackend
import kapi.Gamepad
import kernel.drivers.Keyboard
import kernel.graphics.Framebuffer
import kernel.graphics.GraphicsService
import kernel.ui.HUD
import kernel.ui.UI

private const val SCALE = 2u
private const val LEADING = 2u
private const val MARGIN = 8u

private const val COLOR_BACKGROUND: UInt = 0x000B0F14u
private const val COLOR_TEXT: UInt = 0x00C8D3DEu

class FramebufferConsole(private val fb: Framebuffer, private val background: UInt = COLOR_BACKGROUND) {
    private val glyphWidth = Font.ADVANCE.toUInt() * SCALE
    private val glyphHeight = (Font.HEIGHT.toUInt() + LEADING) * SCALE

    private val top = HUD.RESERVED

    private val columns = ((fb.width - MARGIN * 2u) / glyphWidth).toInt()
    private val rows = ((fb.height - top - MARGIN * 2u) / glyphHeight).toInt()

    private var column = 0
    private var row = 0

    fun clear() {
        fb.clear(background)
        column = 0
        row = 0
        HUD.draw()
        fb.presentAll()
    }

    fun resetCursor() {
        column = 0
        row = 0
    }

    fun putChar(c: Char) {
        when (c) {
            '\n' -> newline()
            '\r' -> column = 0
            '\b' -> backspace()
            else -> {
                if (c.code < 0x20) return
                drawGlyph(c)
                column++
                if (column >= columns) newline()
            }
        }
    }

    fun print(text: String) {
        for (c in text) putChar(c)
        fb.present()
    }

    private fun backspace() {
        if (column == 0) return
        column--
        fillCell(column, row, background)
    }

    private fun newline() {
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

    private fun drawGlyph(c: Char) {
        fillCell(column, row, background)

        val glyph = Font.glyph(c)
        val originX = MARGIN + column.toUInt() * glyphWidth
        val originY = top + MARGIN + row.toUInt() * glyphHeight

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
        Keyboard.nextChar()?.let { return it }
        return Serial.tryReadChar()
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
