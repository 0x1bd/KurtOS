package kernel.fs

class FatEntry(
    val name: String,
    val directory: Boolean,
    val size: ULong,
    val cluster: Int,
)

class Fat32(private val disk: BlockDevice) {
    var mounted = false
        private set

    var status = "not mounted"
        private set

    var label = ""
        private set

    private var sectorBytes = 0
    private var clusterSectors = 0
    private var clusterBytes = 0
    private var reservedSectors = 0
    private var fatCopies = 0
    private var fatSectors = 0
    private var rootCluster = 0
    private var dataSector = 0
    private var clusters = 0
    private var infoSector = 0

    private var fat = ByteArray(0)
    private var dirty = BooleanArray(0)
    private var hint = 2
    private var infoCleared = false

    fun mount(): Boolean {
        if (mounted) return true

        sectorBytes = disk.blockSize
        if (sectorBytes < 512) {
            status = "unsupported sector size $sectorBytes"
            return false
        }

        val boot = ByteArray(sectorBytes)
        if (!disk.read(0UL, 1, boot, 0)) {
            status = "cannot read boot sector"
            return false
        }

        if (le16(boot, 0x1FE) != 0xAA55) {
            status = "no boot signature"
            return false
        }

        val declared = le16(boot, 0x0B)
        if (declared != sectorBytes) {
            status = "sector size $declared does not match device $sectorBytes"
            return false
        }

        clusterSectors = boot[0x0D].toInt() and 0xFF
        reservedSectors = le16(boot, 0x0E)
        fatCopies = boot[0x10].toInt() and 0xFF
        fatSectors = le32(boot, 0x24).toInt()
        rootCluster = le32(boot, 0x2C).toInt()
        infoSector = le16(boot, 0x30)

        val totalSectors = le32(boot, 0x20).toInt()

        if (clusterSectors <= 0 || reservedSectors <= 0 || fatCopies <= 0) {
            status = "invalid bpb"
            return false
        }

        if (fatSectors <= 0 || rootCluster < 2 || totalSectors <= 0) {
            status = "not a fat32 volume"
            return false
        }

        clusterBytes = clusterSectors * sectorBytes
        dataSector = reservedSectors + fatCopies * fatSectors
        clusters = (totalSectors - dataSector) / clusterSectors

        if (clusters < MIN_CLUSTERS) {
            status = "$clusters clusters is not fat32"
            return false
        }

        if (!loadFat()) {
            status = "cannot read the file allocation table"
            return false
        }

        label = readLabel()
        mounted = true

        val mib = clusters.toLong() * clusterBytes / (1024L * 1024L)
        status = "fat32 \"$label\", $clusters clusters x $clusterBytes bytes ($mib MiB)"

        return true
    }

    fun list(path: String): List<FatEntry>? {
        if (!mounted) return null

        val cluster = resolve(parts(path)) ?: return null
        val data = readDirectory(cluster) ?: return null

        return entries(data)
            .filter { it.name != "." && it.name != ".." }
            .map { FatEntry(it.name, it.directory, it.size, it.cluster) }
    }

    fun read(path: String, maxBytes: UInt): ByteArray? {
        if (!mounted) return null

        val parts = parts(path)
        if (parts.isEmpty()) return null

        val parent = resolve(parts.subList(0, parts.size - 1)) ?: return null
        val data = readDirectory(parent) ?: return null

        val entry = entries(data).firstOrNull { matches(it.name, parts.last()) } ?: return null
        if (entry.directory) return null

        var length = entry.size
        if (length > maxBytes.toULong()) length = maxBytes.toULong()
        if (length > Int.MAX_VALUE.toULong()) return null

        val target = ByteArray(length.toInt())
        if (target.isEmpty()) return target

        if (!readChain(entry.cluster, target)) return null

        return target
    }

    fun exists(path: String): Boolean {
        if (!mounted) return false

        val parts = parts(path)
        if (parts.isEmpty()) return true

        val parent = resolve(parts.subList(0, parts.size - 1)) ?: return false
        val data = readDirectory(parent) ?: return false

        return entries(data).any { matches(it.name, parts.last()) }
    }

