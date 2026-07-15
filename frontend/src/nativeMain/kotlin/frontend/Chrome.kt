package frontend

import kapi.Time
import kapi.ui.Canvas
import kapi.ui.Panels
import kapi.ui.PixelIcons

object Chrome {
    class Hint(val icon: PixelIcons.Icon?, val glyph: String?, val color: UInt, val label: String)

    fun barHeight(height: Int): Int = maxOf(40, height * 10 / 108)

    fun clockText(): String {
        val now = Time.now() ?: return uptimeText()

        val hour = now.hour % 12
        val display = if (hour == 0) 12 else hour
        val suffix = if (now.hour < 12) "AM" else "PM"

        return "$display:${pad(now.minute)} $suffix"
    }

    private fun uptimeText(): String {
        val seconds = (Time.uptimeMillis() / 1000UL).toInt()
        return "${pad(seconds / 3600)}:${pad((seconds / 60) % 60)}"
    }

    private fun pad(value: Int): String = value.toString().padStart(2, '0')

    fun drawStatusBar(canvas: Canvas, width: Int, barHeight: Int, title: String, clock: String) {
        canvas.fill(0, 0, width, barHeight, Panels.BAR)

        val scale = maxOf(1, barHeight / 24)
        val pad = barHeight / 4
        val gap = pad / 2
        val textY = (barHeight - canvas.glyphHeight * scale) / 2

        val logoScale = maxOf(1, (barHeight - pad * 2) / HomeIcons.LOGO.height)
        canvas.icon(HomeIcons.LOGO, pad, (barHeight - HomeIcons.LOGO.height * logoScale) / 2, logoScale)

        val nameX = pad + HomeIcons.LOGO.width * logoScale + pad
        canvas.text(nameX, textY, title, Panels.BAR_TEXT, scale)

        val clockX = (width - canvas.textWidth(clock, scale)) / 2
        canvas.text(clockX, textY, clock, Panels.BAR_TEXT, scale)

        val percent = "${Settings.volume}%"
        val battery = "100%"

        val batteryScale = maxOf(1, (barHeight - pad * 2) / HomeIcons.BATTERY.height)
        val batteryWidth = HomeIcons.BATTERY.width * batteryScale
        val batteryY = (barHeight - HomeIcons.BATTERY.height * batteryScale) / 2
        val batteryX = width - pad - canvas.textWidth(battery, scale) - gap - batteryWidth

        canvas.icon(HomeIcons.BATTERY, batteryX, batteryY, batteryScale)
        canvas.fill(
            batteryX + batteryScale * 2,
            batteryY + batteryScale * 2,
            batteryScale * 9,
            batteryScale * 4,
            Panels.GREEN,
        )
        canvas.text(batteryX + batteryWidth + gap, textY, battery, Panels.BAR_TEXT, scale)

        val speaker = if (Settings.muted) PixelIcons.SPEAKER_MUTE else PixelIcons.SPEAKER
        val speakerScale = maxOf(1, (barHeight - pad * 2) / speaker.height)
        val cell = maxOf(3, barHeight / 14)
        val meterWidth = METER_CELLS * (cell + cell / 2)

        val volumeWidth = speaker.width * speakerScale + gap + meterWidth + gap + canvas.textWidth(percent, scale)
        val volumeX = batteryX - pad * 2 - volumeWidth

        canvas.icon(speaker, volumeX, (barHeight - speaker.height * speakerScale) / 2, speakerScale)

        val meterX = volumeX + speaker.width * speakerScale + gap
        val filled = if (Settings.muted) 0 else (Settings.volume + 9) / 10

        for (i in 0 until METER_CELLS) {
            canvas.fill(
                meterX + i * (cell + cell / 2),
                (barHeight - cell * 2) / 2,
                cell,
                cell * 2,
                if (i < filled) Panels.GREEN else Panels.METER_OFF,
            )
        }

        canvas.text(meterX + meterWidth + gap, textY, percent, Panels.BAR_TEXT, scale)
    }

    fun drawNavBar(canvas: Canvas, width: Int, height: Int, barHeight: Int, hints: List<Hint>) {
        val y = height - barHeight
        canvas.fill(0, y, width, barHeight, Panels.BAR)

        if (hints.isEmpty()) return

        val scale = maxOf(1, barHeight / 30)
        val iconScale = maxOf(1, barHeight / 20)
        val glyphRadius = HomeIcons.DPAD.height * iconScale / 2
        val gap = barHeight / 3

        val slot = width / hints.size

        for ((index, hint) in hints.withIndex()) {
            val markWidth = when {
                hint.icon != null -> hint.icon.width * iconScale
                else -> glyphRadius * 2
            }

            val content = markWidth + gap + canvas.textWidth(hint.label, scale)
            val x = index * slot + (slot - content) / 2
            val centre = y + barHeight / 2

            if (hint.icon != null) {
                canvas.icon(hint.icon, x, centre - hint.icon.height * iconScale / 2, iconScale)
            } else if (hint.glyph != null) {
                drawGlyphButton(canvas, x + glyphRadius, centre, glyphRadius, hint.glyph, hint.color, scale)
            }

            canvas.text(
                x + markWidth + gap,
                centre - canvas.glyphHeight * scale / 2,
                hint.label,
                Panels.BAR_TEXT,
                scale,
            )
        }
    }

    private fun drawGlyphButton(
        canvas: Canvas,
        cx: Int,
        cy: Int,
        radius: Int,
        glyph: String,
        color: UInt,
        scale: Int,
    ) {
        drawRing(canvas, cx, cy, radius, color)

        canvas.text(
            cx - (GLYPH_INK * scale) / 2,
            cy - (GLYPH_INK * scale) / 2,
            glyph,
            color,
            scale,
        )
    }

    private fun drawRing(canvas: Canvas, cx: Int, cy: Int, radius: Int, color: UInt) {
        val thickness = maxOf(2, radius / 5)
        val inner = radius - thickness

        for (dy in -radius..radius) {
            val span = isqrt(radius * radius - dy * dy)
            val hollow = if (dy * dy < inner * inner) isqrt(inner * inner - dy * dy) else 0

            if (hollow == 0) {
                canvas.fill(cx - span, cy + dy, span * 2, 1, color)
            } else {
                canvas.fill(cx - span, cy + dy, span - hollow, 1, color)
                canvas.fill(cx + hollow, cy + dy, span - hollow, 1, color)
            }
        }
    }

    private fun isqrt(value: Int): Int {
        if (value <= 0) return 0

        var root = 0
        while ((root + 1) * (root + 1) <= value) root++

        return root
    }

    private const val GLYPH_INK = 6
    private const val METER_CELLS = 10
}
