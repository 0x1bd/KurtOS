package n64.core

import kotlin.test.Test

class RspDiffTest {
    @Test
    fun report() {
        if (environment("KURTOS_RSPDIFF") == null) return
        val image = game() ?: return

        val a = N64(image)
        val b = N64(image, forceNoRspDynarec = true)

        if (a.rsp.dynarec == null) {
            println("[rspdiff] no rsp dynarec (unset KURTOS_NORSPJIT)")
            return
        }

        val frames = environment("KURTOS_FRAMES")?.toIntOrNull() ?: 600

        for (frame in 0 until frames) {
            val pulse = (frame / 25) % 2 == 0
            val buttons = when {
                frame in 200..700 && pulse -> if ((frame / 50) % 2 == 0) 0x1000 else 0x8000
                frame in 701..1000 && pulse -> 0x8000
                else -> 0
            }
            a.setButtons(buttons)
            b.setButtons(buttons)
            a.runFrame()
            b.runFrame()

            var diff = -1
            val fa = a.vi.frame
            val fb = b.vi.frame
            for (i in fa.indices) {
                if (fa[i] != fb[i]) {
                    diff = i
                    break
                }
            }

            if (diff >= 0) {
                println(
                    "[rspdiff] MISMATCH frame=$frame pixel=$diff" +
                        " dyn=0x${(fa[diff].toInt() and 0xFFFF).toString(16)}" +
                        " ref=0x${(fb[diff].toInt() and 0xFFFF).toString(16)}" +
                        " gfxTasks=${a.rsp.gfxTasks} gfxCycles=${a.rsp.gfxCycles}/${b.rsp.gfxCycles}",
                )
                return
            }

            if (frame % 60 == 0) {
                println(
                    "[rspdiff] ok frame=$frame gfxTasks=${a.rsp.gfxTasks}" +
                        " audioTasks=${a.rsp.audioTasks} overruns=${a.rsp.overruns}",
                )
            }
        }

        println("[rspdiff] all $frames frames identical, gfxTasks=${a.rsp.gfxTasks} audioTasks=${a.rsp.audioTasks}")
    }
}
