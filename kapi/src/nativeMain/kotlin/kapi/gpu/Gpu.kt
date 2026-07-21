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
    var backend: GpuBackend? = null
        private set

    val name: String? get() = backend?.name

    fun register(candidate: GpuBackend) {
        if (candidate.available()) backend = candidate
    }

    fun unregister() {
        backend = null
    }

    fun available(): Boolean = backend?.available() == true
}
