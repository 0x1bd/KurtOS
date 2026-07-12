package gba.core

data class RtcTime(
    val year: Int,
    val month: Int,
    val day: Int,
    val hour: Int,
    val minute: Int,
    val second: Int,
)

fun interface RtcClock {
    fun now(): RtcTime?
}

class Gpio(private val clock: RtcClock?) {
    var present = false

    var readable = false
        private set

    private var pinState = 0
    private var direction = 0

    private var transferStep = 0
    private var bitsRead = 0
    private var bits = 0
    private var bytesRemaining = 0
    private var commandActive = false
    private var command = 0
    private var control = 0x40

    private val time = IntArray(7)

    fun read(address: Int): Int = when (address and 0xFF) {
        0xC4 -> pinState
        0xC6 -> direction
        0xC8 -> 1
        else -> 0
    }

    fun write(address: Int, value: Int) {
        when (address and 0xFF) {
            0xC4 -> {
                pinState = (pinState and direction.inv()) or (value and direction)
                pinState = pinState and 0xF
                processPins()
            }
            0xC6 -> direction = value and 0xF
            0xC8 -> readable = value and 1 != 0
        }
    }

    private fun outputPins(pins: Int) {
        pinState = (pinState and direction) or (pins and direction.inv() and 0xF)
    }

    private fun processPins() {
        when (transferStep) {
            0 -> if (pinState and 5 == 1) transferStep = 1

            1 -> if (pinState and 5 == 5) transferStep = 2

            2 -> {
                if (pinState and 1 == 0) {
                    bits = bits and (1 shl bitsRead).inv()
                    bits = bits or (((pinState and 2) ushr 1) shl bitsRead)
                    return
                }

                if (pinState and 4 == 0) {
                    bitsRead = 0
                    bytesRemaining = 0
                    commandActive = false
                    transferStep = 0
                    return
                }

                if (direction and 2 != 0 && command and 0x80 == 0) {
                    bitsRead++
                    if (bitsRead == 8) processByte()
                    return
                }

                outputPins(5 or (output() shl 1))
                bitsRead++

                if (bitsRead == 8) {
                    bitsRead = 0
                    bytesRemaining--
                    if (bytesRemaining <= 0) {
                        commandActive = false
                        command = 0
                    }
                }
            }
        }
    }

    private fun processByte() {
        bytesRemaining--

        if (!commandActive) {
            if (bits and 0xF == 0x6) {
                command = bits
                val code = (bits ushr 4) and 0x7
                bytesRemaining = BYTE_COUNTS[code]
                commandActive = bytesRemaining > 0

                when (code) {
                    RESET -> control = 0
                    DATETIME, TIME -> updateClock()
                }
            }
        } else {
            when ((command ushr 4) and 0x7) {
                CONTROL -> control = bits and 0xFE
            }
        }

        bitsRead = 0

        if (bytesRemaining <= 0) {
            commandActive = false
            command = 0
        }
    }

    private fun output(): Int {
        val byte = when ((command ushr 4) and 0x7) {
            CONTROL -> control
            DATETIME, TIME -> {
                val index = 7 - bytesRemaining
                if (index in 0..6) time[index] else 0
            }
            else -> 0
        }

        return (byte ushr bitsRead) and 1
    }

    private fun updateClock() {
        val now = clock?.now() ?: return

        time[0] = bcd(now.year % 100)
        time[1] = bcd(now.month)
        time[2] = bcd(now.day)
        time[3] = weekday(now.year, now.month, now.day)

        if (control and 0x40 != 0) {
            time[4] = bcd(now.hour)
        } else {
            time[4] = bcd(now.hour % 12)
            if (now.hour >= 12) time[4] = time[4] or 0x40
        }

        time[5] = bcd(now.minute)
        time[6] = bcd(now.second)
    }

    private fun bcd(value: Int): Int = ((value / 10) shl 4) or (value % 10)

    private fun weekday(year: Int, month: Int, day: Int): Int {
        val offsets = intArrayOf(0, 3, 2, 5, 0, 3, 5, 1, 4, 6, 2, 4)
        var y = year
        if (month < 3) y--
        return (y + y / 4 - y / 100 + y / 400 + offsets[month - 1] + day) % 7
    }

    companion object {
        private const val RESET = 0
        private const val DATETIME = 2
        private const val CONTROL = 4
        private const val TIME = 6

        private val BYTE_COUNTS = intArrayOf(0, 0, 7, 0, 1, 0, 3, 0)

        fun detect(rom: ByteArray): Boolean {
            val signature = "SIIRTC_V".encodeToByteArray()

            for (base in 0..rom.size - signature.size) {
                var match = true
                for (i in signature.indices) {
                    if (rom[base + i] != signature[i]) {
                        match = false
                        break
                    }
                }
                if (match) return true
            }

            return false
        }
    }
}
