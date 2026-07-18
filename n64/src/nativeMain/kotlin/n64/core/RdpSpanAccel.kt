package n64.core

import kapi.gfx.GfxPool
import kapi.gfx.ResidentSurface
import kapi.gpu.Gpu
import kapi.gpu.GpuBuffer
import kapi.gpu.GpuKernel

class RdpSpanAccel(
    private val rdram: IntArray,
    private val hidden: ByteArray,
    private val tmem: ByteArray,
    private val zcom: IntArray,
    private val zdec: IntArray,
    private val deltaz: IntArray,
    private val tcdiv: IntArray,
    private val invalidate: (Int, Int) -> Unit,
) {
    var disabled = false
        private set

    private var kernel: GpuKernel? = null
    private var surface: ResidentSurface? = null
    private var rdramBuf: GpuBuffer? = null
    private var hiddenBuf: GpuBuffer? = null
    private var spansBuf: GpuBuffer? = null
    private var uniformBuf: GpuBuffer? = null
    private var zcomBuf: GpuBuffer? = null
    private var zdecBuf: GpuBuffer? = null
    private var deltazBuf: GpuBuffer? = null
    private var tmemBuf: GpuBuffer? = null
    private var tcdivBuf: GpuBuffer? = null
    private var kernargBuf: GpuBuffer? = null
    private var probed = false

    private val spans = IntArray(SPANS * SPAN_STRIDE)
    private val tmemScratch = IntArray(TMEM_WORDS)
    private var lastTmemGen = -1

    var dispatched = 0L
        private set
    var offloadedPixels = 0L
        private set

    private fun ensure(): Boolean {
        if (disabled) return false
        if (surface != null) return true
        if (probed) return false
        probed = true
        val krn = Gpu.backend?.kernel("rdpspan") ?: return false
        val rd = GfxPool.buffer("n64.rdram", RDRAM_SIZE / 4) ?: return false
        val hd = GfxPool.buffer("n64.hidden", RDRAM_SIZE / 8) ?: return false
        val sp = GfxPool.buffer("n64.spans", SPANS * SPAN_STRIDE) ?: return false
        val un = GfxPool.buffer("n64.uniform", UNIFORM_WORDS) ?: return false
        val zc = GfxPool.buffer("n64.zcom", zcom.size) ?: return false
        val zd = GfxPool.buffer("n64.zdec", zdec.size) ?: return false
        val dz = GfxPool.buffer("n64.deltaz", deltaz.size) ?: return false
        val tm = GfxPool.buffer("n64.tmem", TMEM_WORDS) ?: return false
        val tcd = GfxPool.buffer("n64.tcdiv", tcdiv.size) ?: return false
        val ka = GfxPool.buffer("n64.kernarg", KERNARG_WORDS) ?: return false
        zc.write(0, zcom, 0, zcom.size)
        zd.write(0, zdec, 0, zdec.size)
        dz.write(0, deltaz, 0, deltaz.size)
        tcd.write(0, tcdiv, 0, tcdiv.size)
        kernel = krn
        rdramBuf = rd
        hiddenBuf = hd
        spansBuf = sp
        uniformBuf = un
        zcomBuf = zc
        zdecBuf = zd
        deltazBuf = dz
        tmemBuf = tm
        tcdivBuf = tcd
        kernargBuf = ka
        surface = ResidentSurface(rd, hd, rdram, hidden, RDRAM_MASK, invalidate)
        return true
    }

    fun render(
        first: Int,
        last: Int,
        uniform: IntArray,
        valid: BooleanArray,
        lx: IntArray,
        rx: IntArray,
        unscrx: IntArray,
        spanR: IntArray,
        spanG: IntArray,
        spanB: IntArray,
        spanA: IntArray,
        spanZ: IntArray,
        spanS: IntArray,
        spanT: IntArray,
        spanW: IntArray,
        minorX: IntArray,
        majorX: IntArray,
        invalY: IntArray,
        tmemGen: Int,
    ): Long {
        if (!ensure()) return -1

        val flip = uniform[U_FLIP] != 0
        val scissorXh = uniform[U_SCISSOR_XH]
        val scissorXl = uniform[U_SCISSOR_XL]

        var count = 0
        var pixels = 0L
        for (i in first..last) {
            if (!valid[i]) continue
            val xstart = lx[i]
            val xendsc = rx[i]
            val length = if (flip) xstart - xendsc else xendsc - xstart
            if (length < 0) continue

            val base = count * SPAN_STRIDE
            spans[base + SPAN_ROW] = i
            spans[base + SPAN_LX] = xstart
            spans[base + SPAN_RX] = xendsc
            spans[base + SPAN_UNSCRX] = unscrx[i]
            spans[base + SPAN_R] = spanR[i]
            spans[base + SPAN_G] = spanG[i]
            spans[base + SPAN_B] = spanB[i]
            spans[base + SPAN_A] = spanA[i]
            spans[base + SPAN_Z] = spanZ[i]
            spans[base + SPAN_S] = spanS[i]
            spans[base + SPAN_T] = spanT[i]
            spans[base + SPAN_W] = spanW[i]
            for (k in 0 until 4) {
                spans[base + SPAN_MINORX0 + k] = minorX[i * 4 + k]
                spans[base + SPAN_MAJORX0 + k] = majorX[i * 4 + k]
                spans[base + SPAN_INVALY0 + k] = invalY[i * 4 + k]
            }
            count++

            val lo = maxOf(xendsc, scissorXh)
            val hi = minOf(maxOf(xstart, xendsc), scissorXl - 1)
            if (hi >= lo) pixels += (hi - lo + 1).toLong()
        }
        if (count == 0) return 0

        val s = surface ?: return -1
        val sp = spansBuf ?: return -1
        val un = uniformBuf ?: return -1
        val tm = tmemBuf ?: return -1
        val krn = kernel ?: return -1

        val ci = uniform[U_COLORIMAGE]
        val zi = uniform[U_ZIMAGE]
        val w = uniform[U_COLORWIDTH]
        val cs = uniform[U_COLORSIZE]
        s.prepare(ci, zi, w, cs, zi != 0, first, last)

        un.write(0, uniform, 0, UNIFORM_WORDS)
        sp.write(0, spans, 0, count * SPAN_STRIDE)
        if (uniform[U_TEXTURE] != 0 && tmemGen != lastTmemGen) {
            packTmem()
            tm.write(0, tmemScratch, 0, TMEM_WORDS)
            lastTmemGen = tmemGen
        }
        writeKernarg(count)

        val groups = (count + 63) / 64
        val ok = Gpu.backend?.dispatch(krn, kernargBuf!!, groups, 64) ?: false
        if (!ok) {
            disabled = true
            return -1
        }
        s.markDirty(first, last)
        dispatched++
        offloadedPixels += pixels
        return pixels
    }

    fun flush() {
        surface?.flush()
    }

    private fun writeKernarg(count: Int) {
        val ka = kernargBuf ?: return
        putPtr(ka, 0, rdramBuf!!)
        putPtr(ka, 2, hiddenBuf!!)
        putPtr(ka, 4, spansBuf!!)
        putPtr(ka, 6, uniformBuf!!)
        putPtr(ka, 8, zcomBuf!!)
        putPtr(ka, 10, zdecBuf!!)
        putPtr(ka, 12, deltazBuf!!)
        putPtr(ka, 14, tmemBuf!!)
        putPtr(ka, 16, tcdivBuf!!)
        ka.writeWord(18, count)
    }

    private fun putPtr(ka: GpuBuffer, word: Int, buf: GpuBuffer) {
        ka.writeWord(word, (buf.gpuAddress and 0xFFFFFFFFL).toInt())
        ka.writeWord(word + 1, (buf.gpuAddress ushr 32).toInt())
    }

    private fun packTmem() {
        for (word in 0 until TMEM_WORDS) {
            val b = word shl 2
            tmemScratch[word] = (tmem[b].toInt() and 0xFF) or
                ((tmem[b + 1].toInt() and 0xFF) shl 8) or
                ((tmem[b + 2].toInt() and 0xFF) shl 16) or
                ((tmem[b + 3].toInt() and 0xFF) shl 24)
        }
    }

    companion object {
        const val SPAN_STRIDE = 24
        const val SPAN_ROW = 0
        const val SPAN_LX = 1
        const val SPAN_RX = 2
        const val SPAN_UNSCRX = 3
        const val SPAN_R = 4
        const val SPAN_G = 5
        const val SPAN_B = 6
        const val SPAN_A = 7
        const val SPAN_Z = 8
        const val SPAN_MINORX0 = 9
        const val SPAN_MAJORX0 = 13
        const val SPAN_INVALY0 = 17
        const val SPAN_S = 21
        const val SPAN_T = 22
        const val SPAN_W = 23

        const val U_FLIP = 0
        const val U_SCISSOR_XH = 1
        const val U_SCISSOR_XL = 2
        const val U_COLORSIZE = 3
        const val U_COLORIMAGE = 4
        const val U_COLORWIDTH = 5
        const val U_SPANS_DR = 6
        const val U_SPANS_DG = 7
        const val U_SPANS_DB = 8
        const val U_SPANS_DA = 9
        const val U_PRIM = 10
        const val U_ENV = 11
        const val U_BLEND = 12
        const val U_FOG = 13
        const val U_PRIMLOD = 14
        const val U_CRGB = 15
        const val U_CALPHA = 23
        const val U_BLENDSEL = 31
        const val U_SPANS_DZ = 39
        const val U_ZIMAGE = 40
        const val U_ZMODE = 41
        const val U_ZCOMPARE = 42
        const val U_ZUPDATE = 43
        const val U_ZSOURCE = 44
        const val U_ALPHACOMPARE = 45
        const val U_CTA = 46
        const val U_ACS = 47
        const val U_FORCEBLEND = 48
        const val U_PRIMDEPTH = 49
        const val U_PRIMDZ = 50
        const val U_PRIMDZENC = 51
        const val U_SPANDZPIX = 52
        const val U_SPANDZPIXENC = 53
        const val U_USEDEPTH = 54
        const val U_SHADE = 55
        const val U_TEXTURE = 56
        const val U_PERSP = 57
        const val U_SAMPLETYPE = 58
        const val U_TLUTEN = 59
        const val U_TLUTTYPE = 60
        const val U_MIDTEXEL = 61
        const val U_SPANS_DS = 62
        const val U_SPANS_DT = 63
        const val U_SPANS_DW = 64
        const val T_FORMAT = 65
        const val T_SIZE = 66
        const val T_LINE = 67
        const val T_TMEM = 68
        const val T_PALETTE = 69
        const val T_CLAMPS = 70
        const val T_MIRRORS = 71
        const val T_MASKS = 72
        const val T_SHIFTS = 73
        const val T_CLAMPT = 74
        const val T_MIRRORT = 75
        const val T_MASKT = 76
        const val T_SHIFTT = 77
        const val T_SL = 78
        const val T_TL = 79
        const val T_SH = 80
        const val T_TH = 81
        const val T_CLAMPDIFFS = 82
        const val T_CLAMPDIFFT = 83
        const val T_CLAMPENS = 84
        const val T_CLAMPENT = 85
        const val T_MASKSCLAMPED = 86
        const val T_MASKTCLAMPED = 87
        const val UNIFORM_WORDS = 88

        private const val SPANS = 1024
        private const val TMEM_WORDS = 1024
        private const val KERNARG_WORDS = 20
    }
}
