package gba

import gba.core.GBA
import gba.core.Keypad
import gba.core.PPU
import gba.core.RtcClock
import gba.core.RtcTime
import kapi.Time
import kapi.emu.Button
import kapi.emu.Emulator
import kapi.emu.EmulatorSession
import kapi.emu.Video

object GbaEmulator : Emulator {
    override val id = "gba"
    override val system = "GBA"
    override val extensions = listOf(".gba")
    override val frameMicros = 16743UL

    override fun load(image: ByteArray): EmulatorSession? {
        val console = GBA(image, SystemClock)
        if (!console.cartridge.supported) return null
        return Session(console)
    }

    private object SystemClock : RtcClock {
        override fun now(): RtcTime {
            val wall = Time.now()

            if (wall != null) {
                return RtcTime(wall.year, wall.month, wall.day, wall.hour, wall.minute, wall.second)
            }

            return fromUptime(Time.uptimeMillis() / 1000UL)
        }

        private fun fromUptime(seconds: ULong): RtcTime {
            val total = seconds.toLong()
            val days = total / 86400
            val rest = total % 86400

            var year = 2024
            var remaining = days

            while (true) {
                val length = if (leap(year)) 366L else 365L
                if (remaining < length) break
                remaining -= length
                year++
            }

            var month = 1
            while (true) {
                val length = monthLength(year, month).toLong()
                if (remaining < length) break
                remaining -= length
                month++
            }

            return RtcTime(
                year,
                month,
                (remaining + 1).toInt(),
                (rest / 3600).toInt(),
                ((rest % 3600) / 60).toInt(),
                (rest % 60).toInt(),
            )
        }

        private fun leap(year: Int): Boolean =
            year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)

        private fun monthLength(year: Int, month: Int): Int = when (month) {
            1, 3, 5, 7, 8, 10, 12 -> 31
            4, 6, 9, 11 -> 30
            else -> if (leap(year)) 29 else 28
        }
    }

    private class Session(private val console: GBA) : EmulatorSession {
        override val video = Video.HighColor(PPU.WIDTH, PPU.HEIGHT, console.frame)

        override val audioSamples get() = console.apu.samples
        override val audioFrames get() = console.apu.frames

        override fun setButtons(buttons: Int) {
            var mask = 0
            if (buttons and Button.A != 0) mask = mask or Keypad.A
            if (buttons and Button.B != 0) mask = mask or Keypad.B
            if (buttons and Button.SELECT != 0) mask = mask or Keypad.SELECT
            if (buttons and Button.START != 0) mask = mask or Keypad.START
            if (buttons and Button.RIGHT != 0) mask = mask or Keypad.RIGHT
            if (buttons and Button.LEFT != 0) mask = mask or Keypad.LEFT
            if (buttons and Button.UP != 0) mask = mask or Keypad.UP
            if (buttons and Button.DOWN != 0) mask = mask or Keypad.DOWN
            if (buttons and Button.R != 0) mask = mask or Keypad.R
            if (buttons and Button.L != 0) mask = mask or Keypad.L
            console.keypad.setButtons(mask)
        }

        override fun runFrame() = console.runFrame()

        override fun drainAudio() = console.apu.drain()

        override fun describe(): String? = null

        override fun saveData(): ByteArray = console.cartridge.saveData()

        override fun loadSaveData(data: ByteArray) = console.cartridge.loadSaveData(data)

        override fun saveVersion(): Int = console.cartridge.saveVersion
    }
}
