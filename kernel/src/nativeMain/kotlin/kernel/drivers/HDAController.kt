package kernel.drivers

import hal.BootInfo
import hal.Clock
import hal.RawMemory
import kernel.memory.Mmio
import kernel.memory.PageAllocator
import kernel.memory.Region

class HDAController(private val device: PciDevice) {
    private var base: ULong = 0UL
    private var stream: ULong = 0UL

    private var buffer: Region? = null
    private var descriptors: Region? = null

    private var codec: HdaCodec? = null
    private var path: HdaPath? = null

    private var running = false

    var status: String = "not initialized"
        private set

    fun initialize(): Boolean {
        val bar = device.bar(0)
        if (bar == 0UL) {
            status = "no mmio bar"
            return false
        }

        val mapped = Mmio.map(bar, REGISTER_BYTES)
        if (mapped == 0UL) {
            status = "cannot map bar 0x${bar.toString(16)}"
            return false
        }

        base = mapped
        device.enableBusMaster()

        if (!reset()) {
            status = "controller reset timed out"
            return false
        }

        val capabilities = read16(GCAP).toInt()
        if (capabilities == 0xFFFF) {
            status = "bar 0x${bar.toString(16)} reads back all ones"
            return false
        }

        val inputStreams = (capabilities shr 8) and 0x0F
        stream = base + (STREAM_BASE + inputStreams * STREAM_SIZE).toULong()

        val present = read16(STATESTS).toInt()
        if (present == 0) {
            status = "no codec responded"
            return false
        }

        if (!allocate()) {
            status = "no memory for dma buffers"
            return false
        }

        for (index in 0 until 15) {
            if (present and (1 shl index) == 0) continue

            val candidate = HdaCodec(this, index)
            val output = candidate.findOutputPath() ?: continue

            codec = candidate
            path = output
            break
        }

        val output = path
        if (output == null) {
            status = "codec found but no output path"
            return false
        }

        configureStream()
        candidateActivate(output)

        status = "hd audio ${SAMPLE_RATE} Hz stereo (codec ${output.codec}, dac ${output.dac}, pin ${output.pin})"
        return true
    }

    private fun candidateActivate(output: HdaPath) {
        codec?.activate(output, STREAM_NUMBER, FORMAT_48K_16BIT_STEREO)
    }

    private fun reset(): Boolean {
        write32(GCTL, read32(GCTL) and 0x1u.inv())
        if (!waitFor { read32(GCTL) and 0x1u == 0u }) return false

        Clock.sleepMillis(1UL)

        write32(GCTL, read32(GCTL) or 0x1u)
        if (!waitFor { read32(GCTL) and 0x1u != 0u }) return false

        Clock.sleepMillis(2UL)

        write32(INTCTL, 0u)
        return true
    }

    private fun allocate(): Boolean {
        val audio = PageAllocator.allocateBytes(BUFFER_BYTES.toUInt()) ?: return false
        val list = PageAllocator.allocateBytes(4096u) ?: return false

        audio.zero()
        list.zero()

        buffer = audio
        descriptors = list

        val physical = BootInfo.toPhysical(audio.address)
        val chunk = BUFFER_BYTES / DESCRIPTOR_COUNT

        for (i in 0 until DESCRIPTOR_COUNT) {
            val entry = list.address + (i * 16).toULong()
            RawMemory.write64(entry, physical + (i * chunk).toULong())
            RawMemory.write32(entry + 8UL, chunk.toUInt())
            RawMemory.write32(entry + 12UL, 0u)
        }

        return true
    }

    private fun configureStream() {
        writeControl(readControl() or SRST)
        waitFor { readControl() and SRST != 0u }

        writeControl(readControl() and SRST.inv())
        waitFor { readControl() and SRST == 0u }

        val list = descriptors ?: return
        val listPhysical = BootInfo.toPhysical(list.address)

        write32(stream + SDBDPL, (listPhysical and 0xFFFFFFFFu).toUInt())
        write32(stream + SDBDPU, (listPhysical shr 32).toUInt())

        write32(stream + SDCBL, BUFFER_BYTES.toUInt())
        write16(stream + SDLVI, (DESCRIPTOR_COUNT - 1).toUShort())
        write16(stream + SDFMT, FORMAT_48K_16BIT_STEREO.toUShort())

        writeControl((readControl() and STREAM_MASK.inv()) or (STREAM_NUMBER.toUInt() shl 20))
    }

