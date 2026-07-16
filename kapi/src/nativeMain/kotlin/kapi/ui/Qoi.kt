package kapi.ui

object Qoi {
    fun decode(data: ByteArray): Icon? {
        if (data.size < 22) return null
        if (data[0] != 'q'.code.toByte() || data[1] != 'o'.code.toByte() ||
            data[2] != 'i'.code.toByte() || data[3] != 'f'.code.toByte()
        ) return null

        val width = be32(data, 4)
        val height = be32(data, 8)
        if (width <= 0 || height <= 0 || width > MAX_SIDE || height > MAX_SIDE) return null

        val pixels = IntArray(width * height)
        val index = IntArray(64)

        var r = 0
        var g = 0
        var b = 0
        var a = 255

        var p = 14
        var at = 0

        while (at < pixels.size && p < data.size) {
            val b0 = data[p].toInt() and 0xFF
            p++

            when {
                b0 == 0xFE -> {
                    if (p + 3 > data.size) return null
                    r = data[p].toInt() and 0xFF
                    g = data[p + 1].toInt() and 0xFF
                    b = data[p + 2].toInt() and 0xFF
                    p += 3
                }

                b0 == 0xFF -> {
                    if (p + 4 > data.size) return null
                    r = data[p].toInt() and 0xFF
                    g = data[p + 1].toInt() and 0xFF
                    b = data[p + 2].toInt() and 0xFF
                    a = data[p + 3].toInt() and 0xFF
                    p += 4
                }

                b0 ushr 6 == 0 -> {
                    val stored = index[b0]
                    a = stored ushr 24
                    r = (stored shr 16) and 0xFF
                    g = (stored shr 8) and 0xFF
                    b = stored and 0xFF
                }

                b0 ushr 6 == 1 -> {
                    r = (r + ((b0 shr 4) and 3) - 2) and 0xFF
                    g = (g + ((b0 shr 2) and 3) - 2) and 0xFF
                    b = (b + (b0 and 3) - 2) and 0xFF
                }

                b0 ushr 6 == 2 -> {
                    if (p >= data.size) return null
                    val dg = (b0 and 0x3F) - 32
                    val b1 = data[p].toInt() and 0xFF
                    p++
                    r = (r + dg - 8 + ((b1 shr 4) and 0xF)) and 0xFF
                    g = (g + dg) and 0xFF
                    b = (b + dg - 8 + (b1 and 0xF)) and 0xFF
                }

                else -> {
                    var run = (b0 and 0x3F) + 1
                    val pixel = pack(a, r, g, b)
                    while (run > 0 && at < pixels.size) {
                        pixels[at++] = pixel
                        run--
                    }
                    continue
                }
            }

            val pixel = pack(a, r, g, b)
            index[(r * 3 + g * 5 + b * 7 + a * 11) % 64] = pixel
            pixels[at++] = pixel
        }

        if (at < pixels.size) return null
        return Icon(width, height, pixels)
    }

    private fun pack(a: Int, r: Int, g: Int, b: Int): Int =
        (a shl 24) or (r shl 16) or (g shl 8) or b

    private fun be32(data: ByteArray, at: Int): Int =
        ((data[at].toInt() and 0xFF) shl 24) or
            ((data[at + 1].toInt() and 0xFF) shl 16) or
            ((data[at + 2].toInt() and 0xFF) shl 8) or
            (data[at + 3].toInt() and 0xFF)

    private const val MAX_SIDE = 1024
}
