package gameboy.core

class ColorPalette(private val offset: Int, private val colors: IntArray) {
    private val data = ByteArray(SIZE)

    private var index = 0
    private var autoIncrement = false

    var version = 0
        private set

    fun readIndex(): Int = (index and 0x3F) or (if (autoIncrement) 0x80 else 0) or 0x40

    fun writeIndex(value: Int) {
        index = value and 0x3F
        autoIncrement = value and 0x80 != 0
    }

    fun readData(): Int = data[index].toInt() and 0xFF

    fun writeData(value: Int) {
        data[index] = value.toByte()
        refresh(index / 2)

        if (autoIncrement) index = (index + 1) and 0x3F
        version++
    }

    private fun refresh(entry: Int) {
        val low = data[entry * 2].toInt() and 0xFF
        val high = data[entry * 2 + 1].toInt() and 0xFF
        val value = (high shl 8) or low

        val red = value and 0x1F
        val green = (value shr 5) and 0x1F
        val blue = (value shr 10) and 0x1F

        colors[offset + entry] = (expand(red) shl 16) or (expand(green) shl 8) or expand(blue)
    }

    private fun expand(component: Int): Int = (component shl 3) or (component shr 2)

    companion object {
        const val ENTRIES = 32
        private const val SIZE = ENTRIES * 2
    }
}
