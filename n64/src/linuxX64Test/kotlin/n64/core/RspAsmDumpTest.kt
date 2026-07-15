package n64.core

import kotlin.test.Test

class RspAsmDumpTest {
    @Test
    fun dump() {
        if (environment("KURTOS_RSPASMDUMP") == null) return
        val c = RspCompiler()
        val vand = 0x4A000000 or (2 shl 16) or (3 shl 11) or (1 shl 6) or 40
        val len = c.compile(0, intArrayOf(vand), 1, false)
        val sb = StringBuilder()
        for (i in 0 until len) {
            sb.append(((c.asm.buf[i].toInt() and 0xFF) or 0x100).toString(16).substring(1))
        }
        println("[asmdump] len=$len bytes=$sb")
    }
}
