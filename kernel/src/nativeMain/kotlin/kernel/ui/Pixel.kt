package kernel.ui

import hal.RawMemory
import kapi.ui.PixelSink
import kernel.graphics.Framebuffer

class FramebufferSink(private val fb: Framebuffer) : PixelSink {
    override fun fill(x: Int, y: Int, width: Int, height: Int, color: UInt) {
        if (x < 0 || y < 0 || width <= 0 || height <= 0) return
        fb.fillRect(x.toUInt(), y.toUInt(), width.toUInt(), height.toUInt(), color)
    }
}

class PixelCanvas(
    private val fb: Framebuffer,
    val address: ULong,
    val width: Int,
    val height: Int,
) : PixelSink {
    val stride: ULong = width.toULong() * 4UL

    override fun fill(x: Int, y: Int, width: Int, height: Int, color: UInt) {
        val startX = if (x < 0) 0 else x
        val startY = if (y < 0) 0 else y
        val endX = minOf(x + width, this.width)
        val endY = minOf(y + height, this.height)
        if (startX >= endX || startY >= endY) return

        val encoded = fb.encode(color)
        val pixels = (endX - startX).toUInt()

        var row = startY
        while (row < endY) {
            RawMemory.fill32(address + row.toULong() * stride + startX.toULong() * 4UL, encoded, pixels)
            row++
        }
    }
}
