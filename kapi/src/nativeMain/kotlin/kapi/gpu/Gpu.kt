package kapi.gpu

interface GpuBuffer {
    val gpuAddress: Long
    val words: Int

    fun writeWord(word: Int, value: Int)
    fun readWord(word: Int): Int
    fun write(dstWord: Int, src: IntArray, srcWord: Int, count: Int)
    fun read(srcWord: Int, dst: IntArray, dstWord: Int, count: Int)
    fun zero()
}

interface GpuKernel {
    val name: String
}

interface GpuBackend {
    val name: String

    val hardware: Boolean get() = true

    fun available(): Boolean
    fun kernel(name: String): GpuKernel?
    fun alloc(words: Int): GpuBuffer?
    fun free(buffer: GpuBuffer)
    fun dispatch(kernel: GpuKernel, kernargs: GpuBuffer, groups: Int, threadsPerGroup: Int): Boolean
    fun sync() {}
    fun busyCycles(): Long = 0
    fun syncCount(): Long = 0
    fun nowCycles(): Long = 0
    fun writeCycles(): Long = 0
    fun readCycles(): Long = 0
    fun writeWords(): Long = 0
    fun readWords(): Long = 0
}

object Gpu {
    enum class Preference {
        Auto,
        Hardware,
        Software,
    }

    private val registered = ArrayList<GpuBackend>()

    var backend: GpuBackend? = null
        private set

    var preference: Preference = Preference.Auto
        private set

    val name: String? get() = backend?.name

    fun register(candidate: GpuBackend) {
        if (!candidate.available()) return
        if (registered.any { it.name == candidate.name }) return
        registered.add(candidate)
        reselect()
    }

    fun prefer(value: Preference) {
        if (value == preference) return
        preference = value
        reselect()
    }

    fun names(): List<String> = registered.map { it.name }

    fun hasHardware(): Boolean = registered.any { it.hardware }

    fun hasSoftware(): Boolean = registered.any { !it.hardware }

    fun unregister() {
        registered.clear()
        backend = null
    }

    fun available(): Boolean = backend?.available() == true

    private fun reselect() {
        val hardware = registered.firstOrNull { it.hardware }
        val software = registered.firstOrNull { !it.hardware }
        backend = when (preference) {
            Preference.Hardware -> hardware
            Preference.Software -> software
            Preference.Auto -> hardware ?: software
        }
    }
}
