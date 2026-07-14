package n64.core

enum class SaveKind { NONE, EEPROM_4K, EEPROM_16K, SRAM, FLASH }

class ROM(image: ByteArray) {
    val bytes: ByteArray = normalize(image)

    val words: IntArray = IntArray(bytes.size / 4) { i ->
        val at = i * 4
        ((bytes[at].toInt() and 0xFF) shl 24) or
            ((bytes[at + 1].toInt() and 0xFF) shl 16) or
            ((bytes[at + 2].toInt() and 0xFF) shl 8) or
            (bytes[at + 3].toInt() and 0xFF)
    }

    val valid = bytes.size >= 0x1000

    val entry: Int = if (valid) word(0x08) else 0
    val name: String = if (valid) readName() else ""
    val gameCode: String = if (valid) readCode() else ""
    val country: Int = if (valid) bytes[0x3E].toInt() and 0xFF else 0

    val pal = country.toChar() in "DFIPSUXY"

    val cicSeed: Int = if (valid) cic() else 0x3F

    val save: SaveKind = if (valid) saveKind() else SaveKind.NONE

    fun word(at: Int): Int = if (at + 3 < bytes.size) words[at ushr 2] else 0

    private fun readName(): String {
        val builder = StringBuilder()
        for (i in 0x20 until 0x34) {
            val c = bytes[i].toInt() and 0xFF
            if (c in 0x20..0x7E) builder.append(c.toChar())
        }
        return builder.toString().trim()
    }

    private fun readCode(): String {
        val builder = StringBuilder()
        for (i in 0x3B until 0x3F) {
            val c = bytes[i].toInt() and 0xFF
            builder.append(if (c in 0x20..0x7E) c.toChar() else '?')
        }
        return builder.toString()
    }

    private fun cic(): Int {
        var crc = 0xFFFFFFFFu
        for (i in 0x40 until 0x1000) {
            crc = crc xor (bytes[i].toUInt() and 0xFFu)
            for (bit in 0 until 8) {
                crc = if (crc and 1u != 0u) (crc shr 1) xor 0xEDB88320u else crc shr 1
            }
        }
        return when ((crc xor 0xFFFFFFFFu).toInt()) {
            0x6170A4A1 -> 0x3F
            0x90BB6CB5.toInt() -> 0x3F
            0x009E9EA3 -> 0x3F
            0x0B050EE0 -> 0x78
            0x98BC2C86.toInt() -> 0x91
            0xACC8580A.toInt() -> 0x85
            else -> 0x3F
        }
    }

    private fun saveKind(): SaveKind {
        val id = gameCode.substring(0, 3)
        if (id in EEPROM_16K) return SaveKind.EEPROM_16K
        if (id in SRAM_GAMES) return SaveKind.SRAM
        if (id in FLASH_GAMES) return SaveKind.FLASH
        return SaveKind.EEPROM_4K
    }

    companion object {
        private val EEPROM_16K = setOf(
            "NB7", "NGT", "NFU", "NCW", "NCZ", "ND6", "NDO", "ND2", "N3D", "NMX",
            "NGC", "NIM", "NNB", "NMV", "NM8", "NEV", "NPP", "NUB", "NPD", "NRZ",
            "NR7", "NEP", "NYS",
        )

        private val SRAM_GAMES = setOf(
            "NTE", "NVL", "NFX", "NFZ", "NKI", "NMW", "NOB", "CZL", "NZL", "NSN",
            "NWQ", "NYW", "NYS", "NAL", "NB5", "NBK", "NBN", "NDY", "NFH", "NJM",
        )

        private val FLASH_GAMES = setOf(
            "NCC", "NDA", "NAF", "NJF", "NKJ", "NZS", "NM6", "NCK", "NMQ", "NPN",
            "NPF", "NPO", "CPS", "NRH", "NSQ", "NT9", "NW4", "NDP",
        )

        fun normalize(image: ByteArray): ByteArray {
            if (image.size < 4) return image

            val magic = ((image[0].toInt() and 0xFF) shl 24) or
                ((image[1].toInt() and 0xFF) shl 16) or
                ((image[2].toInt() and 0xFF) shl 8) or
                (image[3].toInt() and 0xFF)

            return when (magic) {
                0x80371240.toInt() -> image
                0x37804012 -> swap16(image)
                0x40123780 -> swap32(image)
                else -> image
            }
        }

        private fun swap16(image: ByteArray): ByteArray {
            val out = ByteArray(image.size)
            var i = 0
            while (i + 1 < image.size) {
                out[i] = image[i + 1]
                out[i + 1] = image[i]
                i += 2
            }
            return out
        }

        private fun swap32(image: ByteArray): ByteArray {
            val out = ByteArray(image.size)
            var i = 0
            while (i + 3 < image.size) {
                out[i] = image[i + 3]
                out[i + 1] = image[i + 2]
                out[i + 2] = image[i + 1]
                out[i + 3] = image[i]
                i += 4
            }
            return out
        }
    }
}
