package gba.core

import kurtos.testkit.TestLog

import kapi.state.StateReader
import kotlin.test.Test
import kotlin.test.assertEquals
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

private fun resultRegister(console: GBA): Int {
    val reader = StateReader(console.saveState())

    reader.int()
    reader.int()

    val registers = IntArray(16)
    reader.ints(registers)

    return registers[12]
}

private fun runTestRom(name: String): Int? {
    val image = testRom(name) ?: return null

    val console = GBA(image)
    for (frame in 0 until 240) console.runFrame()

    return resultRegister(console)
}

private fun skipped(rom: String) {
    TestLog.skip("gba", rom, "git submodule update --init --recursive")
}

class TestRomTest {
    @Test
    fun armInstructionsPass() {
        val failing = runTestRom("gba-tests/arm/arm.gba") ?: return skipped("gba-tests/arm/arm.gba")
        assertEquals(0, failing, "arm.gba: first failing test is $failing")
    }

    @Test
    fun thumbInstructionsPass() {
        val failing = runTestRom("gba-tests/thumb/thumb.gba") ?: return skipped("gba-tests/thumb/thumb.gba")
        assertEquals(0, failing, "thumb.gba: first failing test is $failing")
    }

    @Test
    fun memoryAccessesPass() {
        val failing = runTestRom("gba-tests/memory/memory.gba") ?: return skipped("gba-tests/memory/memory.gba")
        assertEquals(0, failing, "memory.gba: first failing test is $failing")
    }
}
