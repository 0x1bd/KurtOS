package n64.core

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.TimeSource

class BenchmarkTest {
    private fun measure(console: N64, label: String, warmup: Int, frames: Int): Double {
        for (frame in 0 until warmup) {
            console.setButtons(if (frame > 200 && (frame / 30) % 2 == 0) 0x1000 else 0)
            console.runFrame()
        }
        console.setButtons(0)

        val instructions = console.cpu.instructions
        val pixels = console.rdp.pixels
        val triangles = console.rdp.triangles
        val gfxCycles = console.rsp.gfxCycles

        val start = TimeSource.Monotonic.markNow()
        for (frame in 0 until frames) console.runFrame()
        val elapsed = start.elapsedNow()

        val fps = frames / elapsed.inWholeMicroseconds.toDouble() * 1e6
        val target = if (console.rom.pal) 50.0 else 60.0

        println(
            "[bench] $label: ${fps.toInt()} fps (${elapsed.inWholeMicroseconds / frames} us/frame)" +
                " ${(fps / target * 100).toInt()}% of realtime",
        )
        println(
            "[bench] per frame: cpu=${(console.cpu.instructions - instructions) / frames} instructions" +
                " rsp=${(console.rsp.gfxCycles - gfxCycles) / frames} cycles" +
                " rdp=${(console.rdp.triangles - triangles) / frames} triangles" +
                " ${(console.rdp.pixels - pixels) / frames} pixels",
        )

        return fps
    }

    @Test
    fun game() {
        val image = n64.core.game() ?: return

        val console = N64(image)
        println("[bench] ${console.describe()}")

        val warmup = environment("KURTOS_BENCH_WARMUP")?.toIntOrNull() ?: 300
        val frames = environment("KURTOS_BENCH_FRAMES")?.toIntOrNull() ?: 300
        val l0 = console.jit?.lookups ?: 0
        val c0 = console.jit?.compiles ?: 0
        val i0 = console.jit?.invalidations ?: 0
        val b0 = console.jit?.bails ?: 0
        val bi0 = console.jit?.blockInstrs ?: 0
        val si0 = console.jit?.stepInstrs ?: 0
        val fps = measure(console, "title", warmup, frames)
        console.jit?.let {
            val bi = it.blockInstrs - bi0
            val si = it.stepInstrs - si0
            println(
                "[bench] jit per frame: lookups=${(it.lookups - l0) / frames}" +
                    " compiles=${(it.compiles - c0) / frames}" +
                    " invalidations=${(it.invalidations - i0) / frames}" +
                    " bails=${(it.bails - b0) / frames}",
            )
            println(
                "[bench] jit coverage: blockInstrs=${bi / frames} stepInstrs=${si / frames}" +
                    " jit%=${if (bi + si > 0) bi * 100 / (bi + si) else 0}",
            )
        }

        if (environment("KURTOS_BENCH") == null) return

        val target = if (console.rom.pal) 50.0 else 60.0
        assertTrue(fps >= target, "only ${fps.toInt()} fps, need ${target.toInt()} for full speed")
    }
}