    fun mkdir(path: String): Boolean {
        if (!mounted) return false
        if (ensure(parts(path)) == null) return false

        return disk.flush()
    }

    fun write(path: String, content: ByteArray): Boolean {
        if (!mounted) return false

        val parts = parts(path)
        if (parts.isEmpty()) return false

        val name = parts.last()
        val parent = ensure(parts.subList(0, parts.size - 1)) ?: return false

        var data = readDirectory(parent) ?: return false
        val existing = entries(data).firstOrNull { matches(it.name, name) }
        if (existing != null && existing.directory) return false

        val needed = (content.size + clusterBytes - 1) / clusterBytes
        val chain = if (existing != null && existing.cluster >= 2) walk(existing.cluster) else mutableListOf()

        while (chain.size < needed) {
            val cluster = allocate() ?: return false
            chain.add(cluster)
        }

        val surplus = mutableListOf<Int>()
        while (chain.size > needed) surplus.add(chain.removeAt(chain.size - 1))

        link(chain)
        for (cluster in surplus) set(cluster, FREE)

        if (!flush()) return false
        if (!writeChain(chain, content)) return false

        val first = if (chain.isEmpty()) 0 else chain[0]

        if (existing != null && existing.cluster == first && existing.size == content.size.toULong()) {
            return disk.flush()
        }

        data = if (existing != null) {
            remove(data, existing)
        } else {
            data
        }

        val updated = insert(data, name, first, content.size.toULong(), false) ?: return false
        if (!writeDirectory(parent, updated)) return false

        return disk.flush()
    }

    private fun readChain(start: Int, target: ByteArray): Boolean {
        val chain = walk(start)
        if (chain.isEmpty()) return target.isEmpty()

        var written = 0
        var index = 0

        while (index < chain.size && written < target.size) {
            var run = 1
            while (index + run < chain.size &&
                chain[index + run] == chain[index] + run &&
                run < MAX_RUN
            ) {
                run++
            }

            val bytes = run * clusterBytes
            val remaining = target.size - written
            val lba = sectorOf(chain[index])

            if (bytes <= remaining) {
                if (!disk.read(lba, run * clusterSectors, target, written)) return false
                written += bytes
            } else {
                val scratch = ByteArray(bytes)
                if (!disk.read(lba, run * clusterSectors, scratch, 0)) return false
                scratch.copyInto(target, written, 0, remaining)
                written += remaining
            }

            index += run
        }

        return written >= target.size
    }

    private fun writeChain(chain: List<Int>, content: ByteArray): Boolean {
        var written = 0
        var index = 0

        while (index < chain.size) {
            var run = 1
            while (index + run < chain.size &&
                chain[index + run] == chain[index] + run &&
                run < MAX_RUN
            ) {
                run++
            }

            val bytes = run * clusterBytes
            val remaining = content.size - written
            val lba = sectorOf(chain[index])

            if (bytes <= remaining) {
                if (!disk.write(lba, run * clusterSectors, content, written)) return false
                written += bytes
            } else {
                val scratch = ByteArray(bytes)
                content.copyInto(scratch, 0, written, content.size)
                if (!disk.write(lba, run * clusterSectors, scratch, 0)) return false
                written = content.size
            }

            index += run
        }

        return true
    }

    private fun readDirectory(cluster: Int): ByteArray? {
        val chain = walk(cluster)
        if (chain.isEmpty()) return null

        val data = ByteArray(chain.size * clusterBytes)
        if (!readChain(cluster, data)) return null

        return data
    }

    private fun writeDirectory(cluster: Int, data: ByteArray): Boolean {
        val needed = data.size / clusterBytes
        val chain = walk(cluster)
        if (chain.isEmpty()) return false

        while (chain.size < needed) {
            val extra = allocate() ?: return false
            chain.add(extra)
        }

        link(chain)
        if (!flush()) return false

        return writeChain(chain, data)
    }

