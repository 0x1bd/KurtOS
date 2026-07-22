package gameboy.core

import kurtos.testkit.TestLog
import kurtos.testkit.TestPaths
import kurtos.testkit.readFile

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.getenv

private fun testRom(name: String): ByteArray? = readFile("${TestPaths.TEST_ROMS}/$name")

private fun serialOutput(name: String, frames: Int): String? {
    val image = testRom(name) ?: return null

    val console = GameBoy(image, intArrayOf(0x9BBC0F, 0x8BAC0F, 0x306230, 0x0F380F))
    val output = StringBuilder()

    console.onSerial = { byte -> output.append(byte.toChar()) }

    for (frame in 0 until frames) {
        console.runFrame()

        val text = output.toString()
        if (text.contains("Passed") || text.contains("Failed")) break
    }

    return output.toString()
}

private fun skipped(rom: String) {
    TestLog.skip("gameboy", rom, "git submodule update --init --recursive")
}

class TestRomTest {
    @Test
    fun cpuInstructionsPass() {
        val output = serialOutput("gb-test-roms/cpu_instrs/cpu_instrs.gb", 4000) ?: return skipped("gb-test-roms/cpu_instrs/cpu_instrs.gb")
        assertTrue(output.contains("Passed"), "cpu_instrs.gb reported: ${output.trim()}")
    }

    @Test
    fun instructionTimingPasses() {
        val output = serialOutput("gb-test-roms/instr_timing/instr_timing.gb", 2000) ?: return skipped("gb-test-roms/instr_timing/instr_timing.gb")
        assertTrue(output.contains("Passed"), "instr_timing.gb reported: ${output.trim()}")
    }
}
