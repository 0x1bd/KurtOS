package kernel.ui

import hal.Clock
import kapi.ui.PixelFont
import kernel.audio.AudioService
import kernel.graphics.GraphicsService
import kernel.memory.PageAllocator

object HUD {
    const val RESERVED: UInt = 52u

    private var sink: FramebufferSink? = null
    private var lastDraw = 0UL

    fun tick() {
        val now = Clock.uptimeMillis()
        if (now - lastDraw < REFRESH_MS) return

        draw()
        GraphicsService.framebuffer()?.present()
    }

    fun draw() {
        val fb = GraphicsService.framebuffer() ?: return

        val target = sink ?: FramebufferSink(fb).also { sink = it }
        val width = fb.width.toInt()

        lastDraw = Clock.uptimeMillis()

        target.fill(0, 0, width, RESERVED.toInt() - 2, COLOR_BACKGROUND)
        target.fill(0, RESERVED.toInt() - 2, width, 2, COLOR_LINE)

        val x0 = 16
        val x1 = width * 32 / 100
        val x2 = width * 54 / 100
        val x3 = width * 72 / 100

        PixelFont.draw(target, x0, LABEL_Y, "KURTOS", COLOR_LABEL, SCALE, COLOR_SHADOW)
        PixelFont.draw(target, x1, LABEL_Y, "WORLD", COLOR_LABEL, SCALE, COLOR_SHADOW)
        PixelFont.draw(target, x2, LABEL_Y, "TIME", COLOR_LABEL, SCALE, COLOR_SHADOW)
        PixelFont.draw(target, x3, LABEL_Y, "♪ SOUND", COLOR_LABEL, SCALE, COLOR_SHADOW)

        PixelFont.draw(target, x0, VALUE_Y, score(), COLOR_VALUE, SCALE, COLOR_SHADOW)
        PixelFont.draw(target, x1, VALUE_Y, " 1-1", COLOR_VALUE, SCALE, COLOR_SHADOW)
        PixelFont.draw(target, x2, VALUE_Y, uptime(), COLOR_VALUE, SCALE, COLOR_SHADOW)

        drawVolume(target, x3)
    }

    private fun drawVolume(target: FramebufferSink, x: Int) {
        if (AudioService.muted()) {
            PixelFont.draw(target, x, VALUE_Y, "MUTED", COLOR_MUTED, SCALE, COLOR_SHADOW)
            return
        }

        val filled = (AudioService.volume() + 9) / 10

        for (i in 0 until CELLS) {
            val cellX = x + i * (CELL_W + CELL_GAP)
            val color = if (i < filled) COLOR_COIN else COLOR_SLOT
            target.fill(cellX, VALUE_Y + 1, CELL_W, CELL_H, color)
        }
    }

    private fun score(): String {
        val used = PageAllocator.totalPages - PageAllocator.freePages
        return used.toString().padStart(6, '0')
    }

    private fun uptime(): String {
        val seconds = Clock.uptimeMillis() / 1000UL
        val minutes = seconds / 60UL
        val rest = (seconds % 60UL).toString().padStart(2, '0')
        return "$minutes:$rest"
    }

    private const val SCALE = 2
    private const val LABEL_Y = 8
    private const val VALUE_Y = 28
    private const val REFRESH_MS = 500UL

    private const val CELLS = 10
    private const val CELL_W = 10
    private const val CELL_H = 14
    private const val CELL_GAP = 4

    private const val COLOR_BACKGROUND: UInt = 0x00060A10u
    private const val COLOR_LINE: UInt = 0x00283048u
    private const val COLOR_LABEL: UInt = 0x00F8D878u
    private const val COLOR_VALUE: UInt = 0x00F8F8F8u
    private const val COLOR_SHADOW: UInt = 0x00101018u
    private const val COLOR_MUTED: UInt = 0x00E04838u
    private const val COLOR_COIN: UInt = 0x00F8B800u
    private const val COLOR_SLOT: UInt = 0x00283048u
}