    private fun ensure(parts: List<String>): Int? {
        var cluster = rootCluster

        for (part in parts) {
            val data = readDirectory(cluster) ?: return null
            val entry = entries(data).firstOrNull { matches(it.name, part) }

            if (entry != null) {
                if (!entry.directory) return null
                cluster = entry.cluster
                continue
            }

            cluster = create(cluster, data, part) ?: return null
        }

        return cluster
    }

    private fun create(parent: Int, data: ByteArray, name: String): Int? {
        val cluster = allocate() ?: return null
        if (!flush()) return null

        val body = ByteArray(clusterBytes)
        shortEntry(body, 0, dot(1), DIRECTORY, cluster, 0UL)
        shortEntry(body, 1, dot(2), DIRECTORY, if (parent == rootCluster) 0 else parent, 0UL)

        if (!disk.write(sectorOf(cluster), clusterSectors, body, 0)) return null

        val updated = insert(data, name, cluster, 0UL, true) ?: return null
        if (!writeDirectory(parent, updated)) return null

        return cluster
    }

    private fun resolve(parts: List<String>): Int? {
        var cluster = rootCluster

        for (part in parts) {
            val data = readDirectory(cluster) ?: return null
            val entry = entries(data).firstOrNull { matches(it.name, part) } ?: return null
            if (!entry.directory) return null
            cluster = entry.cluster
        }

        return cluster
    }

    private class RawEntry(
        val name: String,
        val directory: Boolean,
        val size: ULong,
        val cluster: Int,
        val start: Int,
        val span: Int,
    )

    private fun entries(data: ByteArray): List<RawEntry> {
        val result = mutableListOf<RawEntry>()
        val long = arrayOfNulls<String>(MAX_LONG_PARTS)

        var longest = 0
        var first = -1
        var index = 0

        while ((index + 1) * ENTRY_BYTES <= data.size) {
            val base = index * ENTRY_BYTES
            val marker = data[base].toInt() and 0xFF

            if (marker == END) break

            if (marker == DELETED) {
                longest = 0
                first = -1
                index++
                continue
            }

            val attributes = data[base + 11].toInt() and 0xFF

            if (attributes and LONG_MASK == LONG_NAME) {
                val sequence = marker and 0x1F
                if (sequence in 1..MAX_LONG_PARTS) {
                    if (first < 0) first = index
                    long[sequence - 1] = longChars(data, base)
                    if (sequence > longest) longest = sequence
                } else {
                    longest = 0
                    first = -1
                }
                index++
                continue
            }

            if (attributes and VOLUME != 0) {
                longest = 0
                first = -1
                index++
                continue
            }

            val name = assemble(long, longest) ?: shortName(data, base)
            val start = if (first >= 0) first else index

            result.add(
                RawEntry(
                    name = name,
                    directory = attributes and DIRECTORY != 0,
                    size = le32(data, base + 28).toULong(),
                    cluster = (le16(data, base + 20) shl 16) or le16(data, base + 26),
                    start = start,
                    span = index - start + 1,
                )
            )

            longest = 0
            first = -1
            index++
        }

        return result
    }

    private fun assemble(parts: Array<String?>, longest: Int): String? {
        if (longest == 0) return null

        val builder = StringBuilder()
        for (i in 0 until longest) {
            val part = parts[i] ?: return null
            builder.append(part)
        }

        val name = builder.toString()
        return name.ifEmpty { null }
    }

    private fun longChars(data: ByteArray, base: Int): String {
        val builder = StringBuilder()

        for (offset in LONG_OFFSETS) {
            val code = le16(data, base + offset)
            if (code == 0 || code == 0xFFFF) break
            if (code in 0x20..0x7E) builder.append(code.toChar())
        }

        return builder.toString()
    }

    private fun shortName(data: ByteArray, base: Int): String {
        val flags = data[base + 12].toInt() and 0xFF

        val stem = trimmed(data, base, 8, flags and LOWER_BASE != 0)
        val extension = trimmed(data, base + 8, 3, flags and LOWER_EXTENSION != 0)

        if (extension.isEmpty()) return stem
        return "$stem.$extension"
    }

