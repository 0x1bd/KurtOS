package kernel.drivers.usb

import hal.Clock
import kernel.fs.BlockDevice

class MassStorage(private val device: USBDevice) : BlockDevice {
    override var blockSize: Int = 512
        private set

    override var blockCount: ULong = 0UL
        private set

    var vendor: String = ""
        private set

    var product: String = ""
        private set

    var status: String = "not initialized"
        private set

    val ready: Boolean get() = blockCount > 0UL

    private var tag: UInt = 0u
    private var cacheable = true

    private val maxBlocks: Int get() = device.bulkTransferBytes / blockSize

    fun initialize(): Boolean {
        if (!device.configured) {
            status = "not configured (${device.configureError})"
            return false
        }

        device.maxLun()

        if (!inquiry()) {
            status = "inquiry failed"
            return false
        }

        if (!awaitReady()) {
            status = "unit not ready"
            return false
        }

        if (!readCapacity()) {
            status = "read capacity failed"
            return false
        }

        val mib = blockCount * blockSize.toULong() / (1024UL * 1024UL)
        status = "$vendor $product, $blockCount x $blockSize bytes ($mib MiB)"

        return true
    }

    override fun read(lba: ULong, blocks: Int, into: ByteArray, offset: Int): Boolean =
        transfer(lba, blocks, into, offset, READ_10)

    override fun write(lba: ULong, blocks: Int, from: ByteArray, offset: Int): Boolean =
        transfer(lba, blocks, from, offset, WRITE_10)

    override fun flush(): Boolean {
        if (!cacheable) return true

        val command = ByteArray(10)
        command[0] = SYNCHRONIZE_CACHE.toByte()

        if (transact(command, false, null, 0, 0) == GOOD) return true

        requestSense()
        cacheable = false

        return true
    }

    private fun transfer(lba: ULong, blocks: Int, data: ByteArray, offset: Int, opcode: Int): Boolean {
        if (blocks <= 0) return false
        if (offset + blocks * blockSize > data.size) return false

        val input = opcode == READ_10

        var done = 0
        while (done < blocks) {
            var chunk = blocks - done
            if (chunk > maxBlocks) chunk = maxBlocks

            val command = ByteArray(10)
            command[0] = opcode.toByte()
            putBe32(command, 2, (lba + done.toULong()).toUInt())
            putBe16(command, 7, chunk)

            val bytes = chunk * blockSize
            val at = offset + done * blockSize

            if (!retried { transact(command, input, data, at, bytes) }) return false

            done += chunk
        }

        return true
    }

    private fun inquiry(): Boolean {
        val command = ByteArray(6)
        command[0] = INQUIRY.toByte()
        command[4] = INQUIRY_BYTES.toByte()

        val data = ByteArray(INQUIRY_BYTES)
        if (!retried { transact(command, true, data, 0, INQUIRY_BYTES) }) return false

        vendor = ascii(data, 8, 8)
        product = ascii(data, 16, 16)

        return true
    }

    private fun awaitReady(): Boolean {
        val command = ByteArray(6)

        for (attempt in 0 until READY_ATTEMPTS) {
            if (transact(command, false, null, 0, 0) == GOOD) return true

            requestSense()
            Clock.sleepMillis(READY_DELAY_MS)
        }

        return false
    }

    private fun requestSense(): Boolean {
        val command = ByteArray(6)
        command[0] = REQUEST_SENSE.toByte()
        command[4] = SENSE_BYTES.toByte()

        val data = ByteArray(SENSE_BYTES)
        return transact(command, true, data, 0, SENSE_BYTES) == GOOD
    }

    private fun readCapacity(): Boolean {
        val command = ByteArray(10)
        command[0] = READ_CAPACITY.toByte()

        val data = ByteArray(CAPACITY_BYTES)
        if (!retried { transact(command, true, data, 0, CAPACITY_BYTES) }) return false

        val lastBlock = be32(data, 0)
        val size = be32(data, 4).toInt()

        if (size <= 0 || size > MAX_BLOCK_SIZE) return false

        blockSize = size
        blockCount = lastBlock.toULong() + 1UL

        return true
    }

