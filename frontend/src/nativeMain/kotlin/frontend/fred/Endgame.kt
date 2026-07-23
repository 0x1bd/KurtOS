package frontend.fred

import kapi.Audio
import kapi.Input
import kapi.Sfx
import kapi.Surface
import kapi.Time
import kapi.ui.Canvas
import kapi.ui.NavInput

object Endgame {
    private const val CREDITS = 0
    private const val RESET = 1

    fun finale(surface: Surface) {
        val canvas = Canvas(surface)
        canvas.offsetX = 0
        canvas.offsetY = 0

        Audio.play(Sfx.PANIC)
        crash(canvas)
        runPhase(canvas, 6500, Sfx.CRESCENDO, CREDITS)
        runPhase(canvas, 2000, Sfx.RESET, RESET)

        NavInput.prime()
    }

    private fun crash(canvas: Canvas) {
        NavInput.prime()
        val start = Time.uptimeMillis()
        while (true) {
            Input.poll()
            val nav = NavInput.read()
            if (nav.activate) break
            val t = (Time.uptimeMillis() - start).toLong()
            panic(canvas, canvas.width, canvas.height, t)
            canvas.present()
            Time.idle()
        }
    }

    private fun panic(canvas: Canvas, w: Int, h: Int, t: Long) {
        canvas.fill(0, 0, w, h, 0x000A1E5Au)
        val margin = w / 14
        val lines = listOf(
            "KERNEL PANIC - NOT SYNCING:",
            "TOO MUCH FRED",
            "",
            "FRED FAULT AT 0xF3ED IN fred.ko",
            "RAX=FRED RBX=FRED RCX=FRED",
            "RSI=FRED RDI=FRED RBP=FRED",
            "CR2=FRED CR3=FRED EFLAGS=FRED",
            "",
            "CALL TRACE:",
            "  [<FRED>] fred_everything+0xFRED",
            "  [<FRED>] do_fred+0x1F/0x2F"
        )
        val s = fit(canvas, lines, w - margin * 2)
        val lh = canvas.glyphHeight * s + 6
        var y = h / 8
        for (line in lines) {
            canvas.text(margin, y, line, 0x00E6ECFFu, s)
            y += lh
        }

        if ((t / 500) % 2 == 0L) {
            canvas.text(margin, y + lh, "PRESS ENTER TO CONTINUE", 0x00FFFFFFu, s)
        }

        val icon = Fred.face()
        val is2 = maxOf(2, h / 100)
        canvas.icon(icon, w - icon.width * is2 - w / 20, h - icon.height * is2 - h / 20, is2)
    }

    private fun runPhase(canvas: Canvas, durationMs: Long, sfx: Sfx, kind: Int) {
        NavInput.prime()
        Audio.play(sfx)
        val start = Time.uptimeMillis()
        while (true) {
            Input.poll()
            val nav = NavInput.read()
            val t = (Time.uptimeMillis() - start).toLong()
            if (t >= durationMs || nav.back || nav.activate) break

            val w = canvas.width
            val h = canvas.height
            when (kind) {
                CREDITS -> credits(canvas, w, h, t)
                RESET -> reset(canvas, w, h, t)
            }
            canvas.present()
            Time.idle()
        }
    }

    private fun credits(canvas: Canvas, w: Int, h: Int, t: Long) {
        canvas.fill(0, 0, w, h, 0x00000000u)
        val lines = listOf(
            "F R E D",
            "",
            "a KurtOS production",
            "",
            "FRED ......... as himself",
            "THE FREDS .... as themselves",
            "THE NAVBAR ... as itself",
            "",
            "DIRECTED BY .. FRED",
            "PRODUCED BY .. FRED",
            "CATERING BY .. FRED",
            "git blame .... YOU",
            "",
            "no cats were consulted",
            "",
            "THE END ?",
        )
        val s = fit(canvas, lines, w * 9 / 10)
        val lh = canvas.glyphHeight * s + h / 40
        val scroll = (t * (h + lines.size * lh) / 6500).toInt()
        var y = h - scroll
        for (line in lines) {
            if (y > -lh && y < h) {
                canvas.text((w - canvas.textWidth(line, s)) / 2, y, line, 0x00F2E9FFu, s)
            }
            y += lh
        }
    }

    private fun reset(canvas: Canvas, w: Int, h: Int, t: Long) {
        if (t < 380) {
            canvas.fill(0, 0, w, h, 0x00FFFFFFu)
            return
        }
        canvas.fill(0, 0, w, h, 0x00060608u)
        val icon = Fred.face()
        val scale = maxOf(3, h / 60)
        val fx = (w - icon.width * scale) / 2
        val fy = (h - icon.height * scale) / 2
        canvas.icon(icon, fx, fy, scale)
        val s = maxOf(2, w / 260)
        val msg = "what? :3"
        canvas.text((w - canvas.textWidth(msg, s)) / 2, fy + icon.height * scale + h / 20, msg, 0x00F2E9FFu, s)
    }

    private fun fit(canvas: Canvas, lines: List<String>, maxWidth: Int): Int {
        var widest = 1
        for (line in lines) widest = maxOf(widest, canvas.textWidth(line, 1))
        return maxOf(1, maxWidth / widest)
    }
}
