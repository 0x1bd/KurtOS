package gameboy.core

fun interface RTCClock {
    fun epochSeconds(): Long
}

class RTC(private val clock: RTCClock?) {
    private var seconds = 0
    private var minutes = 0
    private var hours = 0
    private var days = 0
    private var halted = false
    private var carry = false

    private var anchor = clock?.epochSeconds() ?: 0L

    private val latched = IntArray(5)
    private var latchSignal = -1

    val present: Boolean get() = clock != null

    fun read(register: Int): Int = when (register) {
        SECONDS -> latched[0]
        MINUTES -> latched[1]
        HOURS -> latched[2]
        DAYS_LOW -> latched[3]
        DAYS_HIGH -> latched[4]
        else -> 0xFF
    }

    fun write(register: Int, value: Int) {
        tick()

        when (register) {
            SECONDS -> seconds = value and 0x3F
            MINUTES -> minutes = value and 0x3F
            HOURS -> hours = value and 0x1F
            DAYS_LOW -> days = (days and 0x100) or (value and 0xFF)
            DAYS_HIGH -> {
                days = (days and 0xFF) or ((value and 0x01) shl 8)
                halted = value and 0x40 != 0
                carry = value and 0x80 != 0
            }
            else -> return
        }

        latch()
    }

    fun writeLatch(value: Int) {
        if (latchSignal == 0x00 && value == 0x01) latch()
        latchSignal = value
    }

    fun tick() {
        val now = clock?.epochSeconds() ?: return
        if (now <= 0L) return

        if (anchor == 0L) {
            anchor = now
            return
        }

        val elapsed = now - anchor
        anchor = now

        if (halted || elapsed <= 0L) return

        advance(elapsed)
    }

    private fun advance(elapsed: Long) {
        var total = seconds.toLong() +
            minutes.toLong() * 60L +
            hours.toLong() * 3600L +
            days.toLong() * 86400L +
            elapsed

        seconds = (total % 60L).toInt()
        total /= 60L
        minutes = (total % 60L).toInt()
        total /= 60L
        hours = (total % 24L).toInt()
        total /= 24L

        if (total > 0x1FF) carry = true
        days = (total and 0x1FF).toInt()
    }

    private fun latch() {
        tick()

        latched[0] = seconds
        latched[1] = minutes
        latched[2] = hours
        latched[3] = days and 0xFF
        latched[4] = ((days shr 8) and 0x01) or
            (if (halted) 0x40 else 0) or
            (if (carry) 0x80 else 0)
    }

    fun save(): ByteArray {
        tick()

        val out = ByteArray(SAVE_BYTES)

        writeInt(out, 0, seconds)
        writeInt(out, 4, minutes)
        writeInt(out, 8, hours)
        writeInt(out, 12, days and 0xFF)
        writeInt(out, 16, ((days shr 8) and 0x01) or (if (halted) 0x40 else 0) or (if (carry) 0x80 else 0))

        for (i in 0 until 5) writeInt(out, 20 + i * 4, latched[i])

        writeLong(out, 40, anchor)

        return out
    }

    fun load(data: ByteArray, offset: Int) {
        if (offset + SAVE_BYTES > data.size) return

        seconds = readInt(data, offset) and 0x3F
        minutes = readInt(data, offset + 4) and 0x3F
        hours = readInt(data, offset + 8) and 0x1F

        val low = readInt(data, offset + 12) and 0xFF
        val high = readInt(data, offset + 16)

        days = low or ((high and 0x01) shl 8)
        halted = high and 0x40 != 0
        carry = high and 0x80 != 0

        for (i in 0 until 5) latched[i] = readInt(data, offset + 20 + i * 4) and 0xFF

        anchor = readLong(data, offset + 40)

        tick()
        latch()
    }

    private fun writeInt(out: ByteArray, index: Int, value: Int) {
        for (i in 0 until 4) out[index + i] = ((value shr (i * 8)) and 0xFF).toByte()
    }

    private fun writeLong(out: ByteArray, index: Int, value: Long) {
        for (i in 0 until 8) out[index + i] = ((value shr (i * 8)) and 0xFF).toByte()
    }

    private fun readInt(data: ByteArray, index: Int): Int {
        var value = 0
        for (i in 0 until 4) value = value or ((data[index + i].toInt() and 0xFF) shl (i * 8))
        return value
    }

    private fun readLong(data: ByteArray, index: Int): Long {
        var value = 0L
        for (i in 0 until 8) value = value or ((data[index + i].toLong() and 0xFF) shl (i * 8))
        return value
    }

    companion object {
        const val SECONDS = 0x08
        const val MINUTES = 0x09
        const val HOURS = 0x0A
        const val DAYS_LOW = 0x0B
        const val DAYS_HIGH = 0x0C

        const val SAVE_BYTES = 48
    }
}
