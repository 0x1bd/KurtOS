package kernel.graphics

import hal.BootInfo
import hal.FramebufferInfo
import hal.RawMemory
import kapi.Surface
import kernel.memory.PageAllocator

class Framebuffer(private val info: FramebufferInfo, private val backbuffer: ULong) : Surface {
    override val width: UInt = info.width
    override val height: UInt = info.height

    private val stride: UInt = info.width * 4u

    private var dirtyMinY: UInt = info.height
    private var dirtyMaxY: UInt = 0u

    fun encode(color: UInt): UInt {
        val r = (color shr 16) and 0xFFu
        val g = (color shr 8) and 0xFFu
        val b = color and 0xFFu
        return (r shl info.redShift) or (g shl info.greenShift) or (b shl info.blueShift)
    }

    override fun clear(color: UInt) {
        fillRect(0u, 0u, width, height, color)
    }

    override fun fillRect(x: UInt, y: UInt, width: UInt, height: UInt, color: UInt) {
        if (x >= this.width || y >= this.height || width == 0u || height == 0u) return

        val endX = minOf(x + width, this.width)
        val endY = minOf(y + height, this.height)
        val pixels = endX - x
        val encoded = encode(color)

        var row = y
        while (row < endY) {
            RawMemory.fill32(backbuffer + row.toULong() * stride.toULong() + x.toULong() * 4UL, encoded, pixels)
            row++
        }

        markDirty(y, endY)
    }

    fun setPixel(x: UInt, y: UInt, color: UInt) {
        if (x >= width || y >= height) return
        RawMemory.write32(backbuffer + y.toULong() * stride.toULong() + x.toULong() * 4UL, encode(color))
        markDirty(y, y + 1u)
    }

    fun scrollUp(pixels: UInt, fill: UInt) {
        if (pixels == 0u || pixels >= height) return

        val moved = (height - pixels).toULong() * stride.toULong()
        RawMemory.copy(backbuffer, backbuffer + pixels.toULong() * stride.toULong(), moved)

        val encoded = encode(fill)
        var row = height - pixels
        while (row < height) {
            RawMemory.fill32(backbuffer + row.toULong() * stride.toULong(), encoded, width)
            row++
        }

        markDirty(0u, height)
    }

    override fun present() {
        if (dirtyMaxY <= dirtyMinY) return
        blit(dirtyMinY, dirtyMaxY)
        clearDirty()
    }

    override fun presentAll() {
        blit(0u, height)
        clearDirty()
    }

    private fun blit(fromRow: UInt, toRow: UInt) {
        var row = fromRow
        while (row < toRow) {
            RawMemory.copy(
                info.address + row.toULong() * info.pitch.toULong(),
                backbuffer + row.toULong() * stride.toULong(),
                stride.toULong(),
            )
            row++
        }
    }

    private fun markDirty(fromRow: UInt, toRow: UInt) {
        if (fromRow < dirtyMinY) dirtyMinY = fromRow
        if (toRow > dirtyMaxY) dirtyMaxY = minOf(toRow, height)
    }

    private fun clearDirty() {
        dirtyMinY = height
        dirtyMaxY = 0u
    }
}

object GraphicsService {
    private var framebuffer: Framebuffer? = null
    private var status: String = "not initialized"

    fun initialize(): Boolean {
        if (framebuffer != null) return true

        val info = BootInfo.framebuffer
        if (info == null) {
            status = "no framebuffer from bootloader"
            return false
        }

        val buffer = PageAllocator.allocateBytes(info.width * info.height * 4u)
        if (buffer == null) {
            status = "not enough memory for a backbuffer"
            return false
        }

        framebuffer = Framebuffer(info, buffer.address)
        status = "ready ${info.width}x${info.height}"
        return true
    }

    fun framebuffer(): Framebuffer? = framebuffer

    fun status(): String = status
}