    private fun trimmed(data: ByteArray, base: Int, length: Int, lower: Boolean): String {
        val builder = StringBuilder()

        for (i in 0 until length) {
            val c = data[base + i].toInt() and 0xFF
            if (c == 0x20) continue
            builder.append(if (lower) c.toChar().lowercaseChar() else c.toChar())
        }

        return builder.toString()
    }

    private fun remove(data: ByteArray, entry: RawEntry): ByteArray {
        for (i in 0 until entry.span) {
            data[(entry.start + i) * ENTRY_BYTES] = DELETED.toByte()
        }
        return data
    }

    private fun insert(data: ByteArray, name: String, cluster: Int, size: ULong, directory: Boolean): ByteArray? {
        val short = uniqueShort(data, name)
        val long = longNeeded(name, short)
        val span = long + 1

        var body = data
        var slot = free(body, span)

        var grown = 0
        while (slot < 0) {
            if (grown >= MAX_DIRECTORY_GROWTH) return null

            val extended = ByteArray(body.size + clusterBytes)
            body.copyInto(extended)
            body = extended
            grown++

            slot = free(body, span)
        }

        if (long > 0) {
            val checksum = checksum(short)
            for (i in 0 until long) {
                val sequence = long - i
                val last = i == 0
                longEntry(body, slot + i, name, sequence, last, checksum)
            }
        }

        val attributes = if (directory) DIRECTORY else ARCHIVE
        shortEntry(body, slot + long, short, attributes, cluster, size)

        return body
    }

    private fun free(data: ByteArray, span: Int): Int {
        val slots = data.size / ENTRY_BYTES

        var run = 0
        var index = 0

        while (index < slots) {
            val marker = data[index * ENTRY_BYTES].toInt() and 0xFF

            if (marker == END || marker == DELETED) {
                run++
                if (run == span && index + 1 < slots) return index - span + 1
            } else {
                run = 0
            }

            index++
        }

        return -1
    }

    private fun longNeeded(name: String, short: ByteArray): Int {
        if (matchesShort(name, short)) return 0
        return (name.length + LONG_CHARS - 1) / LONG_CHARS
    }

    private fun matchesShort(name: String, short: ByteArray): Boolean {
        val builder = StringBuilder()

        for (i in 0 until 8) {
            val c = short[i].toInt() and 0xFF
            if (c != 0x20) builder.append(c.toChar())
        }

        var extension = ""
        for (i in 8 until 11) {
            val c = short[i].toInt() and 0xFF
            if (c != 0x20) extension += c.toChar()
        }

        if (extension.isNotEmpty()) builder.append('.').append(extension)

        return builder.toString() == name.uppercase()
    }

    private fun uniqueShort(data: ByteArray, name: String): ByteArray {
        val dot = name.lastIndexOf('.')

        val stem = if (dot > 0) name.substring(0, dot) else name
        val extension = if (dot > 0) name.substring(dot + 1) else ""

        val base = sanitize(stem, 8)
        val tail = sanitize(extension, 3)

        val taken = entries(data).map { it.name.uppercase() }.toSet()

        val plain = ByteArray(11)
        fill(plain, base, tail)

        if (base.length <= 8 && stem.uppercase() == base && extension.uppercase() == tail) {
            return plain
        }

        var suffix = 1
        while (suffix < MAX_SHORT_SUFFIX) {
            val marker = "~$suffix"
            val head = base.take(8 - marker.length) + marker

            val candidate = ByteArray(11)
            fill(candidate, head, tail)

            val label = if (tail.isEmpty()) head else "$head.$tail"
            if (!taken.contains(label)) return candidate

            suffix++
        }

        return plain
    }

    private fun fill(target: ByteArray, stem: String, extension: String) {
        for (i in 0 until 11) target[i] = 0x20

        for (i in stem.indices) target[i] = stem[i].code.toByte()
        for (i in extension.indices) target[8 + i] = extension[i].code.toByte()
    }

