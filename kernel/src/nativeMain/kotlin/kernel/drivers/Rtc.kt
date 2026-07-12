package kernel.drivers

import hal.Port
import kapi.DateTime

object Rtc {
    private const val INDEX: UShort = 0x70u
    private const val DATA: UShort = 0x71u

    private const val REG_SECOND = 0x00
    private const val REG_MINUTE = 0x02
    private const val REG_HOUR = 0x04
    private const val REG_DAY = 0x07
    private const val REG_MONTH = 0x08
    private const val REG_YEAR = 0x09
    private const val REG_STATUS_A = 0x0A
    private const val REG_STATUS_B = 0x0B

    var standardOffsetMinutes = 60
    var daylightSaving = true

    fun now(): DateTime? = utc()?.let { localize(it) }

    fun utc(): DateTime? {
        var previous = sample() ?: return null

        for (attempt in 0 until 8) {
            val current = sample() ?: return null
            if (current.contentEquals(previous)) return decode(current)
            previous = current
        }

        return decode(previous)
    }

    private fun localize(utc: DateTime): DateTime {
        val offset = standardOffsetMinutes + if (daylightSaving && summerTime(utc)) 60 else 0
        if (offset == 0) return utc

        val days = daysFromCivil(utc.year, utc.month, utc.day)
        val minutes = utc.hour * 60 + utc.minute + offset

        var shift = minutes / MINUTES_PER_DAY
        var rest = minutes % MINUTES_PER_DAY
        if (rest < 0) {
            rest += MINUTES_PER_DAY
            shift--
        }

        val civil = civilFromDays(days + shift)
        return DateTime(civil[0], civil[1], civil[2], rest / 60, rest % 60, utc.second)
    }

    private fun summerTime(utc: DateTime): Boolean {
        val start = daysFromCivil(utc.year, 3, lastSunday(utc.year, 3))
        val end = daysFromCivil(utc.year, 10, lastSunday(utc.year, 10))
        val today = daysFromCivil(utc.year, utc.month, utc.day)

        if (today < start || today > end) return false
        if (today == start) return utc.hour >= 1
        if (today == end) return utc.hour < 1
        return true
    }

    private fun lastSunday(year: Int, month: Int): Int {
        val weekday = weekdayOf(daysFromCivil(year, month, 31))
        return 31 - weekday
    }

    private fun weekdayOf(days: Int): Int {
        val weekday = (days + 4) % 7
        return if (weekday < 0) weekday + 7 else weekday
    }

    private fun daysFromCivil(year: Int, month: Int, day: Int): Int {
        val y = if (month <= 2) year - 1 else year
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val doy = (153 * (month + (if (month > 2) -3 else 9)) + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097 + doe - 719468
    }

    private fun civilFromDays(days: Int): IntArray {
        val z = days + 719468
        val era = (if (z >= 0) z else z - 146096) / 146097
        val doe = z - era * 146097
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val day = doy - (153 * mp + 2) / 5 + 1
        val month = mp + (if (mp < 10) 3 else -9)
        return intArrayOf(if (month <= 2) y + 1 else y, month, day)
    }

    private const val MINUTES_PER_DAY = 1440

    private fun sample(): IntArray? {
        for (attempt in 0 until 1000) {
            if (read(REG_STATUS_A) and 0x80 == 0) {
                return intArrayOf(
                    read(REG_SECOND),
                    read(REG_MINUTE),
                    read(REG_HOUR),
                    read(REG_DAY),
                    read(REG_MONTH),
                    read(REG_YEAR),
                    read(REG_STATUS_B),
                )
            }
        }

        return null
    }

    private fun decode(raw: IntArray): DateTime? {
        val status = raw[6]
        val binary = status and 0x04 != 0
        val hours24 = status and 0x02 != 0

        val rawHour = raw[2]
        val pm = !hours24 && (rawHour and 0x80 != 0)

        val second = convert(raw[0], binary)
        val minute = convert(raw[1], binary)
        var hour = convert(rawHour and 0x7F, binary)
        val day = convert(raw[3], binary)
        val month = convert(raw[4], binary)
        val year = convert(raw[5], binary)

        if (!hours24) {
            if (hour == 12) hour = 0
            if (pm) hour += 12
        }

        if (month !in 1..12 || day !in 1..31 || hour !in 0..23 || minute !in 0..59 || second !in 0..59) {
            return null
        }

        return DateTime(2000 + year, month, day, hour, minute, second)
    }

    private fun convert(value: Int, binary: Boolean): Int =
        if (binary) value else ((value shr 4) and 0xF) * 10 + (value and 0xF)

    private fun read(register: Int): Int {
        Port.write8(INDEX, register.toUByte())
        return Port.read8(DATA).toInt() and 0xFF
    }
}
