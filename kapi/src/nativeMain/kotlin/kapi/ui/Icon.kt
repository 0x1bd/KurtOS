package kapi.ui

class Icon(val width: Int, val height: Int, private val pixels: IntArray) {
    fun draw(sink: PixelSink, x: Int, y: Int, scale: Int, flip: Boolean = false) {
        for (row in 0 until height) {
            var col = 0
            while (col < width) {
                val pixel = pixels[row * width + col]
                if (pixel ushr 24 < OPAQUE) {
                    col++
                    continue
                }

                var run = 1
                while (col + run < width && pixels[row * width + col + run] == pixel) run++

                val dstX = if (flip) x + (width - col - run) * scale else x + col * scale
                sink.fill(dstX, y + row * scale, run * scale, scale, (pixel and 0xFFFFFF).toUInt())
                col += run
            }
        }
    }

    companion object {
        private const val OPAQUE = 128

        val EMPTY = Icon(16, 16, IntArray(256))
    }
}
