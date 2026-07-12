package kapi.ui

import kapi.Console
import kapi.Gamepad
import kapi.Input
import kapi.Keys
import kapi.Pad
import kapi.Surface
import kapi.Time

class MenuItem(
    val label: String,
    val sublabel: String? = null,
    val icon: PixelIcons.Icon? = null,
    val hotkey: UShort? = null,
)

class Menu(
    private val title: String,
    private val subtitle: String? = null,
    private val footer: String? = null,
) {
    private val padPrevious = BooleanArray(Pad.COUNT)

    fun choose(surface: Surface, items: List<MenuItem>, initial: Int = 0): Int? {
        if (items.isEmpty()) return null

        var selected = initial.coerceIn(0, items.size - 1)
        val sink = SurfaceSink(surface)

        flush()
        render(surface, sink, items, selected)

        while (true) {
            Input.poll()

            for (i in items.indices) {
                val hotkey = items[i].hotkey ?: continue
                if (Input.consumePress(hotkey)) return i
            }

            when (action()) {
                UP -> {
                    selected = (selected + items.size - 1) % items.size
                    render(surface, sink, items, selected)
                }

                DOWN -> {
                    selected = (selected + 1) % items.size
                    render(surface, sink, items, selected)
                }

                SELECT -> return selected

                QUIT -> return null
            }

            Time.idle()
        }
    }

    private fun action(): Int {
        if (Input.consumePress(Keys.UP)) return UP
        if (Input.consumePress(Keys.DOWN)) return DOWN
        if (Input.consumePress(Keys.ENTER)) return SELECT
        if (Input.consumePress(Keys.SPACE)) return SELECT
        if (Input.consumePress(Keys.ESC)) return QUIT

        val pad = padAction()
        if (pad != NONE) return pad

        val character = Console.tryReadChar() ?: return NONE
        return when (character.lowercaseChar()) {
            'w', 'k' -> UP
            's', 'j' -> DOWN
            '\n', '\r', ' ' -> SELECT
            'q', ESCAPE -> QUIT
            else -> NONE
        }
    }

    private fun padAction(): Int {
        if (!Gamepad.available()) return NONE
        Gamepad.poll()

        var action = NONE

        for (button in 0 until Pad.COUNT) {
            val down = Gamepad.isDown(button)

            if (down && !padPrevious[button] && action == NONE) {
                action = when (button) {
                    Pad.UP -> UP
                    Pad.DOWN -> DOWN
                    Pad.A, Pad.START -> SELECT
                    Pad.B -> QUIT
                    else -> NONE
                }
            }

            padPrevious[button] = down
        }

        return action
    }

    private fun flush() {
        Input.poll()
        Input.drain()
        while (Console.tryReadChar() != null) {
        }

        if (!Gamepad.available()) return

        Gamepad.poll()
        for (button in 0 until Pad.COUNT) padPrevious[button] = Gamepad.isDown(button)
    }

    private fun render(surface: Surface, sink: PixelSink, items: List<MenuItem>, selected: Int) {
        val width = surface.width.toInt()
        val height = surface.height.toInt()

        surface.clear(BACKGROUND)

        val titleX = (width - PixelFont.textWidth(title, TITLE_SCALE)) / 2
        PixelFont.draw(sink, titleX, TITLE_Y, title, Panels.GOLD, TITLE_SCALE, Panels.OUTLINE)

        if (subtitle != null) {
            val subX = (width - PixelFont.textWidth(subtitle, TEXT_SCALE)) / 2
            PixelFont.draw(sink, subX, SUBTITLE_Y, subtitle, Panels.SUBTITLE, TEXT_SCALE, Panels.OUTLINE)
        }

        val rowWidth = minOf(width - 200, ROW_MAX_WIDTH)
        val rowX = (width - rowWidth) / 2

        val visible = visibleRange(items.size, selected, height)

        for ((slot, index) in visible.withIndex()) {
            val item = items[index]
            val rowY = ITEMS_Y + slot * (ROW_HEIGHT + ROW_GAP)
            val isSelected = index == selected

            if (isSelected) {
                Panels.frame(sink, rowX, rowY, rowWidth, ROW_HEIGHT)
                PixelIcons.MUSHROOM.draw(sink, rowX - 44, rowY + (ROW_HEIGHT - 22) / 2, 2)
            } else {
                sink.fill(rowX, rowY, rowWidth, ROW_HEIGHT, ROW_FILL)
            }

            val labelColor = if (isSelected) Panels.TITLE else Panels.DIM
            var textX = rowX + 24

            val icon = item.icon
            if (icon != null) {
                icon.draw(sink, textX, rowY + (ROW_HEIGHT - icon.height * 2) / 2, 2)
                textX += icon.width * 2 + 16
            }

            val sublabel = item.sublabel
            val sublabelWidth = if (sublabel != null) PixelFont.textWidth(sublabel, TEXT_SCALE) + 32 else 0

            val labelRoom = (rowX + rowWidth - 24 - sublabelWidth - textX) / (PixelFont.WIDTH * TEXT_SCALE)
            val label = if (item.label.length > labelRoom) item.label.take(maxOf(0, labelRoom - 1)) + "." else item.label

            PixelFont.draw(sink, textX, rowY + (ROW_HEIGHT - 16) / 2, label, labelColor, TEXT_SCALE, Panels.OUTLINE)

            if (sublabel != null) {
                val subX = rowX + rowWidth - PixelFont.textWidth(sublabel, TEXT_SCALE) - 24
                PixelFont.draw(sink, subX, rowY + (ROW_HEIGHT - 16) / 2, sublabel, Panels.DIM, TEXT_SCALE, Panels.OUTLINE)
            }
        }

        if (footer != null) {
            val footerX = (width - PixelFont.textWidth(footer, TEXT_SCALE)) / 2
            PixelFont.draw(sink, footerX, height - FOOTER_MARGIN, footer, Panels.DIM, TEXT_SCALE, Panels.OUTLINE)
        }

        surface.present()
    }

    private fun visibleRange(count: Int, selected: Int, height: Int): IntRange {
        val capacity = maxOf(1, (height - ITEMS_Y - FOOTER_MARGIN - 16) / (ROW_HEIGHT + ROW_GAP))
        if (count <= capacity) return 0 until count

        var first = selected - capacity / 2
        if (first < 0) first = 0
        if (first + capacity > count) first = count - capacity

        return first until first + capacity
    }

    private companion object {
        const val NONE = -1
        const val UP = 0
        const val DOWN = 1
        const val SELECT = 2
        const val QUIT = 3

        const val ESCAPE = '\u001B'

        const val BACKGROUND: UInt = 0x000B0F14u
        const val ROW_FILL: UInt = 0x00121A26u

        const val TITLE_SCALE = 4
        const val TEXT_SCALE = 2

        const val TITLE_Y = 48
        const val SUBTITLE_Y = 96
        const val ITEMS_Y = 150
        const val ROW_HEIGHT = 60
        const val ROW_GAP = 14
        const val ROW_MAX_WIDTH = 680
        const val FOOTER_MARGIN = 48
    }
}
