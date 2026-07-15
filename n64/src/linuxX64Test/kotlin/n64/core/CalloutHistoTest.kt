package n64.core

import kotlin.test.Test

class CalloutHistoTest {
    @Test
    fun histo() {
        val image = game() ?: return
        val console = N64(image)
        for (frame in 0 until 400) {
            console.setButtons(if (frame > 200 && (frame / 30) % 2 == 0) 0x1000 else 0)
            console.runFrame()
        }
        console.rsp.calloutHistogram.fill(0)
        console.rsp.calloutCop2.fill(0)
        console.rsp.calloutMove.fill(0)
        console.rsp.calloutLwc2.fill(0)
        console.rsp.calloutSwc2.fill(0)
        for (frame in 0 until 100) console.runFrame()
        val h = console.rsp.calloutHistogram
        val total = h.sum()
        println("[histo] total callouts per frame: ${total / 100}")
        for (i in 0 until 64) {
            if (h[i] > 0) println("[histo] major $i: ${h[i] / 100}/frame ${(h[i] * 100 / total)}%")
        }
        for (i in 0 until 64) {
            if (console.rsp.calloutCop2[i] > 0) println("[histo] cop2 fn $i: ${console.rsp.calloutCop2[i] / 100}/frame")
        }
        for (i in 0 until 32) {
            if (console.rsp.calloutMove[i] > 0) println("[histo] cop2 move $i: ${console.rsp.calloutMove[i] / 100}/frame")
        }
        for (i in 0 until 32) {
            if (console.rsp.calloutLwc2[i] > 0) println("[histo] lwc2 kind $i: ${console.rsp.calloutLwc2[i] / 100}/frame")
        }
        for (i in 0 until 32) {
            if (console.rsp.calloutSwc2[i] > 0) println("[histo] swc2 kind $i: ${console.rsp.calloutSwc2[i] / 100}/frame")
        }
    }
}
