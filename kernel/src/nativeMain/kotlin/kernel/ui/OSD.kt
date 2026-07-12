package kernel.ui

import hal.Clock
import kapi.ui.Panels
import kapi.ui.PixelFont
import kapi.ui.PixelIcons
import kernel.audio.AudioService
import kernel.graphics.Framebuffer
import kernel.graphics.GraphicsService
import kernel.memory.PageAllocator

object OSD {
    private enum class Phase { Hidden, SlideIn, Hold, SlideOut }
    private enum class Kind { Toast, Volume }

    private var phase = Phase.Hidden
    private var kind = Kind.Toast
    private var phaseStart = 0UL
    private var holdMillis = TOAST_HOLD_MS

    private var canvas: PixelCanvas? = null

    private var panelWidth = 0
    private var panelHeight = 0
    private var panelX = 0
    private var startY = 0
    private var targetY = 0
    private var currentY = 0

    private var drawn = false
    private var drawnY = 0u
    private var drawnRows = 0u
    private var drawnOffset = 0

    fun notify(icon: PixelIcons.Icon, title: String, subtitle: String?, sound: SystemSounds.Clip?) {
        val fb = ensure() ?: return

        if (sound != null) SystemSounds.play(sound)

        clearDrawn(fb)

        kind = Kind.Toast
        holdMillis = TOAST_HOLD_MS
        renderToast(icon, title.take(MAX_CHARS), subtitle?.take(MAX_CHARS))

        panelX = fb.width.toInt() - panelWidth - EDGE_MARGIN
        targetY = HUD.RESERVED.toInt() + 8
        startY = -panelHeight

        begin(fb)
    }

    fun showVolume() {
        val fb = ensure() ?: return

        val wasVolume = phase != Phase.Hidden && kind == Kind.Volume

        if (!wasVolume) clearDrawn(fb)

        kind = Kind.Volume
        holdMillis = VOLUME_HOLD_MS
        renderVolume()

        if (wasVolume) {
            if (phase != Phase.SlideIn) {
                phase = Phase.Hold
                currentY = targetY
            }
            phaseStart = Clock.uptimeMillis()
            move(fb, currentY)
            return
        }

        panelX = (fb.width.toInt() - panelWidth) / 2
        targetY = fb.height.toInt() - panelHeight - BOTTOM_MARGIN
        startY = fb.height.toInt()

        begin(fb)
    }

    fun tick() {
        if (phase == Phase.Hidden) return
        val fb = GraphicsService.framebuffer() ?: return

        val now = Clock.uptimeMillis()
        val elapsed = (now - phaseStart).toInt()

        when (phase) {
            Phase.SlideIn -> {
                if (elapsed >= SLIDE_MS) {
                    phase = Phase.Hold
                    phaseStart = now
                    move(fb, targetY)
                } else {
                    move(fb, startY + (targetY - startY) * elapsed / SLIDE_MS)
                }
            }

            Phase.Hold -> {
                if (elapsed >= holdMillis) {
                    phase = Phase.SlideOut
                    phaseStart = now
                }
            }

            Phase.SlideOut -> {
                if (elapsed >= SLIDE_MS) {
                    hideForPrint()
                } else {
                    move(fb, targetY + (startY - targetY) * elapsed / SLIDE_MS)
                }
            }

            Phase.Hidden -> Unit
        }
    }

    fun composeRow(fb: Framebuffer, row: UInt) {
        if (!drawn) return
        if (row < drawnY || row >= drawnY + drawnRows) return

        val panel = canvas ?: return
        val panelRow = (drawnOffset + (row - drawnY).toInt()).toULong()

        fb.frontBlit(
            panelX.toUInt(),
            row,
            panelWidth.toUInt(),
            1u,
            panel.address + panelRow * panel.stride,
            panel.stride,
        )
    }

    fun hideForPrint() {
        if (phase == Phase.Hidden) return
        val fb = GraphicsService.framebuffer() ?: return

        clearDrawn(fb)
        phase = Phase.Hidden
        fb.present()
    }

    private fun begin(fb: Framebuffer) {
        phase = Phase.SlideIn
        phaseStart = Clock.uptimeMillis()
        move(fb, startY)
    }

