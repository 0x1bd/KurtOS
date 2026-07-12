package kapi.state

class StateWriter {
    private var buffer = ByteArray(1024)
    private var size = 0

    fun int(value: Int) {
        reserve(4)
        for (i in 0 until 4) buffer[size + i] = ((value shr (i * 8)) and 0xFF).toByte()
        size += 4
    }

    fun long(value: Long) {
        reserve(8)
        for (i in 0 until 8) buffer[size + i] = ((value shr (i * 8)) and 0xFF).toByte()
        size += 8
    }

    fun bool(value: Boolean) = int(if (value) 1 else 0)

    fun bytes(value: ByteArray) {
        int(value.size)
        reserve(value.size)
        value.copyInto(buffer, size)
        size += value.size
    }

    fun ints(value: IntArray) {
        int(value.size)
        for (element in value) int(element)
    }

    fun shorts(value: ShortArray) {
        int(value.size)
        reserve(value.size * 2)
        for (element in value) {
            val raw = element.toInt()
            buffer[size] = (raw and 0xFF).toByte()
            buffer[size + 1] = ((raw shr 8) and 0xFF).toByte()
            size += 2
        }
    }

    fun toByteArray(): ByteArray = buffer.copyOf(size)

    private fun reserve(extra: Int) {
        if (size + extra <= buffer.size) return

        var capacity = buffer.size * 2
        while (capacity < size + extra) capacity *= 2

        buffer = buffer.copyOf(capacity)
    }
}

class StateReader(private val data: ByteArray) {
    private var position = 0

    var valid = true
        private set

    fun int(): Int {
        if (!take(4)) return 0

        var value = 0
        for (i in 0 until 4) value = value or ((data[position + i].toInt() and 0xFF) shl (i * 8))
        position += 4

        return value
    }

    fun long(): Long {
        if (!take(8)) return 0L

        var value = 0L
        for (i in 0 until 8) value = value or ((data[position + i].toLong() and 0xFF) shl (i * 8))
        position += 8

        return value
    }

    fun bool(): Boolean = int() != 0

    fun bytes(target: ByteArray) {
        val length = int()
        if (length != target.size || !take(length)) {
            valid = false
            return
        }

        data.copyInto(target, 0, position, position + length)
        position += length
    }

    fun ints(target: IntArray) {
        val length = int()
        if (length != target.size) {
            valid = false
            return
        }

        for (i in 0 until length) target[i] = int()
    }

    fun shorts(target: ShortArray) {
        val length = int()
        if (length != target.size || !take(length * 2)) {
            valid = false
            return
        }

        for (i in 0 until length) {
            val low = data[position].toInt() and 0xFF
            val high = data[position + 1].toInt() and 0xFF
            target[i] = ((high shl 8) or low).toShort()
            position += 2
        }
    }

    private fun take(count: Int): Boolean {
        if (!valid) return false

        if (position + count > data.size) {
            valid = false
            return false
        }

        return true
    }
}