    private fun retried(command: () -> Int): Boolean {
        for (attempt in 0 until COMMAND_ATTEMPTS) {
            if (command() == GOOD) return true

            requestSense()
            Clock.sleepMillis(RETRY_DELAY_MS)
        }

        return false
    }

    private fun transact(command: ByteArray, input: Boolean, data: ByteArray?, offset: Int, length: Int): Int {
        val block = ByteArray(CBW_BYTES)

        tag++

        putLe32(block, 0, CBW_SIGNATURE)
        putLe32(block, 4, tag)
        putLe32(block, 8, length.toUInt())

        block[12] = if (input) 0x80.toByte() else 0
        block[13] = 0
        block[14] = command.size.toByte()

        command.copyInto(block, 15)

        if (device.bulkSend(block, 0, CBW_BYTES) != CBW_BYTES) {
            device.resetStorage()
            return FAILED
        }

        if (length > 0 && data != null) {
            val moved = if (input) {
                device.bulkReceive(data, offset, length)
            } else {
                device.bulkSend(data, offset, length)
            }

            if (moved < 0 && !input) {
                device.resetStorage()
                return FAILED
            }
        }

        return readStatus(tag)
    }

    private fun readStatus(expected: UInt): Int {
        val block = ByteArray(CSW_BYTES)

        var moved = device.bulkReceive(block, 0, CSW_BYTES)
        if (moved < 0) moved = device.bulkReceive(block, 0, CSW_BYTES)

        if (moved < CSW_BYTES) {
            device.resetStorage()
            return FAILED
        }

        if (le32(block, 0) != CSW_SIGNATURE || le32(block, 4) != expected) {
            device.resetStorage()
            return FAILED
        }

        val result = block[12].toInt() and 0xFF
        if (result == PHASE_ERROR) device.resetStorage()

        return result
    }

    private fun putLe32(target: ByteArray, offset: Int, value: UInt) {
        for (i in 0 until 4) target[offset + i] = (value shr (i * 8)).toByte()
    }

    private fun putBe32(target: ByteArray, offset: Int, value: UInt) {
        for (i in 0 until 4) target[offset + i] = (value shr ((3 - i) * 8)).toByte()
    }

    private fun putBe16(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value shr 8).toByte()
        target[offset + 1] = value.toByte()
    }

    private fun le32(data: ByteArray, offset: Int): UInt {
        var value = 0u
        for (i in 0 until 4) value = value or ((data[offset + i].toUInt() and 0xFFu) shl (i * 8))
        return value
    }

    private fun be32(data: ByteArray, offset: Int): UInt {
        var value = 0u
        for (i in 0 until 4) value = (value shl 8) or (data[offset + i].toUInt() and 0xFFu)
        return value
    }

    private fun ascii(data: ByteArray, offset: Int, length: Int): String {
        val builder = StringBuilder()

        for (i in 0 until length) {
            val c = data[offset + i].toInt() and 0xFF
            if (c in 0x20..0x7E) builder.append(c.toChar())
        }

        return builder.toString().trim()
    }

    private companion object {
        const val CBW_BYTES = 31
        const val CSW_BYTES = 13

        const val CBW_SIGNATURE = 0x43425355u
        const val CSW_SIGNATURE = 0x53425355u

        const val GOOD = 0
        const val FAILED = 1
        const val PHASE_ERROR = 2

        const val INQUIRY = 0x12
        const val REQUEST_SENSE = 0x03
        const val READ_CAPACITY = 0x25
        const val READ_10 = 0x28
        const val WRITE_10 = 0x2A
        const val SYNCHRONIZE_CACHE = 0x35

        const val INQUIRY_BYTES = 36
        const val SENSE_BYTES = 18
        const val CAPACITY_BYTES = 8

        const val MAX_BLOCK_SIZE = 4096

        const val READY_ATTEMPTS = 30
        const val READY_DELAY_MS = 50UL

        const val COMMAND_ATTEMPTS = 3
        const val RETRY_DELAY_MS = 10UL
    }
}
