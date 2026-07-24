package hal

interface PortIo {
    fun read8(port: UShort): UByte
    fun write8(port: UShort, value: UByte)
    fun read16(port: UShort): UShort
    fun write16(port: UShort, value: UShort)
    fun read32(port: UShort): UInt
    fun write32(port: UShort, value: UInt)
    fun wait()
}

interface RawMemoryOps {
    fun read8(address: ULong): UByte
    fun read16(address: ULong): UShort
    fun read32(address: ULong): UInt
    fun read64(address: ULong): ULong
    fun write8(address: ULong, value: UByte)
    fun write16(address: ULong, value: UShort)
    fun write32(address: ULong, value: UInt)
    fun write64(address: ULong, value: ULong)
    fun fill32(address: ULong, value: UInt, count: ULong)
    fun copy(destination: ULong, source: ULong, bytes: ULong)
    fun copyStream(destination: ULong, source: ULong, bytes: ULong)
    fun zero(address: ULong, length: ULong)

    fun blitIndexed(
        destination: ULong,
        destinationStride: ULong,
        source: ULong,
        sourceWidth: UInt,
        sourceHeight: UInt,
        palette: ULong,
        scale: UInt,
    )

    fun blitHigh(
        destination: ULong,
        destinationStride: ULong,
        source: ULong,
        sourceWidth: UInt,
        sourceHeight: UInt,
        palette: ULong,
        scale: UInt,
    )
}

interface CpuOps {
    fun enableInterrupts()
    fun disableInterrupts()
    fun waitForInterrupt()
    fun hang()
    fun timestamp(): ULong
    fun readCr2(): ULong
    fun readCr3(): ULong
    fun invalidatePage(address: ULong)
    fun readMsr(msr: UInt): ULong
    fun writeMsr(msr: UInt, value: ULong)
    fun storeFence()
    fun cpuid(leaf: UInt): CpuidResult
}

interface ArchOps {
    fun isrStub(vector: Int): ULong
    fun loadGdt(descriptorAddress: ULong, codeSelector: UShort, dataSelector: UShort)
    fun loadTaskRegister(selector: UShort)
    fun loadIdt(descriptorAddress: ULong)
    fun setLapicBase(base: ULong)
    fun ticks(): ULong
    fun nextScancode(): Int
    fun enableKeyboardPoll()
    fun droppedScancodes(): ULong
    fun keyboardInterrupts(): ULong
    fun usbInterrupts(): ULong
    fun smpStart(): Int
    fun smpCpus(): Int
}

interface BootSource {
    val hhdmOffset: ULong
    val heapStart: ULong
    val heapEnd: ULong
    val heapUsed: ULong
    val heapTotal: ULong
    val pagesStart: ULong
    val pagesEnd: ULong
    val gpuPoolStart: ULong
    val gpuPoolEnd: ULong
    val rsdpAddress: ULong
    val framebufferPresent: UInt
    val framebufferAddress: ULong
    val framebufferWidth: ULong
    val framebufferHeight: ULong
    val framebufferPitch: ULong
    val framebufferRedShift: UInt
    val framebufferGreenShift: UInt
    val framebufferBlueShift: UInt
    val memoryMapCount: ULong
    fun memoryMapBase(index: ULong): ULong
    fun memoryMapLength(index: ULong): ULong
    fun memoryMapType(index: ULong): ULong
}

interface Platform {
    val port: PortIo
    val memory: RawMemoryOps
    val cpu: CpuOps
    val arch: ArchOps
    val boot: BootSource
}

object Hal {
    lateinit var port: PortIo
        private set

    lateinit var memory: RawMemoryOps
        private set

    lateinit var cpu: CpuOps
        private set

    lateinit var arch: ArchOps
        private set

    lateinit var boot: BootSource
        private set

    val isInstalled: Boolean get() = ::port.isInitialized

    fun install(platform: Platform) {
        port = platform.port
        memory = platform.memory
        cpu = platform.cpu
        arch = platform.arch
        boot = platform.boot
    }
}
