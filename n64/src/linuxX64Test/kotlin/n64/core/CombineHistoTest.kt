package n64.core

import kotlin.test.Test

class CombineHistoTest {
    @Test
    fun histo() {
        if (environment("KURTOS_COMBINE_HISTO") == null) return
        val image = game() ?: return

        val console = N64(image)
        val warmup = environment("KURTOS_BENCH_WARMUP")?.toIntOrNull() ?: 300

        for (frame in 0 until warmup) {
            val buttons = when (frame) {
                in 250..252 -> 0x1000
                in 500..502 -> 0x8000
                in 560..562 -> 0x8000
                in 620..622 -> 0x8000
                else -> 0
            }
            console.setButtons(buttons)
            console.runFrame()
        }
        console.setButtons(0)

        console.rdp.diagEnabled = true
        for (frame in 0 until 100) console.runFrame()

        val slots = (0 until 128).filter { console.rdp.diagPixels[it] != 0L }.sortedByDescending { console.rdp.diagPixels[it] }
        var total = 0L
        for (slot in slots) total += console.rdp.diagPixels[slot]
        println("[histo] total pixels: $total over 100 frames")

        for (slot in slots.take(12)) {
            val px = console.rdp.diagPixels[slot]
            val combine = console.rdp.diagCombine[slot]
            val modes = console.rdp.diagModes[slot]
            val w0 = (combine ushr 32).toInt()
            val w1 = combine.toInt()
            val m0 = (modes ushr 32).toInt()
            val m1 = modes.toInt()
            val cycle = (m0 ushr 20) and 3
            val rgb0 = "${(w0 ushr 20) and 0xF}-${(w1 ushr 28) and 0xF}-${(w0 ushr 15) and 0x1F}-${(w1 ushr 15) and 7}"
            val rgb1 = "${(w0 ushr 5) and 0xF}-${(w1 ushr 24) and 0xF}-${w0 and 0x1F}-${(w1 ushr 6) and 7}"
            val alpha0 = "${(w0 ushr 12) and 7}-${(w1 ushr 12) and 7}-${(w0 ushr 9) and 7}-${(w1 ushr 9) and 7}"
            val alpha1 = "${(w1 ushr 21) and 7}-${(w1 ushr 3) and 7}-${(w1 ushr 18) and 7}-${w1 and 7}"
            println(
                "[histo] ${px * 100 / total}% px=$px cycle=$cycle rgb0=$rgb0 a0=$alpha0 rgb1=$rgb1 a1=$alpha1" +
                    " modes=${m0.toString(16)}/${m1.toString(16)}",
            )
        }
    }
}
