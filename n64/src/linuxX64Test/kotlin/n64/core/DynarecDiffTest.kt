package n64.core

import kotlin.test.Test

class DynarecDiffTest {
    @Test
    fun diff() {
        if (environment("KURTOS_JITDIFF") == null) return
        val img = game() ?: return

        val jitConsole = N64(img)
        val ref = N64(img, forceNoDynarec = true)
        val dynarec = jitConsole.dynarec ?: run {
            println("[jitdiff] no dynarec (set KURTOS_JIT=1)")
            return
        }

        val a = jitConsole.cpu
        val b = ref.cpu
        b.suppressAutoEvents = true

        var unit = 0L
        var stop = false
        val maxUnits = environment("KURTOS_JITDIFF_MAX")?.toLongOrNull() ?: 500_000_000L

        dynarec.onUnit = hook@{ startPc, n, isBlock ->
            if (stop) return@hook
            var guard = 0
            if (isBlock) {
                while (b.count < a.count && guard < 400) { b.step(); guard++ }
            } else {
                b.suppressAutoEvents = false
                b.step()
                b.suppressAutoEvents = true
            }

            var badGpr = false
            for (i in 0 until 32) if (a.gpr[i] != b.gpr[i]) badGpr = true
            val badHiLo = a.hi != b.hi || a.lo != b.lo
            val badPc = a.pc != b.pc

            if (badGpr || badHiLo || badPc) {
                println("[jitdiff] MISMATCH unit=$unit startPc=${h(startPc)} n=$n block=$isBlock")
                println("[jitdiff]   pc dynarec=${h(a.pc)} ref=${h(b.pc)} count dynarec=${a.count} ref=${b.count}")
                for (i in 0 until 32) {
                    if (a.gpr[i] != b.gpr[i]) println("[jitdiff]   gpr[$i] dynarec=${h(a.gpr[i])} ref=${h(b.gpr[i])}")
                }
                if (a.hi != b.hi) println("[jitdiff]   hi dynarec=${h(a.hi)} ref=${h(b.hi)}")
                if (a.lo != b.lo) println("[jitdiff]   lo dynarec=${h(a.lo)} ref=${h(b.lo)}")
                dumpBlock(jitConsole, startPc, n)
                stop = true
                return@hook
            }

            if (isBlock) {
                if (a.count > a.nextEventCount) {
                    b.count = a.count
                    if (b.count > b.nextEventCount) ref.triggerEvent()
                }
            }

            unit++
            if (unit >= maxUnits) stop = true
        }

        for (frame in 0 until 400) {
            jitConsole.runFrame()
            if (stop) break
        }
        println("[jitdiff] done units=$unit stop=$stop lastPc=${h(a.pc)}")
    }

    private fun dumpBlock(console: N64, startPc: Long, n: Int) {
        val phys = startPc.toInt() and 0x1FFFFFFF
        if (phys.toUInt() >= RDRAM_SIZE.toUInt()) {
            println("[jitdiff]   (start not in rdram)")
            return
        }
        println("[jitdiff]   block ops:")
        for (k in 0 until n + 2) {
            val op = console.ramRead32(phys + k * 4)
            println("[jitdiff]     ${h((startPc + k * 4L))}: ${h(op.toLong() and 0xFFFFFFFFL)} op=${op ushr 26} f=${op and 0x3F} rs=${(op ushr 21) and 0x1F} rt=${(op ushr 16) and 0x1F} imm=${(op.toShort()).toInt()}")
        }
    }

    private fun h(v: Long) = "0x${v.toULong().toString(16)}"
    private fun h(v: Int) = "0x${v.toUInt().toString(16)}"
}
