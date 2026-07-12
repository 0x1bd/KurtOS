package gameboy.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val BANK_SIZE = 0x4000

private class FakeClock(var seconds: Long) : RTCClock {
    override fun epochSeconds(): Long = seconds
}

private fun timerRom(): ByteArray {
    val image = ByteArray(4 * BANK_SIZE)
    image[0x147] = 0x10
    image[0x148] = 0x01
    image[0x149] = 0x02
    return image
}

private fun enable(cartridge: Cartridge) = cartridge.writeControl(0x0000, 0x0A)

private fun select(cartridge: Cartridge, register: Int) = cartridge.writeControl(0x4000, register)

private fun latch(cartridge: Cartridge) {
    cartridge.writeControl(0x6000, 0x00)
    cartridge.writeControl(0x6000, 0x01)
}

private fun read(cartridge: Cartridge, register: Int): Int {
    select(cartridge, register)
    return cartridge.readRam(0xA000)
}

class RTCTest {

    @Test
    fun countsElapsedSecondsWhileLatched() {
        val clock = FakeClock(1_000_000L)
        val cartridge = Cartridge(timerRom(), clock)

        enable(cartridge)
        latch(cartridge)

        clock.seconds += 3661

        latch(cartridge)

        assertEquals(1, read(cartridge, RTC.SECONDS))
        assertEquals(1, read(cartridge, RTC.MINUTES))
        assertEquals(1, read(cartridge, RTC.HOURS))
    }

    @Test
    fun readsStayFrozenUntilLatched() {
        val clock = FakeClock(1_000_000L)
        val cartridge = Cartridge(timerRom(), clock)

        enable(cartridge)
        latch(cartridge)

        clock.seconds += 42

        assertEquals(0, read(cartridge, RTC.SECONDS))

        latch(cartridge)

        assertEquals(42, read(cartridge, RTC.SECONDS))
    }

    @Test
    fun haltStopsTheClock() {
        val clock = FakeClock(1_000_000L)
        val cartridge = Cartridge(timerRom(), clock)

        enable(cartridge)

        select(cartridge, RTC.DAYS_HIGH)
        cartridge.writeRam(0xA000, 0x40)

        clock.seconds += 500

        latch(cartridge)

        assertEquals(0, read(cartridge, RTC.SECONDS))
        assertTrue(read(cartridge, RTC.DAYS_HIGH) and 0x40 != 0)
    }

    @Test
    fun daysOverflowSetsCarry() {
        val clock = FakeClock(1_000_000L)
        val cartridge = Cartridge(timerRom(), clock)

        enable(cartridge)
        latch(cartridge)

        assertFalse(read(cartridge, RTC.DAYS_HIGH) and 0x80 != 0)

        clock.seconds += 512L * 86400L

        latch(cartridge)

        assertEquals(0, read(cartridge, RTC.DAYS_LOW))
        assertTrue(read(cartridge, RTC.DAYS_HIGH) and 0x80 != 0)
    }

    @Test
    fun writingRegistersSetsTheClock() {
        val clock = FakeClock(1_000_000L)
        val cartridge = Cartridge(timerRom(), clock)

        enable(cartridge)

        select(cartridge, RTC.HOURS)
        cartridge.writeRam(0xA000, 23)
        select(cartridge, RTC.MINUTES)
        cartridge.writeRam(0xA000, 59)
        select(cartridge, RTC.SECONDS)
        cartridge.writeRam(0xA000, 59)

        clock.seconds += 1

        latch(cartridge)

        assertEquals(0, read(cartridge, RTC.SECONDS))
        assertEquals(0, read(cartridge, RTC.MINUTES))
        assertEquals(0, read(cartridge, RTC.HOURS))
        assertEquals(1, read(cartridge, RTC.DAYS_LOW))
    }

    @Test
    fun clockKeepsRunningAcrossASave() {
        val clock = FakeClock(1_000_000L)
        val cartridge = Cartridge(timerRom(), clock)

        enable(cartridge)
        clock.seconds += 120
        latch(cartridge)

        val saved = cartridge.saveData()!!

        val later = FakeClock(clock.seconds + 3600L)
        val restored = Cartridge(timerRom(), later)

        enable(restored)
        restored.loadSaveData(saved)
        latch(restored)

        assertEquals(1, read(restored, RTC.HOURS))
        assertEquals(2, read(restored, RTC.MINUTES))
    }

    @Test
    fun ramStillWorksAlongsideTheClock() {
        val clock = FakeClock(1_000_000L)
        val cartridge = Cartridge(timerRom(), clock)

        enable(cartridge)

        select(cartridge, 0x00)
        cartridge.writeRam(0xA000, 0x5A)

        assertEquals(0x5A, cartridge.readRam(0xA000))

        val saved = cartridge.saveData()!!
        assertEquals(0x2000 + RTC.SAVE_BYTES, saved.size)
    }
}
