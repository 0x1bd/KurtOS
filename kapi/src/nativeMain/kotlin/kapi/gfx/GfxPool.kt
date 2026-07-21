package kapi.gfx

import kapi.gpu.Gpu
import kapi.gpu.GpuBuffer

object GfxPool {
    private val buffers = HashMap<String, GpuBuffer>()
    private var failed = false

    fun buffer(name: String, words: Int): GpuBuffer? {
        buffers[name]?.let { return it }
        if (failed) return null
        val b = Gpu.backend?.alloc(words)
        if (b == null) {
            failed = true
            return null
        }
        buffers[name] = b
        return b
    }

    fun reset() {
        buffers.clear()
        failed = false
    }
}
