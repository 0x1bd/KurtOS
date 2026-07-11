package kernel.fs

enum class FlxEntryKind {
    File,
    Directory,
}

object FlxService {
    private var initialized = false
    private var mounted = false
    private var rootHash: ByteArray = ByteArray(32)
    private var objects: List<FlxObjectRecord> = emptyList()
    private var lastStatus = "not initialized"

    private var source: ByteSource = ModuleByteSource

    fun initialize(): Boolean {
        if (initialized) return mounted
        initialized = true

        if (!ModuleByteSource.available) {
            lastStatus = "no filesystem image supplied by the bootloader"
            return false
        }

        val superblock = source.read(0UL, FLXConstants.FLX_BLOCK_SIZE)
        if (superblock == null) {
            lastStatus = "unable to read superblock"
            return false
        }

        val magic = ascii(superblock, 0, 4)
        val version = le32(superblock, 4)
        val blockSize = le32(superblock, 8)
        val objectCount = le32(superblock, 12)
        val tableOffset = le64(superblock, 16)
        val tableSize = le64(superblock, 24)
        if (magic != FLXConstants.FLX_MAGIC || version != FLXConstants.FLX_VERSION || blockSize != FLXConstants.FLX_BLOCK_SIZE) {
            lastStatus = "invalid FLX superblock"
            return false
        }
        if (objectCount == 0u || tableSize != objectCount.toULong() * FLXConstants.FLX_OBJECT_RECORD_SIZE.toULong()) {
            lastStatus = "invalid FLX object table"
            return false
        }

        rootHash = slice(superblock, 32, 32)
        val table = source.read(tableOffset, tableSize.toUInt())
        if (table == null) {
            lastStatus = "unable to read object table"
            return false
        }

        val parsed = mutableListOf<FlxObjectRecord>()
        var index = 0u
        while (index < objectCount) {
            val base = (index * FLXConstants.FLX_OBJECT_RECORD_SIZE).toInt()
            parsed.add(
                FlxObjectRecord(
                    hash = slice(table, base, 32),
                    type = le32(table, base + 32),
                    offset = le64(table, base + 40),
                    size = le64(table, base + 48),
                )
            )
            index++
        }
        objects = parsed

        if (findObject(rootHash, FLXConstants.FLX_OBJECT_TREE) == null) {
            lastStatus = "root tree missing"
            return false
        }

        mounted = true
        lastStatus = "mounted ${objects.size} objects"
        return true
    }

    fun status(): String = lastStatus

    fun list(path: String): List<FlxDirectoryEntry>? {
        val tree = resolveTree(path) ?: return null
        return readTree(tree).map {
            FlxDirectoryEntry(
                it.name,
                if (it.kind == FLXConstants.FLX_OBJECT_TREE) FlxEntryKind.Directory else FlxEntryKind.File,
                it.size
            )
        }
    }

    fun open(path: String): FlxFile? {
        val normalized = normalizePath(path)
        val parts = pathParts(normalized)
        if (parts.isEmpty()) return null

        var currentHash = rootHash
        for (i in parts.indices) {
            val tree = findObject(currentHash, FLXConstants.FLX_OBJECT_TREE) ?: return null
            val entry = readTree(tree).firstOrNull { it.name == parts[i] } ?: return null
            if (i == parts.lastIndex) {
                val blob = findObject(entry.hash, FLXConstants.FLX_OBJECT_BLOB) ?: return null
                return FlxFile(blob, source)
            }
            if (entry.kind != FLXConstants.FLX_OBJECT_TREE) return null
            currentHash = entry.hash
        }
        return null
    }

    fun fileMagic(path: String): String? {
        val file = open(path) ?: return null
        val bytes = file.read(0UL, 4u) ?: return null
        if (bytes.size < 4) return null
        return ascii(bytes, 0, 4)
    }

