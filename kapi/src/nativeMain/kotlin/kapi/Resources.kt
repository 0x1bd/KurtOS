package kapi

import kapi.res.EmbeddedAssets

object Resources {
    private val cache = HashMap<String, ByteArray?>()

    fun bytes(path: String): ByteArray? {
        if (cache.containsKey(path)) return cache[path]

        val encoded = EmbeddedAssets.data[path]
        val decoded = if (encoded == null) null else decode(encoded)
        cache[path] = decoded
        return decoded
    }

    private val table = IntArray(128) { -1 }.also {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        for (i in alphabet.indices) it[alphabet[i].code] = i
    }

    private fun decode(text: String): ByteArray {
        val out = ByteArray(text.length * 3 / 4)
        var at = 0
        var buffer = 0
        var bits = 0

        for (ch in text) {
            if (ch == '=') break
            val value = if (ch.code < 128) table[ch.code] else -1
            if (value < 0) continue

            buffer = (buffer shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out[at++] = ((buffer shr bits) and 0xFF).toByte()
            }
        }

        return if (at == out.size) out else out.copyOf(at)
    }
}
