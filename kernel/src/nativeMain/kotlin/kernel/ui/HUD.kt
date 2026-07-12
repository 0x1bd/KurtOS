package kernel.ui

import hal.Clock
import kapi.Status
import kapi.Time
import kapi.ui.Panels
import kapi.ui.PixelFont
import kapi.ui.PixelIcons
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

        target.fill(0, 0, width, BAR_HEIGHT, COLOR_BACKGROUND)
        target.fill(0, BAR_HEIGHT, width, 2, COLOR_LINE)

        PixelFont.draw(target, MARGIN, TEXT_Y, "KURTOS", Panels.GOLD, SCALE, COLOR_SHADOW)

        var x = MARGIN + PixelFont.textWidth("KURTOS", SCALE) + GAP
        separator(target, x)
        x += GAP

        val context = Status.context ?: "LIBRARY"
        PixelFont.draw(target, x, TEXT_Y, context, COLOR_VALUE, SCALE, COLOR_SHADOW)

        var right = width - MARGIN

        val clock = clock()
        right -= PixelFont.textWidth(clock, SCALE)
        PixelFont.draw(target, right, TEXT_Y, clock, COLOR_VALUE, SCALE, COLOR_SHADOW)

        right -= GAP
        separator(target, right)
        right -= GAP

        right -= meterWidth()
        drawVolume(target, right)

        right -= GAP
        separator(target, right)
        right -= GAP

        val memory = memory()
        right -= PixelFont.textWidth(memory, SCALE)
        PixelFont.draw(target, right, TEXT_Y, memory, Panels.DIM, SCALE, COLOR_SHADOW)
    }

    private fun separator(target: FramebufferSink, x: Int) {
        target.fill(x, 12, 2, BAR_HEIGHT - 24, COLOR_LINE)
    }

    private fun meterWidth(): Int = PixelIcons.SPEAKER.width * SCALE + 10 + CELLS * (CELL_W + CELL_GAP)

    private fun drawVolume(target: FramebufferSink, x: Int) {
        val muted = AudioService.muted()
        val icon = if (muted) PixelIcons.SPEAKER_MUTE else PixelIcons.SPEAKER

        icon.draw(target, x, (BAR_HEIGHT - icon.height * SCALE) / 2, SCALE)

        val cellsX = x + icon.width * SCALE + 10
        val filled = if (muted) 0 else (AudioService.volume() + 9) / 10

        for (i in 0 until CELLS) {
            val cellX = cellsX + i * (CELL_W + CELL_GAP)
            val color = if (i < filled) Panels.GOLD else COLOR_SLOT
            target.fill(cellX, (BAR_HEIGHT - CELL_H) / 2, CELL_W, CELL_H, color)
        }
    }

    private fun clock(): String {
        val now = Time.now() ?: return uptime()
        return "${pad(now.hour)}:${pad(now.minute)}"
    }

    private fun uptime(): String {
        val seconds = (Clock.uptimeMillis() / 1000UL).toInt()
        return "${pad(seconds / 3600)}:${pad((seconds / 60) % 60)}"
    }

    private fun memory(): String {
        val used = PageAllocator.totalPages - PageAllocator.freePages
        return "${used / PAGES_PER_MIB} / ${PageAllocator.totalPages / PAGES_PER_MIB} MIB"
    }

    private fun pad(value: Int): String = value.toString().padStart(2, '0')

    private const val SCALE = 2
    private const val BAR_HEIGHT = 50
    private const val TEXT_Y = 17
    private const val MARGIN = 16
    private const val GAP = 16
    private const val REFRESH_MS = 500UL

    private const val PAGES_PER_MIB = 256

    private const val CELLS = 10
    private const val CELL_W = 8
    private const val CELL_GAP = 3
    private const val CELL_H = 16

    private const val COLOR_BACKGROUND: UInt = 0x00060A10u
    private const val COLOR_LINE: UInt = 0x00283048u
    private const val COLOR_VALUE: UInt = 0x00F8F8F8u
    private const val COLOR_SHADOW: UInt = 0x00101018u
    private const val COLOR_SLOT: UInt = 0x00283048u
}