    private fun resolveTree(path: String): FlxObjectRecord? {
        val parts = pathParts(normalizePath(path))
        var currentHash = rootHash
        if (parts.isEmpty()) return findObject(currentHash, FLXConstants.FLX_OBJECT_TREE)

        for (part in parts) {
            val tree = findObject(currentHash, FLXConstants.FLX_OBJECT_TREE) ?: return null
            val entry = readTree(tree).firstOrNull { it.name == part } ?: return null
            if (entry.kind != FLXConstants.FLX_OBJECT_TREE) return null
            currentHash = entry.hash
        }
        return findObject(currentHash, FLXConstants.FLX_OBJECT_TREE)
    }

    private fun readTree(record: FlxObjectRecord): List<FlxTreeEntry> {
        val payload = source.read(record.offset, record.size.toUInt()) ?: return emptyList()
        val count = le32(payload, 0)
        val entries = mutableListOf<FlxTreeEntry>()
        var cursor = 4
        var index = 0u
        while (index < count && cursor + 48 <= payload.size) {
            val kind = le32(payload, cursor)
            val nameLength = le32(payload, cursor + 4).toInt()
            val size = le64(payload, cursor + 8)
            val hash = slice(payload, cursor + 16, 32)
            cursor += 48
            if (nameLength < 0 || cursor + nameLength > payload.size) break
            val name = ascii(payload, cursor, nameLength)
            cursor += nameLength
            entries.add(FlxTreeEntry(name, kind, size, hash))
            index++
        }
        return entries
    }

    private fun findObject(hash: ByteArray, type: UInt): FlxObjectRecord? =
        objects.firstOrNull { it.type == type && bytesEqual(it.hash, hash) }
}

internal data class FlxObjectRecord(
    val hash: ByteArray,
    val type: UInt,
    val offset: ULong,
    val size: ULong,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as FlxObjectRecord

        if (!hash.contentEquals(other.hash)) return false
        if (type != other.type) return false
        if (offset != other.offset) return false
        if (size != other.size) return false

        return true
    }

    override fun hashCode(): Int {
        var result = hash.contentHashCode()
        result = 31 * result + type.hashCode()
        result = 31 * result + offset.hashCode()
        result = 31 * result + size.hashCode()
        return result
    }
}

private data class FlxTreeEntry(
    val name: String,
    val kind: UInt,
    val size: ULong,
    val hash: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as FlxTreeEntry

        if (name != other.name) return false
        if (kind != other.kind) return false
        if (size != other.size) return false
        if (!hash.contentEquals(other.hash)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + kind.hashCode()
        result = 31 * result + size.hashCode()
        result = 31 * result + hash.contentHashCode()
        return result
    }
}

private fun normalizePath(path: String): String =
    path.trim().ifBlank { "/" }.let { if (it.startsWith("/")) it else "/$it" }

private fun pathParts(path: String): List<String> =
    path.split("/").filter { it.isNotEmpty() && it != "." }

private fun le32(bytes: ByteArray, offset: Int): UInt {
    val b0 = bytes[offset].toInt() and 0xff
    val b1 = bytes[offset + 1].toInt() and 0xff
    val b2 = bytes[offset + 2].toInt() and 0xff
    val b3 = bytes[offset + 3].toInt() and 0xff
    return (b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)).toUInt()
}

private fun le64(bytes: ByteArray, offset: Int): ULong {
    var value = 0UL
    var i = 0
    while (i < 8) {
        value = value or ((bytes[offset + i].toInt() and 0xff).toULong() shl (i * 8))
        i++
    }
    return value
}

private fun slice(bytes: ByteArray, offset: Int, length: Int): ByteArray {
    val result = ByteArray(length)
    var i = 0
    while (i < length) {
        result[i] = bytes[offset + i]
        i++
    }
    return result
}

private fun ascii(bytes: ByteArray, offset: Int, length: Int): String {
    val builder = StringBuilder()
    var i = 0
    while (i < length) {
        builder.append((bytes[offset + i].toInt() and 0xff).toChar())
        i++
    }
    return builder.toString()
}

private fun bytesEqual(a: ByteArray, b: ByteArray): Boolean {
    if (a.size != b.size) return false
    for (i in a.indices) {
        if (a[i] != b[i]) return false
    }
    return true
}
