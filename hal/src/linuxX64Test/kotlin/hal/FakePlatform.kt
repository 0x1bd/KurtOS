package hal

class FakePortIo : PortIo {
    val words = HashMap<Int, UInt>()
    val bytes = HashMap<Int, UByte>()
    val configSpace = HashMap<UInt, UInt>()
    var lastConfigAddress: UInt = 0u
    var waits = 0

    override fun read8(port: UShort): UByte {
        if (port.toInt() == COM1_LINE_STATUS) return 0x20u
        return bytes[port.toInt()] ?: 0u
    }

    override fun write8(port: UShort, value: UByte) {
        bytes[port.toInt()] = value
    }

    override fun read16(port: UShort): UShort = (words[port.toInt()] ?: 0u).toUShort()

    override fun write16(port: UShort, value: UShort) {
        words[port.toInt()] = value.toUInt()
    }

    override fun read32(port: UShort): UInt {
        if (port.toInt() == PCI_DATA) return configSpace[lastConfigAddress] ?: 0xFFFFFFFFu
        return words[port.toInt()] ?: 0u
    }

    override fun write32(port: UShort, value: UInt) {
        if (port.toInt() == PCI_ADDRESS) lastConfigAddress = value
        words[port.toInt()] = value
    }

    override fun wait() {
        waits++
    }

    companion object {
        const val PCI_ADDRESS = 0xCF8
        const val PCI_DATA = 0xCFC
        const val COM1_LINE_STATUS = 0x3FD
    }
}

class FakeRawMemory : RawMemoryOps {
    val store = HashMap<ULong, UByte>()
    var lastCopyDestination: ULong = 0UL
    var lastCopySource: ULong = 0UL
    var lastCopyBytes: ULong = 0UL

    override fun read8(address: ULong): UByte = store[address] ?: 0u

    override fun read16(address: ULong): UShort =
        (read8(address).toUInt() or (read8(address + 1u).toUInt() shl 8)).toUShort()

    override fun read32(address: ULong): UInt {
        var value = 0u
        for (i in 0 until 4) value = value or (read8(address + i.toULong()).toUInt() shl (i * 8))
        return value
    }

    override fun read64(address: ULong): ULong {
        var value = 0UL
        for (i in 0 until 8) value = value or (read8(address + i.toULong()).toULong() shl (i * 8))
        return value
    }

    override fun write8(address: ULong, value: UByte) {
        store[address] = value
    }

    override fun write16(address: ULong, value: UShort) {
        for (i in 0 until 2) write8(address + i.toULong(), ((value.toUInt() shr (i * 8)) and 0xFFu).toUByte())
    }

    override fun write32(address: ULong, value: UInt) {
        for (i in 0 until 4) write8(address + i.toULong(), ((value shr (i * 8)) and 0xFFu).toUByte())
    }

    override fun write64(address: ULong, value: ULong) {
        for (i in 0 until 8) write8(address + i.toULong(), ((value shr (i * 8)) and 0xFFUL).toUByte())
    }

    override fun fill32(address: ULong, value: UInt, count: ULong) {
        var i = 0UL
        while (i < count) {
            write32(address + i * 4UL, value)
            i++
        }
    }

    override fun copy(destination: ULong, source: ULong, bytes: ULong) {
        lastCopyDestination = destination
        lastCopySource = source
        lastCopyBytes = bytes
        var i = 0UL
        while (i < bytes) {
            write8(destination + i, read8(source + i))
            i++
        }
    }

    override fun copyStream(destination: ULong, source: ULong, bytes: ULong) = copy(destination, source, bytes)

    override fun zero(address: ULong, length: ULong) {
        var i = 0UL
        while (i < length) {
            write8(address + i, 0u)
            i++
        }
    }

    override fun blitIndexed(
        destination: ULong,
        destinationStride: ULong,
        source: ULong,
        sourceWidth: UInt,
        sourceHeight: UInt,
        palette: ULong,
        scale: UInt,
    ) = Unit

    override fun blitHigh(
        destination: ULong,
        destinationStride: ULong,
        source: ULong,
        sourceWidth: UInt,
        sourceHeight: UInt,
        palette: ULong,
        scale: UInt,
    ) = Unit
}

class FakeCpu : CpuOps {
    var brandString = "Fake Kurt CPU @ 3.00GHz"
    var interruptsEnabled = false
    val msrs = HashMap<UInt, ULong>()

