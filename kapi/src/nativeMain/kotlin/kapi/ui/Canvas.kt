package kapi.ui

import kapi.Surface

class Canvas(
    private val sink: PixelSink,
    val width: Int,
    val height: Int,
    private val surface: Surface? = null,
) {
    constructor(surface: Surface) : this(SurfaceSink(surface), surface.width.toInt(), surface.height.toInt(), surface)

    val glyphWidth: Int get() = PixelFont.WIDTH
    val glyphHeight: Int get() = PixelFont.HEIGHT

    fun clear(color: UInt) {
        val target = surface
        if (target != null) target.clear(color) else fill(0, 0, width, height, color)
    }

    fun fill(x: Int, y: Int, width: Int, height: Int, color: UInt) = sink.fill(x, y, width, height, color)

    fun rect(x: Int, y: Int, width: Int, height: Int, color: UInt, thickness: Int = 1) {
        fill(x, y, width, thickness, color)
        fill(x, y + height - thickness, width, thickness, color)
        fill(x, y, thickness, height, color)
        fill(x + width - thickness, y, thickness, height, color)
    }

    fun hline(x: Int, y: Int, width: Int, color: UInt, thickness: Int = 1) = fill(x, y, width, thickness, color)

    fun text(x: Int, y: Int, text: String, color: UInt, scale: Int = 1, shadow: UInt = PixelFont.NO_SHADOW) =
        PixelFont.draw(sink, x, y, text, color, scale, shadow)

    fun textWidth(text: String, scale: Int): Int = PixelFont.textWidth(text, scale)

    fun icon(icon: PixelIcons.Icon, x: Int, y: Int, scale: Int) = icon.draw(sink, x, y, scale)

    fun card(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        fill: UInt = Panels.CARD,
        edge: UInt = Panels.EDGE,
        border: Int = 4,
    ) = Panels.card(sink, x, y, width, height, fill, edge, border)

    fun frame(x: Int, y: Int, width: Int, height: Int, fill: UInt = Panels.FILL) =
        Panels.frame(sink, x, y, width, height, fill)

    fun present() {
        surface?.present()
    }

    fun presentAll() {
        surface?.presentAll()
    }
}
