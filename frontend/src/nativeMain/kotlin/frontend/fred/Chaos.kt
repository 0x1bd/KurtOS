package frontend.fred

import kapi.Time
import kapi.ui.Canvas
import kapi.ui.Icons
import kapi.ui.Panels

object Chaos {
    private const val TINT: UInt = 0x00FF3EA5u
    private const val MATRIX: UInt = 0x00FF7AC6u

    fun level(): Int {
        val n = Fred.count()
        return when {
            n <= 1 -> 0
            n <= 3 -> 1
            n <= 7 -> 2
            n <= 15 -> 3
            n <= 31 -> 4
            n <= 63 -> 5
            n <= 127 -> 6
            else -> 7
        }
    }

    fun blink(periodMs: ULong): Boolean = (Time.uptimeMillis() / periodMs) % 2UL == 0UL

    fun bandTop(navTop: Int): Int {
        val lvl = level()
        if (lvl < 2) return navTop
        val spread = (lvl - 1).coerceIn(1, 6)
        return (navTop - navTop * spread / 6).coerceAtLeast(0)
    }

    fun shakeX(): Int = shake(0x9E3779B9u)
    fun shakeY(): Int = shake(0x85EBCA77u)

    private fun shake(salt: UInt): Int {
        val lvl = level()
        if (lvl < 4) return 0
        val amp = (lvl - 3) * 2
        val h = (Time.uptimeMillis().toUInt() * 2654435761u xor salt)
        return (h % (2u * amp.toUInt() + 1u)).toInt() - amp
    }

    fun paper(default: UInt): UInt = mix(default, TINT, (level() - 3), 16)
    fun stripe(default: UInt): UInt = mix(default, TINT, (level() - 3), 12)

    fun title(default: String): String = FredText.title(default, level(), blink(450u))
    fun welcome(default: String): String = FredText.welcome(default, level())
    fun card(default: String): String = FredText.card(default, level())
    fun settingsLabel(): String = FredText.settingsLabel(level())

    fun clock(default: String): String {
        val lvl = level()
        if (lvl < 3) return default
        if (lvl >= 6) return "=^.^="
        return if (blink(700u)) "=^.^=" else default
    }

    fun overlay(canvas: Canvas, width: Int, height: Int, navTop: Int) {
        val lvl = level()
        if (lvl >= 5) banner(canvas, width, navTop)
        if (lvl >= 5) rain(canvas, width, height)
        if (lvl >= 6) counter(canvas, width)
    }

    private fun banner(canvas: Canvas, width: Int, navTop: Int) {
        val scale = maxOf(1, width / 320)
        val h = canvas.glyphHeight * scale + 6
        val y = navTop - h
        canvas.fill(0, y, width, h, TINT)

        val text = "  *** FRED CONTAINMENT BREACH ***  DO NOT RESIST  ***  THIS IS NORMAL  ***  "
        val textW = canvas.textWidth(text, scale)
        if (textW <= 0) return
        val shift = ((Time.uptimeMillis() / 16UL) % textW.toULong()).toInt()
        var x = -shift
        while (x < width) {
            canvas.text(x, y + 3, text, Panels.BAR_TEXT, scale)
            x += textW
        }
    }

    private fun rain(canvas: Canvas, width: Int, height: Int) {
        val icon = Fred.face()
        val scale = maxOf(1, height / 300)
        val stride = icon.width * scale + width / 24
        if (stride <= 0) return
        val columns = maxOf(1, width / stride)
        val now = Time.uptimeMillis()
        val span = (height + icon.height * scale).toULong()

        for (c in 0 until columns) {
            val seed = (c.toUInt() * 2246822519u) xor 0x1234567u
            val speed = (40u + (seed % 90u)).toULong()
            val phase = (seed % 4000u).toULong()
            val y = (((now * speed) / 1000UL + phase) % span).toInt() - icon.height * scale
            val x = c * stride + (seed % (stride / 2 + 1).toUInt()).toInt()
            canvas.icon(icon, x, y, scale, (seed and 1u) == 1u)
        }
    }

    private fun counter(canvas: Canvas, width: Int) {
        val scale = maxOf(1, width / 360)
        val fake = Fred.count().toLong() * 68101 + (Time.uptimeMillis() / 3UL).toLong()
        val text = "FREDS: $fake"
        val tw = canvas.textWidth(text, scale)
        canvas.fill(width - tw - 12, 4, tw + 12, canvas.glyphHeight * scale + 8, TINT)
        canvas.text(width - tw - 6, 8, text, Panels.BAR_TEXT, scale)
    }

    private fun mix(base: UInt, target: UInt, num: Int, den: Int): UInt {
        if (num <= 0) return base
        val n = minOf(num, den)
        val br = ((base shr 16) and 0xFFu).toInt()
        val bg = ((base shr 8) and 0xFFu).toInt()
        val bb = (base and 0xFFu).toInt()
        val tr = ((target shr 16) and 0xFFu).toInt()
        val tg = ((target shr 8) and 0xFFu).toInt()
        val tb = (target and 0xFFu).toInt()
        val r = br + (tr - br) * n / den
        val g = bg + (tg - bg) * n / den
        val b = bb + (tb - bb) * n / den
        return ((r shl 16) or (g shl 8) or b).toUInt()
    }
}
