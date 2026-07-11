package kernel.fs

data class FlxDirectoryEntry(
    val name: String,
    val kind: FlxEntryKind,
    val size: ULong,
)

class FlxFile internal constructor(
    private val record: FlxObjectRecord,
    private val source: ByteSource,
) {
    val size: ULong get() = record.size

    fun read(offset: ULong, length: UInt): ByteArray? {
        if (offset >= record.size) return ByteArray(0)
        val available = record.size - offset
        val actual = minOf(length.toULong(), available).toUInt()
        return source.read(record.offset + offset, actual)
    }

    fun readAll(maxBytes: UInt): ByteArray? {
        val length = minOf(size, maxBytes.toULong()).toUInt()
        return read(0UL, length)
    }
}
