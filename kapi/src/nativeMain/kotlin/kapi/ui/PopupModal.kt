package kapi.ui

import kapi.Input
import kapi.Surface
import kapi.Time

class ModalChoice(
    val label: String,
    val hint: String = "",
    val accent: UInt = Panels.ACCENT,
)

object PopupModal {
    const val CANCELLED = -1
    private const val PENDING = Int.MIN_VALUE

    fun ask(surface: Surface, title: String, message: List<String>, choices: List<ModalChoice>): Int {
        if (choices.isEmpty()) return CANCELLED

        val canvas = Canvas(surface)
        val menu = Menu(Axis.BOTH)
        NavInput.prime()

        var chosen = PENDING
        var dirty = true

        while (true) {
            if (dirty) {
                draw(canvas, title, message, choices, menu.focusedIndex())
                canvas.present()
                dirty = false
            }

            Input.poll()
            val nav = NavInput.read()

            menu.begin()
            for ((index, _) in choices.withIndex()) {
                menu.add(Focusable("choice$index", onActivate = { chosen = index }))
            }
            menu.update(nav, onBack = { chosen = CANCELLED })

            if (chosen != PENDING) return chosen
            if (nav.moved) dirty = true else Time.idle()
        }
    }

    fun confirm(surface: Surface, title: String, message: List<String>, confirm: ModalChoice): Boolean =
        ask(surface, title, message, listOf(confirm, ModalChoice("CANCEL"))) == 0

    private fun draw(canvas: Canvas, title: String, message: List<String>, choices: List<ModalChoice>, selected: Int) {
        val screenWidth = canvas.width
        val screenHeight = canvas.height

        val scale = maxOf(1, screenHeight / 320)
        val small = maxOf(1, scale - 1)
        val line = canvas.glyphHeight * scale
        val pad = maxOf(12, screenHeight / 36)
        val rowHeight = maxOf(30, screenHeight / 14)
        val gap = rowHeight / 6

        var inner = canvas.textWidth(title, scale)
        for (text in message) inner = maxOf(inner, canvas.textWidth(text, small))
        for (choice in choices) {
            inner = maxOf(inner, canvas.textWidth(choice.label, scale) + rowHeight)
            if (choice.hint.isNotEmpty()) inner = maxOf(inner, canvas.textWidth(choice.hint, small) + rowHeight)
        }

        val width = minOf(screenWidth - pad * 2, inner + pad * 2)
        val height = pad * 2 + line + gap * 2 +
            message.size * (canvas.glyphHeight * small + gap) +
            choices.size * (rowHeight + gap)

        val x = (screenWidth - width) / 2
        val y = (screenHeight - height) / 2

        shade(canvas, screenWidth, screenHeight, x, y, width, height)
        canvas.card(x, y, width, height)

        val border = maxOf(2, rowHeight / 22)
        var cursor = y + pad

        canvas.text(x + pad, cursor, title, Panels.INK, scale)
        cursor += line + gap

        canvas.hline(x + pad, cursor, width - pad * 2, Panels.EDGE, border / 2 + 1)
        cursor += gap * 2

        for (text in message) {
            canvas.text(x + pad, cursor, text, Panels.QUIET, small)
            cursor += canvas.glyphHeight * small + gap
        }

        cursor += gap

        for ((index, choice) in choices.withIndex()) {
            val active = index == selected
            canvas.fill(x + pad, cursor, width - pad * 2, rowHeight, if (active) choice.accent else Panels.EDGE)
            canvas.fill(
                x + pad + border,
                cursor + border,
                width - pad * 2 - border * 2,
                rowHeight - border * 2,
                if (active) 0x00FFFFFFu else Panels.CARD,
            )

            val textX = x + pad + border * 4
            if (choice.hint.isEmpty()) {
                canvas.text(textX, cursor + (rowHeight - line) / 2, choice.label, Panels.INK, scale)
            } else {
                canvas.text(textX, cursor + rowHeight / 5, choice.label, Panels.INK, scale)
                canvas.text(textX, cursor + rowHeight * 3 / 5, choice.hint, Panels.QUIET, small)
            }

            cursor += rowHeight + gap
        }
    }

    private fun shade(canvas: Canvas, screenWidth: Int, screenHeight: Int, x: Int, y: Int, width: Int, height: Int) {
        val edge = maxOf(2, height / 90)
        canvas.fill(x - edge, y - edge, width + edge * 2, edge, Panels.OUTLINE)
        canvas.fill(x - edge, y + height, width + edge * 2, edge, Panels.OUTLINE)
        canvas.fill(x - edge, y, edge, height, Panels.OUTLINE)
        canvas.fill(x + width, y, edge, height, Panels.OUTLINE)
    }
}
