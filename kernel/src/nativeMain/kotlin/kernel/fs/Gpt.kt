package kernel.fs

class GptEntry(
    val type: String,
    val name: String,
    val firstLba: ULong,
    val lastLba: ULong,
) {
    val blocks: ULong get() = lastLba - firstLba + 1UL
}

object Gpt {
    const val BASIC_DATA = "EBD0A0A2-B9E5-4433-87C0-68B21B25C673"

    fun partitions(disk: BlockDevice): List<GptEntry> {
        if (disk.blockSize < HEADER_BYTES) return emptyList()

        val header = ByteArray(disk.blockSize)
        if (!disk.read(1UL, 1, header, 0)) return emptyList()
        if (ascii(header, 0, 8) != SIGNATURE) return emptyList()

        val tableLba = le64(header, 72)
        val count = le32(header, 80).toInt()
        val entryBytes = le32(header, 84).toInt()

        if (count <= 0 || count > MAX_ENTRIES) return emptyList()
        if (entryBytes < ENTRY_BYTES || entryBytes > disk.blockSize) return emptyList()

        val perBlock = disk.blockSize / entryBytes
        val blocks = (count + perBlock - 1) / perBlock

        val table = ByteArray(blocks * disk.blockSize)
        if (!disk.read(tableLba, blocks, table, 0)) return emptyList()

        val result = mutableListOf<GptEntry>()

        for (index in 0 until count) {
            val base = index * entryBytes
            if (base + ENTRY_BYTES > table.size) break

            val type = guid(table, base)
            if (type == EMPTY) continue

            val first = le64(table, base + 32)
            val last = le64(table, base + 40)
            if (last < first) continue

            result.add(GptEntry(type, label(table, base + 56), first, last))
        }

        return result
    }

    private fun label(data: ByteArray, offset: Int): String {
        val builder = StringBuilder()

        for (i in 0 until NAME_CHARS) {
            val at = offset + i * 2
            if (at + 1 >= data.size) break

            val code = (data[at].toInt() and 0xFF) or ((data[at + 1].toInt() and 0xFF) shl 8)
            if (code == 0) break
            if (code in 0x20..0x7E) builder.append(code.toChar())
        }

        return builder.toString().trim()
    }

    private fun guid(data: ByteArray, offset: Int): String {
        val builder = StringBuilder()

        for (i in MIXED_ENDIAN) {
            if (i < 0) {
                builder.append('-')
                continue
            }
            builder.append(hex2(data[offset + i].toInt() and 0xFF))
        }

        return builder.toString()
    }

    private fun hex2(value: Int): String = value.toString(16).uppercase().padStart(2, '0')

    private fun ascii(data: ByteArray, offset: Int, length: Int): String {
        val builder = StringBuilder()
        for (i in 0 until length) builder.append((data[offset + i].toInt() and 0xFF).toChar())
        return builder.toString()
    }

    private fun le32(data: ByteArray, offset: Int): UInt {
        var value = 0u
        for (i in 0 until 4) value = value or ((data[offset + i].toUInt() and 0xFFu) shl (i * 8))
        return value
    }

    private fun le64(data: ByteArray, offset: Int): ULong {
        var value = 0UL
        for (i in 0 until 8) value = value or ((data[offset + i].toULong() and 0xFFUL) shl (i * 8))
        return value
    }

    private const val SIGNATURE = "EFI PART"
    private const val EMPTY = "00000000-0000-0000-0000-000000000000"

    private const val HEADER_BYTES = 92
    private const val ENTRY_BYTES = 128
    private const val NAME_CHARS = 36
    private const val MAX_ENTRIES = 256

    private val MIXED_ENDIAN = intArrayOf(
        3, 2, 1, 0, -1,
        5, 4, -1,
        7, 6, -1,
        8, 9, -1,
        10, 11, 12, 13, 14, 15,
    )
}
