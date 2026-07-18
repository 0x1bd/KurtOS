package n64.core

import kapi.gpu.Gpu
import kapi.gpu.GpuBuffer
import kapi.gpu.GpuKernel

const val RDP_KIND_FILL16 = 0
const val RDP_KIND_FILL32 = 1

class RdpAccel(
    private val rdram: IntArray,
    private val hidden: ByteArray,
    private val invalidate: (Int, Int) -> Unit,
) {
    private var kernel: GpuKernel? = null
    private var probed = false

    var disabled = false
        private set

    private var rdramBuf: GpuBuffer? = null
    private var hiddenBuf: GpuBuffer? = null
    private var primBuf: GpuBuffer? = null
    private var kernargBuf: GpuBuffer? = null

    private val prims = IntArray(PRIM_CAPACITY * PRIM_STRIDE)
    private var primCount = 0

    private var bboxX0 = 0
    private var bboxY0 = 0
    private var bboxX1 = 0
    private var bboxY1 = 0

    private var rdramLo = Int.MAX_VALUE
    private var rdramHi = Int.MIN_VALUE
    private var hiddenLo = Int.MAX_VALUE
    private var hiddenHi = Int.MIN_VALUE

    private var batchKind = -1
    private var batchImage = 0
    private var batchWidth = 0

    private var hiddenScratch = IntArray(0)

    var dispatched = 0L
        private set
    var offloadedPixels = 0L
        private set

    private fun ready(): Boolean {
        if (disabled) return false
        if (!probed) {
            probed = true
            val backend = Gpu.backend ?: return false
            val krn = backend.kernel("rdp") ?: return false
            val rd = backend.alloc(RDRAM_SIZE / 4) ?: return false
            val hd = backend.alloc(RDRAM_SIZE / 8) ?: return false
            val pr = backend.alloc(PRIM_CAPACITY * PRIM_STRIDE) ?: return false
            val ka = backend.alloc(KERNARG_WORDS) ?: return false
            kernel = krn
            rdramBuf = rd
            hiddenBuf = hd
            primBuf = pr
            kernargBuf = ka
            hiddenScratch = IntArray(RDRAM_SIZE / 8)
        }
        return kernel != null
    }

    fun enqueueFill(
        kind: Int,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        width: Int,
        image: Int,
        fillColor: Int,
    ): Boolean {
        if (x1 < x0 || y1 < y0) return true
        if (!ready()) return false
        if (primCount > 0 && (kind != batchKind || image != batchImage || width != batchWidth)) flush()
        if (primCount == PRIM_CAPACITY) flush()
        if (disabled) return false
        if (primCount == 0) {
            batchKind = kind
            batchImage = image
            batchWidth = width
        }

        val base = primCount * PRIM_STRIDE
        prims[base + PRIM_KIND] = kind
        prims[base + PRIM_X0] = x0
        prims[base + PRIM_Y0] = y0
        prims[base + PRIM_X1] = x1
        prims[base + PRIM_Y1] = y1
        prims[base + PRIM_WIDTH] = width
        prims[base + PRIM_IMAGE] = image
        prims[base + PRIM_ARG0] = fillColor

        if (primCount == 0) {
            bboxX0 = x0; bboxY0 = y0; bboxX1 = x1; bboxY1 = y1
        } else {
            if (x0 < bboxX0) bboxX0 = x0
            if (y0 < bboxY0) bboxY0 = y0
            if (x1 > bboxX1) bboxX1 = x1
            if (y1 > bboxY1) bboxY1 = y1
        }

        val bytesPer = if (kind == RDP_KIND_FILL16) 2 else 4
        val loByte = image + (y0 * width + x0) * bytesPer
        val hiByte = image + (y1 * width + x1) * bytesPer + (bytesPer - 1)
        val loWord = (loByte and RDRAM_MASK) ushr 2
        val hiWord = (hiByte and RDRAM_MASK) ushr 2
        if (loWord < rdramLo) rdramLo = loWord
        if (hiWord > rdramHi) rdramHi = hiWord

        if (kind == RDP_KIND_FILL16) {
            val hLo = ((loByte and RDRAM_MASK) ushr 1) ushr 2
            val hHi = ((hiByte and RDRAM_MASK) ushr 1) ushr 2
            if (hLo < hiddenLo) hiddenLo = hLo
            if (hHi > hiddenHi) hiddenHi = hHi
        }

        primCount++
        return true
    }

    fun flush() {
        if (primCount == 0) return
        val krn = kernel
        val rd = rdramBuf
        val hd = hiddenBuf
        val pr = primBuf
        val ka = kernargBuf
        val backend = Gpu.backend
        if (krn == null || rd == null || hd == null || pr == null || ka == null || backend == null) {
            softwareFallback()
            reset()
            return
        }

        val bboxW = bboxX1 - bboxX0 + 1
        val bboxH = bboxY1 - bboxY0 + 1

        pr.write(0, prims, 0, primCount * PRIM_STRIDE)

        val rdCount = rdramHi - rdramLo + 1
        rd.write(rdramLo, rdram, rdramLo, rdCount)

        val hasHidden = hiddenHi >= hiddenLo
        if (hasHidden) {
            packHidden(hiddenLo, hiddenHi)
            hd.write(hiddenLo, hiddenScratch, hiddenLo, hiddenHi - hiddenLo + 1)
        }

        ka.writeWord(0, (rd.gpuAddress and 0xFFFFFFFFL).toInt())
        ka.writeWord(1, (rd.gpuAddress ushr 32).toInt())
        ka.writeWord(2, (hd.gpuAddress and 0xFFFFFFFFL).toInt())
        ka.writeWord(3, (hd.gpuAddress ushr 32).toInt())
        ka.writeWord(4, (pr.gpuAddress and 0xFFFFFFFFL).toInt())
        ka.writeWord(5, (pr.gpuAddress ushr 32).toInt())
        ka.writeWord(6, primCount)
        ka.writeWord(7, bboxX0)
        ka.writeWord(8, bboxY0)
        ka.writeWord(9, bboxW)
        ka.writeWord(10, bboxH)

        val threads = bboxW * bboxH
        val groups = (threads + 63) / 64

        val ok = backend.dispatch(krn, ka, groups, 64)
        if (!ok) {
            disabled = true
            softwareFallback()
            reset()
            return
        }

        rd.read(rdramLo, rdram, rdramLo, rdCount)
        invalidate(rdramLo, rdramHi)
        if (hasHidden) {
            hd.read(hiddenLo, hiddenScratch, hiddenLo, hiddenHi - hiddenLo + 1)
            unpackHidden(hiddenLo, hiddenHi)
        }

        dispatched++
        offloadedPixels += threads.toLong()
        reset()
    }

    private fun softwareFallback() {
        for (p in 0 until primCount) {
            val base = p * PRIM_STRIDE
            val kind = prims[base + PRIM_KIND]
            val x0 = prims[base + PRIM_X0]
            val y0 = prims[base + PRIM_Y0]
            val x1 = prims[base + PRIM_X1]
            val y1 = prims[base + PRIM_Y1]
            val width = prims[base + PRIM_WIDTH]
            val image = prims[base + PRIM_IMAGE]
            val fillColor = prims[base + PRIM_ARG0]
            for (y in y0..y1) {
                for (x in x0..x1) {
                    if (kind == RDP_KIND_FILL16) {
                        val value = if (x and 1 == 0) (fillColor ushr 16) and 0xFFFF else fillColor and 0xFFFF
                        val at = image + (y * width + x) * 2
                        writeRam16(at, value)
                        hidden[(at and RDRAM_MASK) ushr 1] = (if (value and 1 != 0) 3 else 0).toByte()
                    } else {
                        rdram[(image + (y * width + x) * 4 and RDRAM_MASK) ushr 2] = fillColor
                    }
                }
            }
        }
        if (rdramHi >= rdramLo) invalidate(rdramLo, rdramHi)
    }

    private fun writeRam16(addr: Int, value: Int) {
        val index = (addr and RDRAM_MASK) ushr 2
        val shift = 16 - ((addr and 2) shl 3)
        rdram[index] = (rdram[index] and (0xFFFF shl shift).inv()) or ((value and 0xFFFF) shl shift)
    }

    private fun packHidden(loWord: Int, hiWord: Int) {
        for (w in loWord..hiWord) {
            val b = w shl 2
            hiddenScratch[w] = (hidden[b].toInt() and 0xFF) or
                ((hidden[b + 1].toInt() and 0xFF) shl 8) or
                ((hidden[b + 2].toInt() and 0xFF) shl 16) or
                ((hidden[b + 3].toInt() and 0xFF) shl 24)
        }
    }

    private fun unpackHidden(loWord: Int, hiWord: Int) {
        for (w in loWord..hiWord) {
            val word = hiddenScratch[w]
            val b = w shl 2
            hidden[b] = word.toByte()
            hidden[b + 1] = (word ushr 8).toByte()
            hidden[b + 2] = (word ushr 16).toByte()
            hidden[b + 3] = (word ushr 24).toByte()
        }
    }

    fun reset() {
        primCount = 0
        batchKind = -1
        rdramLo = Int.MAX_VALUE
        rdramHi = Int.MIN_VALUE
        hiddenLo = Int.MAX_VALUE
        hiddenHi = Int.MIN_VALUE
    }

    val pending: Boolean get() = primCount > 0

    companion object {
        private const val PRIM_CAPACITY = 4096
        private const val PRIM_STRIDE = 12
        private const val KERNARG_WORDS = 16

        private const val PRIM_KIND = 0
        private const val PRIM_X0 = 1
        private const val PRIM_Y0 = 2
        private const val PRIM_X1 = 3
        private const val PRIM_Y1 = 4
        private const val PRIM_WIDTH = 5
        private const val PRIM_IMAGE = 6
        private const val PRIM_ARG0 = 7
    }
}
