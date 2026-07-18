package kernel.drivers.gpu.vega

import hal.Cpu
import kapi.gpu.GpuBackend
import kapi.gpu.GpuBuffer
import kapi.gpu.GpuKernel
import kernel.KLog

private class VegaGpuBuffer(val alloc: VramAlloc, private val regs: VegaRegs) : GpuBuffer {
    override val gpuAddress: Long get() = alloc.gpuAddress.toLong()
    override val words: Int get() = (alloc.bytes / 4UL).toInt()

    override fun writeWord(word: Int, value: Int) = alloc.writeDword(word.toULong() * 4UL, value.toUInt())

    override fun readWord(word: Int): Int {
        regs.write(VegaReg.HDP_READ_CACHE_INVALIDATE, 1u)
        return alloc.readDword(word.toULong() * 4UL).toInt()
    }

    override fun write(dstWord: Int, src: IntArray, srcWord: Int, count: Int) {
        var i = 0
        while (i < count) {
            alloc.writeDword((dstWord + i).toULong() * 4UL, src[srcWord + i].toUInt())
            i++
        }
        Cpu.storeFence()
    }

    override fun read(srcWord: Int, dst: IntArray, dstWord: Int, count: Int) {
        regs.write(VegaReg.HDP_READ_CACHE_INVALIDATE, 1u)
        var i = 0
        while (i < count) {
            dst[dstWord + i] = alloc.readDword((srcWord + i).toULong() * 4UL).toInt()
            i++
        }
    }

    override fun zero() = alloc.zero()
}

private class VegaGpuKernel(val shader: Shader) : GpuKernel {
    override val name: String get() = shader.name
}

class VegaBackend(private val compute: VegaCompute, private val regs: VegaRegs) : GpuBackend {
    override val name: String = "vega"

    private val kernels = mutableMapOf<String, GpuKernel?>()

    override fun available(): Boolean = true

    override fun kernel(name: String): GpuKernel? =
        kernels.getOrPut(name) { VegaShaderLoader.load(name)?.let { VegaGpuKernel(it) } }

    override fun alloc(words: Int): GpuBuffer? =
        VegaVram.allocate(words.toULong() * 4UL)?.let { VegaGpuBuffer(it, regs) }

    override fun free(buffer: GpuBuffer) {
    }

    private var loggedDispatch = false
    private var loggedFailure = false

    override fun dispatch(kernel: GpuKernel, kernargs: GpuBuffer, groups: Int, threadsPerGroup: Int): Boolean {
        val shader = (kernel as VegaGpuKernel).shader
        val kernarg = (kernargs as VegaGpuBuffer).alloc
        val ok = compute.dispatch(shader, kernarg, groups.toUInt(), threadsPerGroup.toUInt())
        if (!loggedDispatch) {
            loggedDispatch = true
            KLog.step("gpu", "rdp dispatch first", ok, "${shader.name} groups=$groups")
        }
        if (!ok && !loggedFailure) {
            loggedFailure = true
            KLog.step("gpu", "rdp dispatch", false, "${shader.name} groups=$groups fence timeout")
        }
        return ok
    }

    fun selfTest(): Boolean {
        val krn = kernel("rdp")
        if (krn == null) {
            KLog.step("gpu", "rdp selftest", false, "kernel missing")
            return false
        }
        val shader = (krn as VegaGpuKernel).shader

        val ok32 = selfTestFill(shader, 1, "rdp fill32")
        val ok16 = selfTestFill(shader, 0, "rdp fill16")
        return ok32 && ok16
    }

    private fun selfTestFill(shader: Shader, kind: Int, label: String): Boolean {
        val rd = alloc(64)
        val hd = alloc(64)
        val pr = alloc(64)
        val ka = alloc(16)
        if (rd == null || hd == null || pr == null || ka == null) {
            KLog.step("gpu", label, false, "scratch alloc")
            return false
        }

        rd.zero()
        hd.zero()

        val prim = intArrayOf(kind, 0, 0, 1, 1, 2, 0, 0x11112222.toInt(), 0, 0, 0, 0)
        pr.write(0, prim, 0, prim.size)

        ka.writeWord(0, (rd.gpuAddress and 0xFFFFFFFFL).toInt())
        ka.writeWord(1, (rd.gpuAddress ushr 32).toInt())
        ka.writeWord(2, (hd.gpuAddress and 0xFFFFFFFFL).toInt())
        ka.writeWord(3, (hd.gpuAddress ushr 32).toInt())
        ka.writeWord(4, (pr.gpuAddress and 0xFFFFFFFFL).toInt())
        ka.writeWord(5, (pr.gpuAddress ushr 32).toInt())
        ka.writeWord(6, 1)
        ka.writeWord(7, 0)
        ka.writeWord(8, 0)
        ka.writeWord(9, 2)
        ka.writeWord(10, 2)

        val dispatched = compute.dispatch(shader, (ka as VegaGpuBuffer).alloc, 1u, 64u)
        val w0 = rd.readWord(0)
        val w1 = rd.readWord(1)
        val pass = dispatched && w0 == 0x11112222.toInt() && w1 == 0x11112222.toInt()

        KLog.step(
            "gpu",
            label,
            pass,
            "disp=$dispatched w0=${KLog.hex(w0.toUInt())} w1=${KLog.hex(w1.toUInt())}",
        )
        return pass
    }
}
