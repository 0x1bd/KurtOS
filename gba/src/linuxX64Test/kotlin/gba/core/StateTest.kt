package gba.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun word(rom: ByteArray, offset: Int, value: Int) {
    for (i in 0 until 4) rom[offset + i] = ((value shr (i * 8)) and 0xFF).toByte()
}

private fun rom(): ByteArray {
    val image = ByteArray(0x1000)

    word(image, 0x00, 0xE3A00000.toInt())
    word(image, 0x04, 0xE3A0140A.toInt())
    word(image, 0x08, 0xE2811001.toInt())
    word(image, 0x0C, 0xE5C11000.toInt())
    word(image, 0x10, 0xEAFFFFFC.toInt())

    return image
}

private fun run(console: GBA, frames: Int) {
    for (frame in 0 until frames) console.runFrame()
}

class StateTest {
    @Test
    fun restoredMachineMatchesBitForBit() {
        val image = rom()

        val original = GBA(image)
        run(original, 8)

        val snapshot = original.saveState()

        run(original, 12)
        val expected = original.saveState()

        val restored = GBA(image)
        assertTrue(restored.loadState(snapshot))

        run(restored, 12)

        assertContentEquals(expected, restored.saveState())
    }

    @Test
    fun snapshotIsIndependentOfTheLiveMachine() {
        val image = rom()

        val console = GBA(image)
        run(console, 8)

        val snapshot = console.saveState()
        val copy = snapshot.copyOf()

        run(console, 20)

        assertContentEquals(copy, snapshot)
    }

    @Test
    fun junkIsRejected() {
        assertFalse(GBA(rom()).loadState(ByteArray(64)))
    }

    @Test
    fun truncatedStateIsRejected() {
        val console = GBA(rom())
        run(console, 4)

        val snapshot = console.saveState()

        assertFalse(GBA(rom()).loadState(snapshot.copyOf(snapshot.size / 2)))
    }

    @Test
    fun stateFromAnotherVersionIsRejected() {
        val console = GBA(rom())
        val snapshot = console.saveState()

        snapshot[4] = (snapshot[4] + 1).toByte()

        assertFalse(GBA(rom()).loadState(snapshot))
    }
}
