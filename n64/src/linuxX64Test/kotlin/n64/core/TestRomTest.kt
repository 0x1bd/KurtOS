package n64.core

import kurtos.testkit.TestLog
import kurtos.testkit.TestPaths
import kurtos.testkit.findFile
import kurtos.testkit.readFile

import kotlin.test.Test
import kotlin.test.assertTrue

class TestRomTest {
    @Test
    fun cpu() = suite(environment("KURTOS_N64TESTLIST")?.split(",") ?: DEFAULT)

    @Test
    fun rsp() = suite(environment("KURTOS_N64RSPLIST")?.split(",") ?: RSP)

    @Test
    fun nemu64() {
        val path = TestPaths.NEMU64_ROM
        val image = readFile(path) ?: run {
            TestLog.skip("nemu64", "ROM not built at $path", "needs rustup + nust64")
            return
        }

        val console = N64(image)
        val log = StringBuilder()
        val stream = environment("KURTOS_NEMU64_STREAM") != null
        console.onSerial = {
            log.append(it)
            if (stream) print(it)
        }

        var lastLength = -1
        for (frame in 0 until 6000) {
            console.runFrame()
            if (log.contains("Finished in")) break
            if (frame % 500 == 0) {
                TestLog.info("nemu64", "frame=$frame serial=${log.length} overruns=${console.rsp.overruns}")
                if (log.length == lastLength && frame > 0) {
                    TestLog.info("nemu64", "stalled, last output: ...${log.takeLast(120).toString().replace('\n', ' ')}")
                }
                lastLength = log.length
            }
        }

        val text = log.toString()
        val failures = Regex("Test '(.+?)' failed").findAll(text).map { it.groupValues[1] }.toList()
        val ran = Regex("Running (.+?)\\.\\.\\.").findAll(text).count()

        TestLog.report("nemu64", failures.map { "FAIL $it" } + "$ran tests, ${failures.size} failed")

        assertTrue(ran > 0, "nemu64-test produced no output")

        val allowed = environment("KURTOS_NEMU64_ALLOWED")?.toIntOrNull() ?: KNOWN_FAILURES
        assertTrue(
            failures.size <= allowed,
            "nemu64-test: ${failures.size} failures, expected at most $allowed",
        )
    }

    private fun suite(names: List<String>) {
        val directory = TestPaths.PETERLEMON_N64

        var failures = 0
        var missing = 0
        val failed = StringBuilder()

        for (name in names) {
            val path = findFile(directory, "$name.N64") ?: findFile(directory, "$name.n64") ?: run {
                TestLog.skip("testrom", name, "git submodule update --init --recursive")
                missing++
                continue
            }
            val image = readFile(path) ?: continue
            val console = N64(image)

            for (frame in 0 until 30) console.runFrame()

            val verdict = score(console)
            dumpNative(console, "test-$name")

            TestLog.info("testrom", "$name pass=${verdict.first} fail=${verdict.second}")
            if (verdict.second > 0) {
                failures++
                failed.append(" $name")
            }
        }

        assertTrue(failures == 0, "$failures test ROMs reported failures:$failed")
        assertTrue(missing < names.size, "no test ROMs found under $directory")
    }

    private fun score(console: N64): Pair<Int, Int> {
        val origin = console.vi.regs[VI_ORIGIN] and 0xFFFFFF
        val width = console.vi.regs[VI_WIDTH] and 0xFFF
        if (origin == 0 || width == 0) return 0 to 0

        val height = if (width >= 640) 480 else 240
        val type = console.vi.regs[VI_STATUS] and 3

        var red = 0
        var green = 0

        val columnStart = width * 78 / 100
        val headerEnd = height * 5 / 100

        for (y in headerEnd until height) {
            var start = -1
            var end = -1
            var count = 0

            for (x in 0 until width) {
                val pixel = if (type == 3) {
                    val word = console.ramRead32(origin + (y * width + x) * 4)
                    Triple(
                        ((word ushr 24) and 0xFF) shr 3,
                        ((word ushr 16) and 0xFF) shr 3,
                        ((word ushr 8) and 0xFF) shr 3,
                    )
                } else {
                    val half = console.ramRead16(origin + (y * width + x) * 2)
                    Triple((half ushr 11) and 0x1F, (half ushr 6) and 0x1F, (half ushr 1) and 0x1F)
                }

                val r = pixel.first
                val g = pixel.second
                val b = pixel.third

                if (r > 20 && g < 10 && b < 10) {
                    if (start < 0 || x - end > BOX_GAP) {
                        red += box(start, end, count, columnStart)
                        start = x
                        count = 0
                    }
                    end = x
                    count++
                }

                if (x >= columnStart && g > 12 && r < 10 && b < 10) green++
            }

            red += box(start, end, count, columnStart)
        }

        return green to red
    }

    private fun box(start: Int, end: Int, count: Int, columnStart: Int): Int {
        if (start < 0 || end < columnStart) return 0
        if (end - start + 1 > BOX_WIDTH) return 0
        return count
    }

    companion object {
        private const val BOX_WIDTH = 48
        private const val BOX_GAP = 8

        private const val KNOWN_FAILURES = 753

        private val DEFAULT = listOf(
            "CPUADD", "CPUADDU", "CPUAND", "CPUDADD", "CPUDADDU", "CPUDDIV", "CPUDDIVU",
            "CPUDIV", "CPUDIVU", "CPUDMULT", "CPUDMULTU", "CPUDSUB", "CPUDSUBU",
            "CPULOADSTORE", "CPUMULT", "CPUMULTU", "CPUNOR", "CPUOR", "CPUSHIFT",
            "CPUSUB", "CPUSUBU", "CPUXOR",
            "CP1ADD", "CP1SUB", "CP1MUL", "CP1DIV", "CP1SQRT", "CP1ABS", "CP1NEG",
            "CP1CVT", "CP1C", "CP1ROUND", "CP1FLOOR", "CP1CEIL", "CP1TRUNC",
        )

        private val RSP = listOf(
            "RSPCPUADD", "RSPCPUADDU", "RSPCPUAND", "RSPCPUNOR", "RSPCPUOR", "RSPCPUSLL",
            "RSPCPUSLLV", "RSPCPUSRA", "RSPCPUSRAV", "RSPCPUSRL", "RSPCPUSRLV", "RSPCPUSUB",
            "RSPCPUSUBU", "RSPCPUXOR",
            "RSPCP2VABS", "RSPCP2VADD", "RSPCP2VAND", "RSPCP2VCL", "RSPCP2VCR", "RSPCP2VEQ",
            "RSPCP2VLT", "RSPCP2VMACF", "RSPCP2VMADL", "RSPCP2VMADN", "RSPCP2VMUDL",
            "RSPCP2VMUDN", "RSPCP2VMULF", "RSPCP2VNOP", "RSPCP2VOR", "RSPCP2VRCP",
            "RSPCP2VRCPH", "RSPCP2VRCPL", "RSPCP2VSAR", "RSPCP2VSUB", "RSPCP2VXOR",
            "RSPCP2LTV", "RSPCP2LWV", "RSPTransposeMatrix", "RSPTransposeMatrixVMOV",
            "RSPIMEM", "RSPSORT",
        )
    }
}