    fun start() {
        if (running || path == null) return
        writeControl(readControl() or RUN)
        running = true
    }

    fun stop() {
        if (!running) return
        writeControl(readControl() and RUN.inv())
        running = false
    }

    val ready: Boolean get() = path != null

    fun describeCodecs(): List<String> {
        val lines = mutableListOf<String>()
        val present = read16(STATESTS).toInt()

        for (index in 0 until 15) {
            if (present and (1 shl index) == 0) continue
            lines.addAll(HdaCodec(this, index).describe())
        }

        if (lines.isEmpty()) lines.add("no codecs present")
        return lines
    }

    fun position(): Int = read32(stream + SDLPIB).toInt()

    fun bufferAddress(): ULong = buffer?.address ?: 0UL

    fun verb12(codecAddress: Int, node: Int, verb: Int, payload: Int): Int =
        immediate(codecAddress, node, (verb.toUInt() shl 8) or (payload.toUInt() and 0xFFu))

    fun verb4(codecAddress: Int, node: Int, verb: Int, payload: Int): Int =
        immediate(codecAddress, node, (verb.toUInt() shl 16) or (payload.toUInt() and 0xFFFFu))

    private fun immediate(codecAddress: Int, node: Int, data: UInt): Int {
        val value = (codecAddress.toUInt() shl 28) or (node.toUInt() shl 20) or (data and 0xFFFFFu)

        if (!waitFor { read16(ICIS).toUInt() and 0x1u == 0u }) return 0

        write16(ICIS, 0x2u)
        write32(ICOI, value)
        write16(ICIS, 0x1u)

        if (!waitFor { read16(ICIS).toUInt() and 0x2u != 0u }) return 0

        return read32(ICII).toInt()
    }

    private fun readControl(): UInt = read32(stream + SDCTL) and CONTROL_MASK

    private fun writeControl(value: UInt) = write32(stream + SDCTL, value and CONTROL_MASK)

    private fun waitFor(condition: () -> Boolean): Boolean {
        val deadline = Clock.uptimeMillis() + TIMEOUT_MS

        while (Clock.uptimeMillis() < deadline) {
            if (condition()) return true
        }

        return condition()
    }

    private fun read16(offset: Int): UShort = RawMemory.read16(base + offset.toULong())
    private fun read32(offset: Int): UInt = RawMemory.read32(base + offset.toULong())
    private fun read32(offset: ULong): UInt = RawMemory.read32(offset)

    private fun write16(offset: Int, value: UShort) = RawMemory.write16(base + offset.toULong(), value)
    private fun write16(offset: ULong, value: UShort) = RawMemory.write16(offset, value)
    private fun write32(offset: Int, value: UInt) = RawMemory.write32(base + offset.toULong(), value)
    private fun write32(offset: ULong, value: UInt) = RawMemory.write32(offset, value)

    companion object {
        const val SAMPLE_RATE = 48000
        const val CHANNELS = 2
        const val BYTES_PER_FRAME = CHANNELS * 2

        const val BUFFER_BYTES = 32768
        private const val REGISTER_BYTES = 0x4000UL
        const val DESCRIPTOR_COUNT = 4

        private const val STREAM_NUMBER = 1
        private const val FORMAT_48K_16BIT_STEREO = 0x0011

        private const val TIMEOUT_MS = 200UL

        private const val GCAP = 0x00
        private const val GCTL = 0x08
        private const val STATESTS = 0x0E
        private const val INTCTL = 0x20
        private const val ICOI = 0x60
        private const val ICII = 0x64
        private const val ICIS = 0x68

        private const val STREAM_BASE = 0x80
        private const val STREAM_SIZE = 0x20

        private const val SDCTL = 0x00UL
        private const val SDLPIB = 0x04UL
        private const val SDCBL = 0x08UL
        private const val SDLVI = 0x0CUL
        private const val SDFMT = 0x12UL
        private const val SDBDPL = 0x18UL
        private const val SDBDPU = 0x1CUL

        private const val CONTROL_MASK = 0x00FFFFFFu
        private const val STREAM_MASK = 0x00F00000u
        private const val SRST = 0x1u
        private const val RUN = 0x2u
    }
}
