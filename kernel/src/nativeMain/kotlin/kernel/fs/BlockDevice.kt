package kernel.fs

interface BlockDevice {
    val blockSize: Int
    val blockCount: ULong

    fun read(lba: ULong, blocks: Int, into: ByteArray, offset: Int): Boolean
    fun write(lba: ULong, blocks: Int, from: ByteArray, offset: Int): Boolean
    fun flush(): Boolean
}

class Partition(
    private val disk: BlockDevice,
    private val start: ULong,
    private val length: ULong,
) : BlockDevice {
    override val blockSize: Int get() = disk.blockSize

    override val blockCount: ULong get() = length

    override fun read(lba: ULong, blocks: Int, into: ByteArray, offset: Int): Boolean {
        if (!within(lba, blocks)) return false
        return disk.read(start + lba, blocks, into, offset)
    }

    override fun write(lba: ULong, blocks: Int, from: ByteArray, offset: Int): Boolean {
        if (!within(lba, blocks)) return false
        return disk.write(start + lba, blocks, from, offset)
    }

    override fun flush(): Boolean = disk.flush()

    private fun within(lba: ULong, blocks: Int): Boolean {
        if (blocks <= 0) return false
        return lba + blocks.toULong() <= length
    }
}
