package kapi.gpu

interface GpuBuffer {
    val handle: Long
    val bytes: Long
}

interface GpuBackend {
    fun available(): Boolean
    fun alloc(bytes: Long, deviceLocal: Boolean): GpuBuffer?
    fun upload(dst: GpuBuffer, dstOffset: Long, src: ByteArray, srcOffset: Int, bytes: Int)
    fun map(buf: GpuBuffer): Long
    fun dispatch(kernelId: Int, groupsX: Int, groupsY: Int, groupsZ: Int, kernargs: LongArray): Long
    fun wait(fence: Long, timeoutMicros: Long): Boolean
    fun blitToScanout(src: GpuBuffer, srcWidth: Int, srcHeight: Int, srcStride: Int)
    fun free(buf: GpuBuffer)
}

object Gpu {
    var backend: GpuBackend? = null

    fun available(): Boolean = backend?.available() == true
}
