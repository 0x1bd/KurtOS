package kernel.ui

import hal.Clock
import kapi.ui.Canvas
import kapi.ui.Panels
import kapi.ui.Icon
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

    private var panel: PixelCanvas? = null

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

    fun notify(icon: Icon, title: String, subtitle: String?, sound: SystemSounds.Clip?) {
        val fb = ensure() ?: return

        if (sound != null) SystemSounds.play(sound)

        clearDrawn(fb)

        kind = Kind.Toast
        holdMillis = TOAST_HOLD_MS
        renderToast(icon, title.take(MAX_CHARS), subtitle?.take(MAX_CHARS))

        panelX = fb.width.toInt() - panelWidth - EDGE_MARGIN
        targetY = EDGE_MARGIN
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

        val buffer = panel ?: return
        val panelRow = (drawnOffset + (row - drawnY).toInt()).toULong()

        fb.frontBlit(
            panelX.toUInt(),
            row,
            panelWidth.toUInt(),
            1u,
            buffer.address + panelRow * buffer.stride,
            buffer.stride,
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
        if (panel != null) return fb

        val region = PageAllocator.allocateBytes((PANEL_MAX_W * PANEL_MAX_H * 4).toUInt()) ?: return null
        panel = PixelCanvas(fb, region.address, PANEL_MAX_W, PANEL_MAX_H)
        return fb
    }

    private fun renderToast(icon: Icon, title: String, subtitle: String?) {
        val buffer = panel ?: return
        val ui = Canvas(buffer, buffer.width, buffer.height)

        val textWidth = maxOf(
            ui.textWidth(title, SCALE),
            if (subtitle != null) ui.textWidth(subtitle, SCALE) else 0,
        )
        val textHeight = if (subtitle != null) 40 else 16

        panelWidth = minOf(PAD + ICON_BOX + GAP + textWidth + PAD, PANEL_MAX_W)
        panelHeight = PAD * 2 + maxOf(ICON_BOX, textHeight)

        ui.card(0, 0, panelWidth, panelHeight, Panels.CARD, Panels.ACCENT, BORDER)

        val iconY = (panelHeight - icon.height * SCALE) / 2
        ui.icon(icon, PAD + (ICON_BOX - icon.width * SCALE) / 2, iconY, SCALE)

        val textX = PAD + ICON_BOX + GAP
        if (subtitle == null) {
            ui.text(textX, (panelHeight - 16) / 2, title, Panels.INK, SCALE)
        } else {
            val top = (panelHeight - 40) / 2
            ui.text(textX, top, title, Panels.INK, SCALE)
            ui.text(textX, top + 24, subtitle, Panels.QUIET, SCALE)
        }
    }

    private fun renderVolume() {
        val buffer = panel ?: return
        val ui = Canvas(buffer, buffer.width, buffer.height)

        val cellsWidth = CELLS * CELL_W + (CELLS - 1) * CELL_GAP
        panelWidth = PAD + ICON_BOX + GAP + cellsWidth + PAD
        panelHeight = PAD * 2 + ICON_BOX

        ui.card(0, 0, panelWidth, panelHeight, Panels.CARD, Panels.ACCENT, BORDER)

        val muted = AudioService.muted()
        val icon = if (muted) PixelIcons.SPEAKER_MUTE else PixelIcons.SPEAKER
        ui.icon(icon, PAD + (ICON_BOX - icon.width * SCALE) / 2, (panelHeight - icon.height * SCALE) / 2, SCALE)

        val filled = if (muted) 0 else (AudioService.volume() + 9) / 10
        val cellY = (panelHeight - CELL_H) / 2

        for (i in 0 until CELLS) {
            val cellX = PAD + ICON_BOX + GAP + i * (CELL_W + CELL_GAP)
            ui.fill(cellX, cellY, CELL_W, CELL_H, if (i < filled) Panels.GREEN else Panels.EDGE)
        }
    }

    private const val SCALE = 2
    private const val BORDER = 4
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
