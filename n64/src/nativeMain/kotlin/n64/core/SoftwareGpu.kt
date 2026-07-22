package n64.core

import kapi.gpu.GpuBackend
import kapi.gpu.GpuBuffer
import kapi.gpu.GpuKernel
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.pin
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toLong
import rdpshader.rdpspan

@OptIn(ExperimentalForeignApi::class)
private class SoftwareBuffer(override val words: Int) : GpuBuffer {
    private val data = IntArray(words)
    private val pinned = data.pin()

    override val gpuAddress: Long = pinned.addressOf(0).toLong()

    override fun writeWord(word: Int, value: Int) {
        data[word] = value
    }

    override fun readWord(word: Int): Int = data[word]

    override fun write(dstWord: Int, src: IntArray, srcWord: Int, count: Int) {
        src.copyInto(data, dstWord, srcWord, srcWord + count)
    }

    override fun read(srcWord: Int, dst: IntArray, dstWord: Int, count: Int) {
        data.copyInto(dst, dstWord, srcWord, srcWord + count)
    }

    override fun zero() = data.fill(0)
}

private object SoftwareSpanKernel : GpuKernel {
    override val name = "rdpspan"
}

@OptIn(ExperimentalForeignApi::class)
class SoftwareGpu(private val order: Order = Order.Forward) : GpuBackend {
    enum class Order { Forward, Reverse }

    override val name = "software"

    override val hardware = false

    override fun available(): Boolean = true

    override fun kernel(name: String): GpuKernel? =
        if (name == SoftwareSpanKernel.name) SoftwareSpanKernel else null

    override fun alloc(words: Int): GpuBuffer? = if (words <= 0) null else SoftwareBuffer(words)

    override fun free(buffer: GpuBuffer) {}

    override fun dispatch(kernel: GpuKernel, kernargs: GpuBuffer, groups: Int, threadsPerGroup: Int): Boolean {
        if (kernel !== SoftwareSpanKernel) return false

        val chains = kernargs.readWord(22)
        val limit = if (groups < chains) groups else chains
        if (limit <= 0) return true

        val rdram = pointer(kernargs, 0).toCPointer<UIntVar>()
        val hidden = pointer(kernargs, 2).toCPointer<UByteVar>()
        val spans = pointer(kernargs, 4).toCPointer<UIntVar>()
        val uniforms = pointer(kernargs, 6).toCPointer<UIntVar>()
        val zcom = pointer(kernargs, 8).toCPointer<UIntVar>()
        val zdec = pointer(kernargs, 10).toCPointer<UIntVar>()
        val deltaz = pointer(kernargs, 12).toCPointer<UIntVar>()
        val tmem = pointer(kernargs, 14).toCPointer<UByteVar>()
        val tcdiv = pointer(kernargs, 16).toCPointer<UIntVar>()
        val chainStart = pointer(kernargs, 18).toCPointer<UIntVar>()
        val chainSpans = pointer(kernargs, 20).toCPointer<UIntVar>()

        val dispatch = IntArray(DISPATCH_WORDS)
        dispatch[SIZE_X] = threadsPerGroup
        dispatch[SIZE_Y] = 1
        dispatch[SIZE_Z] = 1

        val pinnedDispatch = dispatch.pin()
        val dispatchPointer = pinnedDispatch.addressOf(0).reinterpret<UIntVar>()

        for (step in 0 until limit) {
            val group = if (order == Order.Reverse) limit - 1 - step else step
            dispatch[GROUP_X] = group
            for (lane in 0 until threadsPerGroup) {
                dispatch[ITEM_X] = lane
                rdpspan(
                    dispatchPointer,
                    rdram,
                    hidden,
                    spans,
                    uniforms,
                    zcom,
                    zdec,
                    deltaz,
                    tmem,
                    tcdiv,
                    chainStart,
                    chainSpans,
                    chains.toUInt(),
                )
            }
        }
        pinnedDispatch.unpin()
        return true
    }

    private companion object {
        const val DISPATCH_WORDS = 9
        const val ITEM_X = 0
        const val GROUP_X = 3
        const val SIZE_X = 6
        const val SIZE_Y = 7
        const val SIZE_Z = 8
    }

    private fun pointer(kernargs: GpuBuffer, word: Int): Long =
        (kernargs.readWord(word).toLong() and 0xFFFFFFFFL) or
            ((kernargs.readWord(word + 1).toLong() and 0xFFFFFFFFL) shl 32)
}
