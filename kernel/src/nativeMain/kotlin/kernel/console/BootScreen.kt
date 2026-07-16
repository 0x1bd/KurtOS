package kernel.console

import hal.Arch
import hal.Clock
import hal.Cpu
import hal.Serial
import kapi.Pad
import kapi.ui.Canvas
import kernel.KernelSystem
import kernel.arch.Acpi
import kernel.audio.AudioService
import kernel.drivers.I8042
import kernel.drivers.Keyboard
import kernel.drivers.usb.GamepadService
import kernel.drivers.usb.USBService
import kernel.fs.StorageService
import kernel.graphics.GraphicsService

object BootScreen {
    private enum class Status(val badge: String, val color: UInt) {
        OK(" OK ", 0x3FB950u),
        WARN("WARN", 0xD29922u),
        FAIL("FAIL", 0xF85149u),
        INFO("INFO", 0x58A6FFu),
        HEAD("", 0x79C0FFu),
    }

    private class Line(val status: Status, val label: String, val value: String)

    private const val BG: UInt = 0x0D1117u
    private const val BAND: UInt = 0x161B22u
    private const val TEXT: UInt = 0xC9D1D9u
    private const val DIM: UInt = 0x8B949Eu
    private const val SECTION: UInt = 0x79C0FFu
    private const val ACCENT: UInt = 0x58A6FFu
    private const val PROMPT: UInt = 0xF0F6FCu

    private const val MARGIN = 32
    private const val TITLE_SCALE = 4
    private const val BODY_SCALE = 2

    private const val AUTO_ADVANCE_MS = 2_500UL
    private const val RENDER_INTERVAL_MS = 120UL

    fun show() {
        val lines = collect()
        serialDump(lines)

        val canvas = GraphicsService.framebuffer()?.let { Canvas(it) }
        if (canvas != null) render(canvas, lines, 1000)

        awaitContinue(canvas)
    }

    private fun collect(): List<Line> {
        val lines = mutableListOf<Line>()

        lines += Line(Status.HEAD, "SYSTEM", "")
        lines += Line(Status.INFO, "cpu", Cpu.brand())
        lines += Line(Status.INFO, "memory", KernelSystem.memoryReport())
        lines += statusLine(GraphicsService.framebuffer() != null, "display", GraphicsService.status())
        lines += statusLine(Acpi.available, "acpi", if (Acpi.available) "madt parsed, apic routing active" else "no madt")

        lines += Line(Status.HEAD, "INPUT", "")
        lines += Line(
            if (I8042.present) Status.OK else Status.WARN,
            "keyboard",
            if (I8042.present) {
                "ps/2 keyboard  (irq ${Arch.keyboardInterrupts()}, keys ${Keyboard.received})"
            } else {
                "no keyboard controller (gamepad only)"
            },
        )
        lines += Line(
            if (GamepadService.available) Status.OK else Status.WARN,
            "gamepad",
            GamepadService.summary(),
        )

        lines += Line(Status.HEAD, "AUDIO", "")
        lines += Line(if (AudioService.available) Status.OK else Status.FAIL, "audio", AudioService.status)

        lines += Line(Status.HEAD, "STORAGE", "")
        lines += Line(if (StorageService.ready) Status.OK else Status.WARN, "storage", StorageService.status)

        lines += Line(Status.HEAD, "USB", "")
        val usb = USBService.describe()
        lines += Line(if (USBService.ready) Status.OK else Status.WARN, "usb", usb.firstOrNull() ?: "no controller")
        for (detail in usb.drop(1).take(8)) lines += Line(Status.INFO, "", detail)

        return lines
    }

    private fun statusLine(ok: Boolean, label: String, value: String): Line =
        Line(if (ok) Status.OK else Status.WARN, label, value)

