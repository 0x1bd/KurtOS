package kapi.ui

object PixelIcons {

    class Icon(val width: Int, val height: Int, private val pixels: IntArray, private val palette: UIntArray) {
        fun draw(sink: PixelSink, x: Int, y: Int, scale: Int) {
            for (row in 0 until height) {
                var col = 0
                while (col < width) {
                    val index = pixels[row * width + col]
                    if (index == 0) {
                        col++
                        continue
                    }

                    var run = 1
                    while (col + run < width && pixels[row * width + col + run] == index) run++

                    sink.fill(x + col * scale, y + row * scale, run * scale, scale, palette[index])
                    col += run
                }
            }
        }
    }

    private fun icon(colors: Map<Char, UInt>, vararg rows: String): Icon {
        val width = rows.maxOf { it.length }
        val palette = UIntArray(colors.size + 1)
        val indices = mutableMapOf('.' to 0)

        colors.entries.forEachIndexed { i, entry ->
            palette[i + 1] = entry.value
            indices[entry.key] = i + 1
        }

        val pixels = IntArray(width * rows.size)
        for (row in rows.indices) {
            for (col in rows[row].indices) {
                pixels[row * width + col] = indices[rows[row][col]] ?: 0
            }
        }

        return Icon(width, rows.size, pixels, palette)
    }

    val GAMEPAD = icon(
        mapOf(
            'k' to 0x00181820u,
            'g' to 0x00D8D8D0u,
            'd' to 0x00303038u,
            'r' to 0x00E04838u,
            'y' to 0x00F8B800u,
        ),
        "..kkkkkkkkkkkk..",
        ".kggggggggggggk.",
        "kgggdgggggggrrgk",
        "kgdddddgggggrrgk",
        "kgggdggggyyggggk",
        "kgggdggggyyggggk",
        ".kggggggggggggk.",
        "..kkkkkkkkkkkk..",
    )

    val SPEAKER = icon(
        mapOf(
            'k' to 0x00181820u,
            'c' to 0x00F8D878u,
            'w' to 0x00F8F8F8u,
        ),
        "....kk......",
        "...kck..w...",
        "..kcck...w..",
        "kkccck.w..w.",
        "kcccck..w.w.",
        "kcccck..w.w.",
        "kcccck..w.w.",
        "kkccck..w.w.",
        "..kcck.w..w.",
        "...kck...w..",
        "....kk..w...",
    )

    val SPEAKER_MUTE = icon(
        mapOf(
            'k' to 0x00181820u,
            'c' to 0x00887860u,
            'r' to 0x00E04838u,
        ),
        "....kk......",
        "...kck......",
        "..kcck.r..r.",
        "kkccck..rr..",
        "kcccck..rr..",
        "kcccck.r..r.",
        "kcccck......",
        "kkccck......",
        "..kcck......",
        "...kck......",
        "....kk......",
    )

    val MUSHROOM = icon(
        mapOf(
            'k' to 0x00181820u,
            'r' to 0x00E04838u,
            'w' to 0x00F8F8F8u,
            'c' to 0x00F8D878u,
        ),
        "....kkkkkk....",
        "..kkrrrrrrkk..",
        ".krrwwrrwwrrk.",
        "krrwwwrrwwwrrk",
        "krrwwwrrwwwrrk",
        "krrrrrrrrrrrrk",
        "krwwrrrrrrwwrk",
        ".kkkkkkkkkkkk.",
        "..kcckcckcck..",
        "..kcckcckcck..",
        "...kkkkkkkk...",
    )

    val COIN = icon(
        mapOf(
            'k' to 0x00181820u,
            'y' to 0x00F8B800u,
            'w' to 0x00F8E890u,
            'd' to 0x00905800u,
        ),
        "..kkkkkk..",
        ".kyywwyyk.",
        "kyywkkwyyk",
        "kywwkkwwyk",
        "kywwkkwwyk",
        "kywwkkwwyk",
        "kywwkkwwyk",
        "kywwkkwwyk",
        "kyywkkwyyk",
        ".kyyddyyk.",
        "..kkkkkk..",
    )

    val QUESTION_BLOCK = icon(
        mapOf(
            'k' to 0x00181820u,
            'y' to 0x00F8B800u,
            'd' to 0x00905800u,
        ),
        "kkkkkkkkkkkk",
        "kdyyyyyyyydk",
        "kyyykkkkyyyk",
        "kyykkyykkyyk",
        "kyyyyyykkyyk",
        "kyyyyykkyyyk",
        "kyyyykkyyyyk",
        "kyyyyyyyyyyk",
        "kyyyykkyyyyk",
        "kdyyyyyyyydk",
        "kkkkkkkkkkkk",
    )

    val CARTRIDGE = icon(
        mapOf(
            'k' to 0x00181820u,
            'g' to 0x00A8A8B0u,
            'd' to 0x00606068u,
            'c' to 0x00F8D878u,
        ),
        ".kkkkkkkkkk.",
        "kggggggggggk",
        "kgccccccccgk",
        "kgccccccccgk",
        "kgccccccccgk",
        "kggggggggggk",
        "kgdgdgdgdggk",
        "kgdgdgdgdggk",
        "kgdgdgdgdggk",
        "kggggggggggk",
        ".kkkkkkkkkk.",
    )

    val TERMINAL = icon(
        mapOf(
            'k' to 0x00181820u,
            'd' to 0x00082018u,
            'e' to 0x0048D048u,
        ),
        "kkkkkkkkkkkk",
        "kddddddddddk",
        "kdeddddddddk",
        "kddedddddddk",
        "kdddeddddddk",
        "kddedddddddk",
        "kdedeeeedddk",
        "kddddddddddk",
        "kkkkkkkkkkkk",
        "...kkkkkk...",
        ".kkkkkkkkkk.",
    )
}