    override fun enableInterrupts() {
        interruptsEnabled = true
    }

    override fun disableInterrupts() {
        interruptsEnabled = false
    }

    override fun waitForInterrupt() = Unit

    override fun hang() = Unit

    override fun timestamp(): ULong = 0UL

    override fun readCr2(): ULong = 0UL

    override fun readCr3(): ULong = 0UL

    override fun invalidatePage(address: ULong) = Unit

    override fun readMsr(msr: UInt): ULong = msrs[msr] ?: 0UL

    override fun writeMsr(msr: UInt, value: ULong) {
        msrs[msr] = value
    }

    override fun storeFence() = Unit

    override fun cpuid(leaf: UInt): CpuidResult = when (leaf) {
        0x80000000u -> CpuidResult(0x80000004u, 0u, 0u, 0u)
        in 0x80000002u..0x80000004u -> brandRegisters(leaf)
        else -> CpuidResult(0u, 0u, 0u, 0u)
    }

    private fun brandRegisters(leaf: UInt): CpuidResult {
        val padded = brandString.padEnd(48, ' ').substring(0, 48)
        val chunk = ((leaf - 0x80000002u).toInt()) * 16
        fun register(offset: Int): UInt {
            var value = 0u
            for (i in 0 until 4) value = value or (padded[chunk + offset + i].code.toUInt() shl (i * 8))
            return value
        }
        return CpuidResult(register(0), register(4), register(8), register(12))
    }
}

class FakeArch : ArchOps {
    var tickCount = 0UL
    var keyboardPollEnabled = false
    val scancodes = ArrayDeque<Int>()

    override fun isrStub(vector: Int): ULong = 0xFFFF_0000UL + vector.toULong()

    override fun loadGdt(descriptorAddress: ULong, codeSelector: UShort, dataSelector: UShort) = Unit

    override fun loadTaskRegister(selector: UShort) = Unit

    override fun loadIdt(descriptorAddress: ULong) = Unit

    override fun setLapicBase(base: ULong) = Unit

    override fun ticks(): ULong = tickCount

    override fun nextScancode(): Int = if (scancodes.isEmpty()) -1 else scancodes.removeFirst()

    override fun enableKeyboardPoll() {
        keyboardPollEnabled = true
    }

    override fun droppedScancodes(): ULong = 0UL

    override fun keyboardInterrupts(): ULong = 0UL

    override fun usbInterrupts(): ULong = 0UL

    override fun smpStart(): Int = 1

    override fun smpCpus(): Int = 1
}

class FakeBoot : BootSource {
    override var hhdmOffset: ULong = 0xFFFF_8000_0000_0000UL
    override var heapStart: ULong = 0x10_0000UL
    override var heapEnd: ULong = 0x110_0000UL
    override var heapUsed: ULong = 0UL
    override var heapTotal: ULong = 0x100_0000UL
    override var pagesStart: ULong = 0UL
    override var pagesEnd: ULong = 0UL
    override var gpuPoolStart: ULong = 0UL
    override var gpuPoolEnd: ULong = 0UL
    override var rsdpAddress: ULong = 0UL
    override var framebufferPresent: UInt = 1u
    override var framebufferAddress: ULong = 0xE000_0000UL
    override var framebufferWidth: ULong = 1280UL
    override var framebufferHeight: ULong = 720UL
    override var framebufferPitch: ULong = 5120UL
    override var framebufferRedShift: UInt = 16u
    override var framebufferGreenShift: UInt = 8u
    override var framebufferBlueShift: UInt = 0u

    var regions = listOf<Triple<ULong, ULong, ULong>>()

    override val memoryMapCount: ULong get() = regions.size.toULong()

    override fun memoryMapBase(index: ULong): ULong = regions[index.toInt()].first

    override fun memoryMapLength(index: ULong): ULong = regions[index.toInt()].second

    override fun memoryMapType(index: ULong): ULong = regions[index.toInt()].third
}

class FakePlatform : Platform {
    override val port = FakePortIo()
    override val memory = FakeRawMemory()
    override val cpu = FakeCpu()
    override val arch = FakeArch()
    override val boot = FakeBoot()
}

object TestPlatform {
    val current = FakePlatform()

    fun install() {
        if (!Hal.isInstalled) Hal.install(current)
    }
}