    private fun sanitize(text: String, limit: Int): String {
        val builder = StringBuilder()

        for (c in text) {
            if (builder.length == limit) break

            val upper = c.uppercaseChar()
            builder.append(if (upper.code in 0x20..0x7E && !ILLEGAL.contains(upper)) upper else '_')
        }

        return builder.toString()
    }

    private fun checksum(short: ByteArray): Int {
        var sum = 0

        for (i in 0 until 11) {
            val carry = (sum and 1) shl 7
            sum = (carry + (sum shr 1) + (short[i].toInt() and 0xFF)) and 0xFF
        }

        return sum
    }

    private fun longEntry(data: ByteArray, slot: Int, name: String, sequence: Int, last: Boolean, checksum: Int) {
        val base = slot * ENTRY_BYTES

        for (i in 0 until ENTRY_BYTES) data[base + i] = 0

        data[base] = (if (last) sequence or LAST_LONG else sequence).toByte()
        data[base + 11] = LONG_NAME.toByte()
        data[base + 13] = checksum.toByte()

        val start = (sequence - 1) * LONG_CHARS

        for (i in 0 until LONG_CHARS) {
            val at = base + LONG_OFFSETS[i]
            val index = start + i

            val code = when {
                index < name.length -> name[index].code
                index == name.length -> 0
                else -> 0xFFFF
            }

            data[at] = code.toByte()
            data[at + 1] = (code shr 8).toByte()
        }
    }

    private fun shortEntry(data: ByteArray, slot: Int, short: ByteArray, attributes: Int, cluster: Int, size: ULong) {
        val base = slot * ENTRY_BYTES

        for (i in 0 until ENTRY_BYTES) data[base + i] = 0

        short.copyInto(data, base, 0, 11)

        data[base + 11] = attributes.toByte()

        putLe16(data, base + 20, (cluster shr 16) and 0xFFFF)
        putLe16(data, base + 26, cluster and 0xFFFF)
        putLe32(data, base + 28, size.toUInt())
    }

    private fun dot(count: Int): ByteArray {
        val name = ByteArray(11)
        for (i in 0 until 11) name[i] = 0x20
        for (i in 0 until count) name[i] = '.'.code.toByte()
        return name
    }

    private fun walk(start: Int): MutableList<Int> {
        val chain = mutableListOf<Int>()

        var cluster = start
        while (cluster in 2..(clusters + 1) && chain.size <= clusters) {
            chain.add(cluster)
            cluster = get(cluster)
        }

        return chain
    }

    private fun link(chain: List<Int>) {
        for (i in chain.indices) {
            val next = if (i == chain.size - 1) EOC else chain[i + 1]
            set(chain[i], next)
        }
    }

    private fun allocate(): Int? {
        val limit = clusters + 2

        var scanned = 0
        var cluster = hint

        while (scanned < clusters) {
            if (cluster < 2 || cluster >= limit) cluster = 2

            if (get(cluster) == FREE) {
                set(cluster, EOC)
                hint = cluster + 1
                return cluster
            }

            cluster++
            scanned++
        }

        return null
    }

    private fun get(cluster: Int): Int {
        val offset = cluster * 4
        if (offset + 4 > fat.size) return EOC

        return (le32(fat, offset).toInt() and MASK)
    }

    private fun set(cluster: Int, value: Int) {
        val offset = cluster * 4
        if (offset + 4 > fat.size) return

        val current = le32(fat, offset).toInt()
        val updated = (current and MASK.inv()) or (value and MASK)

        putLe32(fat, offset, updated.toUInt())
        dirty[offset / sectorBytes] = true
    }

    private fun flush(): Boolean {
        var written = false

        for (index in dirty.indices) {
            if (!dirty[index]) continue

            for (copy in 0 until fatCopies) {
                val lba = (reservedSectors + copy * fatSectors + index).toULong()
                if (!disk.write(lba, 1, fat, index * sectorBytes)) return false
            }

            dirty[index] = false
            written = true
        }

        if (written) clearInfo()

        return true
    }

