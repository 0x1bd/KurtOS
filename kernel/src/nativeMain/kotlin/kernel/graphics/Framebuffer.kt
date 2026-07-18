package kernel.graphics

import hal.BootInfo
import hal.FramebufferInfo
import hal.RawMemory
import kapi.IndexedBitmap
import kapi.Surface
import kernel.KLog
import kernel.memory.PageAllocator
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.pin
import kotlinx.cinterop.toLong

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

    override fun createBitmap(width: UInt, height: UInt, paletteSize: Int): IndexedBitmap? {
        if (width == 0u || height == 0u || paletteSize <= 0) return null
        if (width > this.width || height > this.height) return null
        return PinnedBitmap(width, height, paletteSize)
    }

    override fun createHighColorBitmap(width: UInt, height: UInt): kapi.HighColorBitmap? {
        if (width == 0u || height == 0u) return null
        if (width > this.width || height > this.height) return null
        return PinnedHighBitmap(width, height)
    }

    private var highPalette: IntArray? = null

    @OptIn(ExperimentalForeignApi::class)
    private var highPalettePin: kotlinx.cinterop.Pinned<IntArray>? = null

    @OptIn(ExperimentalForeignApi::class)
    private fun highPaletteAddress(): ULong {
        val existing = highPalettePin
        if (existing != null) return existing.addressOf(0).toLong().toULong()

        val palette = IntArray(0x8000) { index ->
            val r = (index and 0x1F) shl 3 or ((index and 0x1F) shr 2)
            val g = ((index shr 5) and 0x1F) shl 3 or (((index shr 5) and 0x1F) shr 2)
            val b = ((index shr 10) and 0x1F) shl 3 or (((index shr 10) and 0x1F) shr 2)
            encode(((r shl 16) or (g shl 8) or b).toUInt()).toInt()
        }

        highPalette = palette
        val pinned = palette.pin()
        highPalettePin = pinned
        return pinned.addressOf(0).toLong().toULong()
    }

    @OptIn(ExperimentalForeignApi::class)
    private inner class PinnedHighBitmap(
        override val width: UInt,
        override val height: UInt,
    ) : kapi.HighColorBitmap {
        override val pixels = ShortArray((width * height).toInt())

        private val pinnedPixels = pixels.pin()
        private val pixelsAddress = pinnedPixels.addressOf(0).toLong().toULong()

        override fun draw(x: UInt, y: UInt, scale: UInt) {
            if (scale == 0u) return

            val drawnWidth = width * scale
            val drawnHeight = height * scale
            if (x + drawnWidth > this@Framebuffer.width) return
            if (y + drawnHeight > this@Framebuffer.height) return

            RawMemory.blitHigh(
                backbuffer + y.toULong() * stride.toULong() + x.toULong() * 4UL,
                stride,
                pixelsAddress,
                width,
                height,
                highPaletteAddress(),
                scale,
            )

            markDirty(y, y + drawnHeight)
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private inner class PinnedBitmap(
        override val width: UInt,
        override val height: UInt,
        paletteSize: Int,
    ) : IndexedBitmap {
        override val pixels = ByteArray((width * height).toInt())

        private val palette = IntArray(paletteSize)

        private val pinnedPixels = pixels.pin()
        private val pinnedPalette = palette.pin()

        private val pixelsAddress = pinnedPixels.addressOf(0).toLong().toULong()
        private val paletteAddress = pinnedPalette.addressOf(0).toLong().toULong()

        override fun setPalette(index: Int, color: UInt) {
            if (index < 0 || index >= palette.size) return
            palette[index] = encode(color).toInt()
        }

        override fun draw(x: UInt, y: UInt, scale: UInt) {
            if (scale == 0u) return

            val drawnWidth = width * scale
            val drawnHeight = height * scale
            if (x + drawnWidth > this@Framebuffer.width) return
            if (y + drawnHeight > this@Framebuffer.height) return

            RawMemory.blitIndexed(
                backbuffer + y.toULong() * stride.toULong() + x.toULong() * 4UL,
                stride,
                pixelsAddress,
                width,
                height,
                paletteAddress,
                scale,
            )

            markDirty(y, y + drawnHeight)
        }
    }

    fun setPixel(x: UInt, y: UInt, color: UInt) {
        if (x >= width || y >= height) return
        RawMemory.write32(backbuffer + y.toULong() * stride.toULong() + x.toULong() * 4UL, encode(color))
        markDirty(y, y + 1u)
    }

    fun scrollUp(pixels: UInt, fill: UInt) {
        scrollRegion(0u, pixels, fill)
    }

    fun scrollRegion(top: UInt, pixels: UInt, fill: UInt) {
        if (pixels == 0u || top + pixels >= height) return

        val moved = (height - top - pixels).toULong() * stride.toULong()
        RawMemory.copy(
            backbuffer + top.toULong() * stride.toULong(),
            backbuffer + (top + pixels).toULong() * stride.toULong(),
            moved,
        )

        val encoded = encode(fill)
        var row = height - pixels
        while (row < height) {
            RawMemory.fill32(backbuffer + row.toULong() * stride.toULong(), encoded, width)
            row++
        }

        markDirty(top, height)
    }

    fun frontBlit(x: UInt, y: UInt, width: UInt, height: UInt, source: ULong, sourceStride: ULong) {
        if (!DisplayContext.consoleOwnsScanout) return
        if (x >= this.width || y >= this.height) return

        val pixels = minOf(width, this.width - x).toULong() * 4UL
        val rows = minOf(height, this.height - y)

        var row = 0u
        while (row < rows) {
            RawMemory.copy(
                info.address + (y + row).toULong() * info.pitch.toULong() + x.toULong() * 4UL,
                source + row.toULong() * sourceStride,
                pixels,
            )
            row++
        }
    }

    fun invalidate(fromRow: UInt, toRow: UInt) {
        markDirty(fromRow, toRow)
    }

    override fun present() {
        if (!DisplayContext.consoleOwnsScanout) return
        if (dirtyMaxY <= dirtyMinY) return
        blit(dirtyMinY, dirtyMaxY)
        clearDirty()
    }

    override fun presentAll() {
        if (!DisplayContext.consoleOwnsScanout) return
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
            kernel.ui.OSD.composeRow(this, row)
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
    private var reported = false

    fun initialize(): Boolean {
        if (framebuffer != null) return true

        val info = BootInfo.framebuffer
        if (info == null) {
            status = "no framebuffer from bootloader"
            report(false)
            return false
        }

        val buffer = PageAllocator.allocateBytes(info.width * info.height * 4u)
        if (buffer == null) {
            status = "not enough memory for a backbuffer"
            report(false)
            return false
        }

        framebuffer = Framebuffer(info, buffer.address)
        status = "ready ${info.width}x${info.height}"
        report(true)
        return true
    }

    private fun report(ok: Boolean) {
        if (reported) return
        reported = true
        KLog.step("display", "framebuffer", ok, status)
    }

    fun framebuffer(): Framebuffer? = framebuffer

    fun status(): String = status
}
