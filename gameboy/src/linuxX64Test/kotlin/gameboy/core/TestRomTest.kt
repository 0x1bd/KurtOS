package gameboy.core

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

@OptIn(ExperimentalForeignApi::class)
private fun testRom(name: String): ByteArray? {
    val root = getenv("KURTOS_TESTROMS")?.toKString() ?: return null
    val file = fopen("$root/$name", "rb") ?: return null

    fseek(file, 0, SEEK_END)
    val size = ftell(file).toInt()
    fseek(file, 0, SEEK_SET)

    val image = ByteArray(size)
    image.usePinned { fread(it.addressOf(0), 1u, size.toULong(), file) }
    fclose(file)

    return image
}

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

class TestRomTest {
    @Test
    fun cpuInstructionsPass() {
        val output = serialOutput("cpu_instrs.gb", 4000) ?: return
        assertTrue(output.contains("Passed"), "cpu_instrs.gb reported: ${output.trim()}")
    }

    @Test
    fun instructionTimingPasses() {
        val output = serialOutput("instr_timing.gb", 2000) ?: return
        assertTrue(output.contains("Passed"), "instr_timing.gb reported: ${output.trim()}")
    }
}