    private fun clearInfo() {
        if (infoCleared) return
        infoCleared = true

        if (infoSector <= 0 || infoSector >= reservedSectors) return

        val info = ByteArray(sectorBytes)
        if (!disk.read(infoSector.toULong(), 1, info, 0)) return

        if (le32(info, 0) != INFO_LEAD || le32(info, 484) != INFO_BODY) return

        putLe32(info, 488, UNKNOWN)
        putLe32(info, 492, UNKNOWN)

        disk.write(infoSector.toULong(), 1, info, 0)
    }

    private fun loadFat(): Boolean {
        fat = ByteArray(fatSectors * sectorBytes)
        dirty = BooleanArray(fatSectors)

        var sector = 0
        while (sector < fatSectors) {
            var run = fatSectors - sector
            if (run > FAT_RUN) run = FAT_RUN

            val lba = (reservedSectors + sector).toULong()
            if (!disk.read(lba, run, fat, sector * sectorBytes)) return false

            sector += run
        }

        return true
    }

    private fun readLabel(): String {
        val data = readDirectory(rootCluster) ?: return ""

        var index = 0
        while ((index + 1) * ENTRY_BYTES <= data.size) {
            val base = index * ENTRY_BYTES
            val marker = data[base].toInt() and 0xFF

            if (marker == END) break

            val attributes = data[base + 11].toInt() and 0xFF
            if (marker != DELETED && attributes and LONG_MASK != LONG_NAME && attributes and VOLUME != 0) {
                return trimmed(data, base, 11, false)
            }

            index++
        }

        return ""
    }

    private fun sectorOf(cluster: Int): ULong =
        (dataSector + (cluster - 2) * clusterSectors).toULong()

    private fun matches(a: String, b: String): Boolean = a.equals(b, ignoreCase = true)

    private fun parts(path: String): List<String> =
        path.split("/").filter { it.isNotEmpty() && it != "." }

    private fun le16(data: ByteArray, offset: Int): Int {
        if (offset + 2 > data.size) return 0
        return (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun le32(data: ByteArray, offset: Int): UInt {
        if (offset + 4 > data.size) return 0u

        var value = 0u
        for (i in 0 until 4) value = value or ((data[offset + i].toUInt() and 0xFFu) shl (i * 8))
        return value
    }

    private fun putLe16(data: ByteArray, offset: Int, value: Int) {
        data[offset] = value.toByte()
        data[offset + 1] = (value shr 8).toByte()
    }

    private fun putLe32(data: ByteArray, offset: Int, value: UInt) {
        for (i in 0 until 4) data[offset + i] = (value shr (i * 8)).toByte()
    }

    private companion object {
        const val ENTRY_BYTES = 32

        const val END = 0x00
        const val DELETED = 0xE5

        const val VOLUME = 0x08
        const val DIRECTORY = 0x10
        const val ARCHIVE = 0x20
        const val LONG_NAME = 0x0F
        const val LONG_MASK = 0x3F
        const val LAST_LONG = 0x40

        const val LOWER_BASE = 0x08
        const val LOWER_EXTENSION = 0x10

        const val FREE = 0
        const val EOC = 0x0FFFFFF8
        const val MASK = 0x0FFFFFFF

        const val INFO_LEAD = 0x41615252u
        const val INFO_BODY = 0x61417272u
        const val UNKNOWN = 0xFFFFFFFFu

        const val MIN_CLUSTERS = 65525
        const val LONG_CHARS = 13
        const val MAX_LONG_PARTS = 20
        const val MAX_SHORT_SUFFIX = 1000
        const val MAX_DIRECTORY_GROWTH = 8
        const val MAX_RUN = 64
        const val FAT_RUN = 64

        const val ILLEGAL = "\"*+,./:;<=>?[\\]|"

        val LONG_OFFSETS = intArrayOf(1, 3, 5, 7, 9, 14, 16, 18, 20, 22, 24, 28, 30)
    }
}