    private fun render(canvas: Canvas, lines: List<Line>, progressPermille: Int) {
        canvas.clear(BG)

        val charWidth = canvas.glyphWidth * BODY_SCALE
        val lineHeight = (canvas.glyphHeight + 4) * BODY_SCALE
        val titleHeight = canvas.glyphHeight * TITLE_SCALE

        val bandHeight = MARGIN + titleHeight + 8 + lineHeight + 8
        canvas.fill(0, 0, canvas.width, bandHeight, BAND)
        canvas.text(MARGIN, MARGIN, "KurtOS", ACCENT, TITLE_SCALE)
        canvas.text(MARGIN, MARGIN + titleHeight + 8, Cpu.brand(), DIM, BODY_SCALE)

        val barHeight = 6
        val footerHeight = canvas.glyphHeight * BODY_SCALE + 20 + barHeight
        val footerTop = canvas.height - footerHeight
        canvas.fill(0, footerTop, canvas.width, footerHeight, BAND)
        canvas.text(MARGIN, footerTop + 8, "PRESS (A) OR START TO CONTINUE", PROMPT, BODY_SCALE)

        val barTop = canvas.height - barHeight
        val fill = canvas.width * progressPermille.coerceIn(0, 1000) / 1000
        canvas.fill(0, barTop, fill, barHeight, ACCENT)

        val labelWidth = 10 * charWidth
        var y = bandHeight + lineHeight

        for (line in lines) {
            if (y + lineHeight > footerTop) break

            if (line.status == Status.HEAD) {
                y += lineHeight / 2
                canvas.text(MARGIN, y, line.label, SECTION, BODY_SCALE)
                canvas.hline(MARGIN, y + lineHeight - 4, labelWidth * 3, BAND)
                y += lineHeight
                continue
            }

            if (line.label.isEmpty()) {
                canvas.text(MARGIN + charWidth, y, line.value, DIM, BODY_SCALE)
                y += lineHeight
                continue
            }

            val badgeWidth = line.status.badge.length * charWidth
            val pillWidth = badgeWidth + 12
            canvas.fill(MARGIN, y - 2, pillWidth, canvas.glyphHeight * BODY_SCALE + 4, line.status.color)
            canvas.text(MARGIN + 6, y, line.status.badge, BG, BODY_SCALE)

            val labelX = MARGIN + pillWidth + 14
            canvas.text(labelX, y, line.label, TEXT, BODY_SCALE)
            canvas.text(labelX + labelWidth, y, line.value, DIM, BODY_SCALE)

            y += lineHeight
        }

        canvas.presentAll()
    }

    private fun awaitContinue(canvas: Canvas?) {
        var deadline = Clock.uptimeMillis() + AUTO_ADVANCE_MS
        var lastReceived = Keyboard.received
        var lastRender = 0UL
        var previousA = true
        var previousStart = true

        while (true) {
            Keyboard.poll()
            if (Serial.tryReadChar() != null) return

            val event = GamepadService.pump()
            GamepadService.poll()

            val now = Clock.uptimeMillis()

            if (Keyboard.received != lastReceived) {
                lastReceived = Keyboard.received
                deadline = now + AUTO_ADVANCE_MS
            }
            if (event != null) deadline = now + AUTO_ADVANCE_MS

            val a = GamepadService.isDown(0, Pad.A)
            val start = GamepadService.isDown(0, Pad.START)
            if ((a && !previousA) || (start && !previousStart)) return
            previousA = a
            previousStart = start

            if (now >= deadline) return

            if (canvas != null && now - lastRender >= RENDER_INTERVAL_MS) {
                lastRender = now
                val remaining = deadline - now
                val permille = (remaining * 1000UL / AUTO_ADVANCE_MS).toInt()
                render(canvas, collect(), permille)
            }

            Cpu.waitForInterrupt()
        }
    }

    private fun serialDump(lines: List<Line>) {
        Serial.print("\nKurtOS boot report\n")
        for (line in lines) {
            if (line.status == Status.HEAD) {
                Serial.print("\n[${line.label}]\n")
                continue
            }
            val badge = if (line.status.badge.isBlank()) "    " else line.status.badge
            Serial.print("  $badge ${line.label.padEnd(10)} ${line.value}\n")
        }
        Serial.print("\n")
    }
}