    private fun move(fb: Framebuffer, y: Int) {
        if (drawn) {
            drawn = false
            fb.invalidate(drawnY, drawnY + drawnRows)
        }

        currentY = y

        val visibleY = if (y < 0) 0 else y
        val offset = visibleY - y
        val rows = minOf(panelHeight - offset, fb.height.toInt() - visibleY)

        if (rows > 0 && panelX >= 0) {
            drawnY = visibleY.toUInt()
            drawnRows = rows.toUInt()
            drawnOffset = offset
            drawn = true
            fb.invalidate(drawnY, drawnY + drawnRows)
        }

        fb.present()
    }

    private fun clearDrawn(fb: Framebuffer) {
        if (!drawn) return

        drawn = false
        fb.invalidate(drawnY, drawnY + drawnRows)
    }

    private fun ensure(): Framebuffer? {
        val fb = GraphicsService.framebuffer() ?: return null
        if (canvas != null) return fb

        val region = PageAllocator.allocateBytes((PANEL_MAX_W * PANEL_MAX_H * 4).toUInt()) ?: return null
        canvas = PixelCanvas(fb, region.address, PANEL_MAX_W, PANEL_MAX_H)
        return fb
    }

    private fun renderToast(icon: PixelIcons.Icon, title: String, subtitle: String?) {
        val panel = canvas ?: return

        val textWidth = maxOf(
            PixelFont.textWidth(title, SCALE),
            if (subtitle != null) PixelFont.textWidth(subtitle, SCALE) else 0,
        )
        val textHeight = if (subtitle != null) 40 else 16

        panelWidth = minOf(PAD + ICON_BOX + GAP + textWidth + PAD, PANEL_MAX_W)
        panelHeight = PAD * 2 + maxOf(ICON_BOX, textHeight)

        Panels.frame(panel, 0, 0, panelWidth, panelHeight)

        val iconY = (panelHeight - icon.height * SCALE) / 2
        icon.draw(panel, PAD + (ICON_BOX - icon.width * SCALE) / 2, iconY, SCALE)

        val textX = PAD + ICON_BOX + GAP
        if (subtitle == null) {
            PixelFont.draw(panel, textX, (panelHeight - 16) / 2, title, Panels.TITLE, SCALE, Panels.OUTLINE)
        } else {
            val top = (panelHeight - 40) / 2
            PixelFont.draw(panel, textX, top, title, Panels.TITLE, SCALE, Panels.OUTLINE)
            PixelFont.draw(panel, textX, top + 24, subtitle, Panels.SUBTITLE, SCALE, Panels.OUTLINE)
        }
    }

    private fun renderVolume() {
        val panel = canvas ?: return

        val cellsWidth = CELLS * CELL_W + (CELLS - 1) * CELL_GAP
        panelWidth = PAD + ICON_BOX + GAP + cellsWidth + PAD
        panelHeight = PAD * 2 + ICON_BOX

        Panels.frame(panel, 0, 0, panelWidth, panelHeight)

        val muted = AudioService.muted()
        val icon = if (muted) PixelIcons.SPEAKER_MUTE else PixelIcons.SPEAKER
        icon.draw(panel, PAD + (ICON_BOX - icon.width * SCALE) / 2, (panelHeight - icon.height * SCALE) / 2, SCALE)

        val filled = if (muted) 0 else (AudioService.volume() + 9) / 10
        val cellY = (panelHeight - CELL_H) / 2

        for (i in 0 until CELLS) {
            val cellX = PAD + ICON_BOX + GAP + i * (CELL_W + CELL_GAP)

            panel.fill(cellX, cellY, CELL_W, CELL_H, Panels.OUTLINE)
            if (i < filled) {
                panel.fill(cellX + 2, cellY + 2, CELL_W - 4, CELL_H - 4, Panels.GOLD)
                panel.fill(cellX + 2, cellY + CELL_H - 6, CELL_W - 4, 4, Panels.GOLD_DARK)
            } else {
                panel.fill(cellX + 2, cellY + 2, CELL_W - 4, CELL_H - 4, Panels.SLOT)
            }
        }
    }

    private const val SCALE = 2
    private const val PAD = 16
    private const val GAP = 12
    private const val ICON_BOX = 32
    private const val EDGE_MARGIN = 16
    private const val BOTTOM_MARGIN = 40
    private const val MAX_CHARS = 24

    private const val CELLS = 10
    private const val CELL_W = 16
    private const val CELL_H = 22
    private const val CELL_GAP = 6

    private const val PANEL_MAX_W = 512
    private const val PANEL_MAX_H = 96

    private const val SLIDE_MS = 160
    private const val TOAST_HOLD_MS = 2600
    private const val VOLUME_HOLD_MS = 1200
}
