package kernel.graphics

import kapi.HighColorBitmap
import kapi.IndexedBitmap
import kapi.Surface

class OffsetSurface(private val fb: Framebuffer, private val top: UInt) : Surface {
    override val width: UInt get() = fb.width
    override val height: UInt get() = fb.height - top

    override fun clear(color: UInt) {
        fb.fillRect(0u, top, fb.width, height, color)
    }

    override fun fillRect(x: UInt, y: UInt, width: UInt, height: UInt, color: UInt) {
        fb.fillRect(x, y + top, width, height, color)
    }

    override fun createBitmap(width: UInt, height: UInt, paletteSize: Int): IndexedBitmap? {
        val bitmap = fb.createBitmap(width, height, paletteSize) ?: return null
        return OffsetBitmap(bitmap, top)
    }

    override fun createHighColorBitmap(width: UInt, height: UInt): HighColorBitmap? {
        val bitmap = fb.createHighColorBitmap(width, height) ?: return null
        return OffsetHighBitmap(bitmap, top)
    }

    override fun present() = fb.present()

    override fun presentAll() = fb.presentAll()
}

private class OffsetBitmap(private val inner: IndexedBitmap, private val top: UInt) : IndexedBitmap {
    override val width: UInt get() = inner.width
    override val height: UInt get() = inner.height
    override val pixels: ByteArray get() = inner.pixels

    override fun setPalette(index: Int, color: UInt) = inner.setPalette(index, color)

    override fun draw(x: UInt, y: UInt, scale: UInt) = inner.draw(x, y + top, scale)
}

private class OffsetHighBitmap(private val inner: HighColorBitmap, private val top: UInt) : HighColorBitmap {
    override val width: UInt get() = inner.width
    override val height: UInt get() = inner.height
    override val pixels: ShortArray get() = inner.pixels

    override fun draw(x: UInt, y: UInt, scale: UInt) = inner.draw(x, y + top, scale)
}
