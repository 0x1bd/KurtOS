package kapi.ui

import kapi.Surface

interface PixelSink {
    fun fill(x: Int, y: Int, width: Int, height: Int, color: UInt)
}

class SurfaceSink(private val surface: Surface) : PixelSink {
    override fun fill(x: Int, y: Int, width: Int, height: Int, color: UInt) {
        if (x < 0 || y < 0 || width <= 0 || height <= 0) return
        surface.fillRect(x.toUInt(), y.toUInt(), width.toUInt(), height.toUInt(), color)
    }
}

object Panels {
    const val OUTLINE: UInt = 0x00101018u
    const val BORDER: UInt = 0x00F8D878u
    const val FILL: UInt = 0x00182048u
    const val TITLE: UInt = 0x00F8F8F8u
    const val SUBTITLE: UInt = 0x00B8C0D8u
    const val GOLD: UInt = 0x00F8B800u
    const val GOLD_DARK: UInt = 0x00905800u
    const val SLOT: UInt = 0x00303848u
    const val DIM: UInt = 0x00687088u

    fun frame(sink: PixelSink, x: Int, y: Int, width: Int, height: Int, fill: UInt = FILL) {
        sink.fill(x, y, width, height, OUTLINE)
        sink.fill(x + 2, y + 2, width - 4, height - 4, BORDER)
        sink.fill(x + 6, y + 6, width - 12, height - 12, OUTLINE)
        sink.fill(x + 8, y + 8, width - 16, height - 16, fill)
    }
}
