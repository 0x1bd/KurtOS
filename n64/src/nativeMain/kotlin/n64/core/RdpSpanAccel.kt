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
    private val spansBufs = arrayOfNulls<GpuBuffer>(SLOTS)
    private val uniformBufs = arrayOfNulls<GpuBuffer>(SLOTS)
    private val kernargBufs = arrayOfNulls<GpuBuffer>(SLOTS)
    private val rowStartBufs = arrayOfNulls<GpuBuffer>(SLOTS)
    private val rowSpanBufs = arrayOfNulls<GpuBuffer>(SLOTS)
    private var identityBuf: GpuBuffer? = null
    private var zcomBuf: GpuBuffer? = null
    private var zdecBuf: GpuBuffer? = null
    private var deltazBuf: GpuBuffer? = null
    private val tmemBufs = arrayOfNulls<GpuBuffer>(SLOTS)
    private var tcdivBuf: GpuBuffer? = null
    private var slot = 0
    private var probed = false

    private val spans = IntArray(SPANS * SPAN_STRIDE)
    private val rowFill = IntArray(MAX_ROWS)
    private val rowCursor = IntArray(MAX_ROWS)
    private val rowSpans = IntArray(SPANS)
    private val spanLo = IntArray(SPANS)
    private val spanHi = IntArray(SPANS)
    private val spanChain = IntArray(SPANS)
    private val chainStart = IntArray(SPANS + 1)
    private val chainSpans = IntArray(SPANS)
    private val order = IntArray(MAX_ROW_SPANS)
    private val compFill = IntArray(MAX_ROW_SPANS)
    private val compCursor = IntArray(MAX_ROW_SPANS)
    private val tmemPacked = IntArray(TMEM_WORDS * TMEM_SLOTS)

    private var accActive = false
    private var accCount = 0
    private val accUniform = IntArray(UNIFORM_WORDS)
    private var accTmemGen = 0
    private var accCi = 0
    private var accZi = 0
    private var accW = 0
    private var accCs = 0
    private var accRowLo = 0
    private var accRowHi = 0
    private var accColorAux = false
    private val accTmemGens = IntArray(TMEM_SLOTS)
    private var accTmemCount = 0
    private var accTmemSlot = 0
    private var accRowsAscending = true
    private var accLastRow = 0
    private var accPixels = 0L

    var dispatched = 0L
        private set
    var spansSubmitted = 0L
        private set
    var groupsSubmitted = 0L
        private set
    var breakTmem = 0L
        private set
    var breakUniform = 0L
        private set
    var breakCapacity = 0L
        private set
    var breakForced = 0L
        private set
    var offloadedPixels = 0L
        private set
    var failReason = 0
        private set
    val rebinds: Int get() = surface?.rebinds ?: 0
    val rebindBits: Int get() = surface?.rebindBits ?: 0
    val flushes: Int get() = surface?.flushes ?: 0
    val colorWordsRead: Long get() = surface?.colorWordsRead ?: 0L
    val zWordsRead: Long get() = surface?.zWordsRead ?: 0L
    val auxWordsRead: Long get() = surface?.auxWordsRead ?: 0L

    private fun ensure(): Boolean {
        if (disabled) return false
        if (surface != null) return true
        if (probed) return false
        failReason = 1
        val krn = Gpu.backend?.kernel("rdpspan") ?: return false
        probed = true
        failReason = 2
        val rd = GfxPool.buffer("n64.rdram", RDRAM_SIZE / 4) ?: return false
        failReason = 3
        val hd = GfxPool.buffer("n64.hidden", RDRAM_SIZE / 8) ?: return false
        failReason = 4
        for (i in 0 until SLOTS) {
            spansBufs[i] = GfxPool.buffer("n64.spans.$i", SPANS * SPAN_STRIDE) ?: return false
            uniformBufs[i] = GfxPool.buffer("n64.uniform.$i", UNIFORM_WORDS) ?: return false
            kernargBufs[i] = GfxPool.buffer("n64.kernarg.$i", KERNARG_WORDS) ?: return false
            tmemBufs[i] = GfxPool.buffer("n64.tmem.$i", TMEM_WORDS * TMEM_SLOTS) ?: return false
            rowStartBufs[i] = GfxPool.buffer("n64.rowstart.$i", SPANS + 1) ?: return false
            rowSpanBufs[i] = GfxPool.buffer("n64.rowspans.$i", SPANS) ?: return false
        }
        val ident = GfxPool.buffer("n64.identity", SPANS + 1) ?: return false
        val zc = GfxPool.buffer("n64.zcom", zcom.size) ?: return false
        failReason = 5
        val zd = GfxPool.buffer("n64.zdec", zdec.size) ?: return false
        val dz = GfxPool.buffer("n64.deltaz", deltaz.size) ?: return false
        val tcd = GfxPool.buffer("n64.tcdiv", tcdiv.size) ?: return false
        failReason = 0
        zc.write(0, zcom, 0, zcom.size)
        zd.write(0, zdec, 0, zdec.size)
        dz.write(0, deltaz, 0, deltaz.size)
        tcd.write(0, tcdiv, 0, tcdiv.size)
        ident.write(0, IntArray(SPANS + 1) { it }, 0, SPANS + 1)
        identityBuf = ident
        kernel = krn
        rdramBuf = rd
        hiddenBuf = hd
        zcomBuf = zc
        zdecBuf = zd
        deltazBuf = dz
        tcdivBuf = tcd
        surface = ResidentSurface(rd, hd, rdram, hidden, RDRAM_MASK, invalidate)
        return true
    }

    private fun binChains(spanCount: Int, rowLo: Int, rowHi: Int): Int {
        val rows = rowHi - rowLo + 1
        if (rows <= 0 || rows > MAX_ROWS) return -1

        for (i in 0 until spanCount) {
            val base = i * SPAN_STRIDE
            val a = spans[base + SPAN_LX]
            val b = spans[base + SPAN_RX]
            spanLo[i] = if (a < b) a else b
            spanHi[i] = if (a < b) b else a
        }

        rowFill.fill(0, 0, rows)
        for (i in 0 until spanCount) rowFill[spans[i * SPAN_STRIDE + SPAN_ROW] - rowLo]++
        var sum = 0
        for (row in 0 until rows) {
            rowCursor[row] = sum
            sum += rowFill[row]
        }
        for (i in 0 until spanCount) {
            val row = spans[i * SPAN_STRIDE + SPAN_ROW] - rowLo
            rowSpans[rowCursor[row]] = i
            rowCursor[row] = rowCursor[row] + 1
        }

        var groups = 0
        var out = 0
        var start = 0
        for (row in 0 until rows) {
            val end = rowCursor[row]
            val n = end - start
            if (n <= 0) continue

            if (n == 1 || n > MAX_ROW_SPANS) {
                chainStart[groups] = out
                groups++
                for (k in start until end) {
                    chainSpans[out] = rowSpans[k]
                    out++
                }
                start = end
                continue
            }

            for (k in 0 until n) order[k] = rowSpans[start + k]
            for (k in 1 until n) {
                val v = order[k]
                val key = spanLo[v]
                var j = k - 1
                while (j >= 0 && spanLo[order[j]] > key) {
                    order[j + 1] = order[j]
                    j--
                }
                order[j + 1] = v
            }

            var comps = 0
            var maxHi = 0
            for (k in 0 until n) {
                val s = order[k]
                if (k == 0 || spanLo[s] > maxHi) {
                    comps++
                    maxHi = spanHi[s]
                } else if (spanHi[s] > maxHi) {
                    maxHi = spanHi[s]
                }
                spanChain[s] = comps - 1
            }

            for (c in 0 until comps) compFill[c] = 0
            for (k in start until end) compFill[spanChain[rowSpans[k]]]++
            var cs = out
            for (c in 0 until comps) {
                chainStart[groups] = cs
                groups++
                compCursor[c] = cs
                cs += compFill[c]
            }
            for (k in start until end) {
                val s = rowSpans[k]
                val c = spanChain[s]
                chainSpans[compCursor[c]] = s
                compCursor[c] = compCursor[c] + 1
            }
            out = cs
            start = end
        }
        chainStart[groups] = out
        return groups
    }

    private fun dispatchSlot(uniform: IntArray, spanCount: Int, groups: Int, identity: Boolean, tmemCount: Int): Boolean {
        val krn = kernel ?: return false
        if (slot == 0) Gpu.backend?.sync()
        val s = slot
        val sp = spansBufs[s] ?: return false
        val un = uniformBufs[s] ?: return false
        val ka = kernargBufs[s] ?: return false
        val tm = tmemBufs[s] ?: return false
        val id = identityBuf ?: return false

        un.write(0, uniform, 0, UNIFORM_WORDS)
        sp.write(0, spans, 0, spanCount * SPAN_STRIDE)

        val rs: GpuBuffer
        val rl: GpuBuffer
        if (identity) {
            rs = id
            rl = id
        } else {
            rs = rowStartBufs[s] ?: return false
            rl = rowSpanBufs[s] ?: return false
            rs.write(0, chainStart, 0, groups + 1)
            rl.write(0, chainSpans, 0, spanCount)
        }

        if (tmemCount > 0) tm.write(0, tmemPacked, 0, tmemCount * TMEM_WORDS)
        writeKernarg(ka, sp, un, tm, rs, rl, groups)

        val ok = Gpu.backend?.dispatch(krn, ka, groups, 64) ?: false
        if (!ok) {
            disabled = true
            return false
        }
        slot++
        if (slot == SLOTS) slot = 0
        dispatched++
        spansSubmitted += spanCount.toLong()
        groupsSubmitted += groups.toLong()
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

        if (!beginBatch(uniform, tmemGen, last - first + 1)) return -1

        var pixels = 0L
        for (i in first..last) {
            if (!valid[i]) continue
            val xstart = lx[i]
            val xendsc = rx[i]
            val length = if (flip) xstart - xendsc else xendsc - xstart
            if (length < 0) continue

            val base = accCount * SPAN_STRIDE
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
            writePrimitive(base, uniform)
            accCount++

            if (i <= accLastRow) accRowsAscending = false
            accLastRow = i
            if (i < accRowLo) accRowLo = i
            if (i > accRowHi) accRowHi = i

            val lo = maxOf(xendsc, scissorXh)
            val hi = minOf(maxOf(xstart, xendsc), scissorXl - 1)
            if (hi >= lo) pixels += (hi - lo + 1).toLong()
        }
        accPixels += pixels
        return pixels
    }

    private fun beginBatch(uniform: IntArray, tmemGen: Int, maxAdd: Int): Boolean {
        val needsTmem = uniform[U_TEXTURE] != 0 || uniform[U_COPY] != 0
        if (accActive) {
            val capBreak = accCount + maxAdd > SPANS
            val uniformBreak = !uniformsEqual(uniform)
            var slot = accTmemSlot
            var tmemBreak = false
            if (!capBreak && !uniformBreak && needsTmem && accTmemGen != tmemGen) {
                slot = tmemSlotFor(tmemGen)
                if (slot < 0) tmemBreak = true
            }
            if (capBreak || uniformBreak || tmemBreak) {
                if (tmemBreak) breakTmem++ else if (capBreak) breakCapacity++ else breakUniform++
                flushBatch()
            } else {
                accTmemSlot = slot
            }
        }
        if (!accActive) {
            uniform.copyInto(accUniform, 0, 0, UNIFORM_WORDS)
            accCi = uniform[U_COLORIMAGE]
            accZi = uniform[U_ZIMAGE]
            accW = uniform[U_COLORWIDTH]
            accCs = uniform[U_COLORSIZE]
            accRowLo = Int.MAX_VALUE
            accRowHi = Int.MIN_VALUE
            accColorAux = uniform[U_FILL] != 0 && uniform[U_COLORSIZE] == 2
            accRowsAscending = true
            accLastRow = Int.MIN_VALUE
            accCount = 0
            accPixels = 0
            accActive = true
            accTmemCount = 0
            accTmemSlot = if (needsTmem) tmemSlotFor(tmemGen) else 0
        }
        accTmemGen = tmemGen
        return true
    }

    private fun uniformsEqual(u: IntArray): Boolean {
        for (i in 0 until UNIFORM_WORDS) if (SHARED[i] && accUniform[i] != u[i]) return false
        return true
    }

    private fun writePrimitive(base: Int, u: IntArray) {
        spans[base + SPAN_TMEM] = accTmemSlot
        spans[base + SPAN_FLIP] = u[U_FLIP]
        spans[base + SPAN_DR] = u[U_SPANS_DR]
        spans[base + SPAN_DG] = u[U_SPANS_DG]
        spans[base + SPAN_DB] = u[U_SPANS_DB]
        spans[base + SPAN_DA] = u[U_SPANS_DA]
        spans[base + SPAN_DZ] = u[U_SPANS_DZ]
        spans[base + SPAN_DS] = u[U_SPANS_DS]
        spans[base + SPAN_DT] = u[U_SPANS_DT]
        spans[base + SPAN_DW] = u[U_SPANS_DW]
        spans[base + SPAN_DZPIX] = u[U_SPANDZPIX]
        spans[base + SPAN_DZPIXENC] = u[U_SPANDZPIXENC]
        spans[base + SPAN_TEXRECT] = u[U_TEXRECT]
        spans[base + SPAN_TR_X0] = u[U_TR_X0]
        spans[base + SPAN_TR_Y0] = u[U_TR_Y0]
        spans[base + SPAN_TR_S] = u[U_TR_S]
        spans[base + SPAN_TR_T] = u[U_TR_T]
        spans[base + SPAN_TR_DSDX] = u[U_TR_DSDX]
        spans[base + SPAN_TR_DTDY] = u[U_TR_DTDY]
        spans[base + SPAN_TR_SHIFT] = u[U_TR_SHIFT]
        spans[base + SPAN_TR_FLIP] = u[U_TR_FLIP]
    }

    fun flushBatch() {
        if (!accActive) return
        accActive = false
        if (accCount == 0 || accRowHi < accRowLo) return
        val s = surface ?: return
        s.prepare(accCi, accZi, accW, accCs, accZi != 0, accColorAux, accRowLo, accRowHi)
        val groups = if (accRowsAscending) accCount else binChains(accCount, accRowLo, accRowHi)
        if (groups < 0) {
            disabled = true
            return
        }
        val ok = groups == 0 || dispatchSlot(accUniform, accCount, groups, groups == accCount, accTmemCount)
        if (ok) {
            s.markDirty(accRowLo, accRowHi)
            offloadedPixels += accPixels
        }
    }

    fun renderFill(uniform: IntArray, top: Int, bottom: Int, left: Int, right: Int, tmemGen: Int): Long =
        renderRows(uniform, top, bottom, left, right, tmemGen)

    fun renderTexrect(uniform: IntArray, top: Int, bottom: Int, left: Int, right: Int, tmemGen: Int): Long =
        renderRows(uniform, top, bottom, left, right, tmemGen)

    private fun renderRows(uniform: IntArray, top: Int, bottom: Int, left: Int, right: Int, tmemGen: Int): Long {
        if (!ensure()) return -1
        if (bottom < top || right < left) return 0

        if (!beginBatch(uniform, tmemGen, bottom - top + 1)) return -1

        for (y in top..bottom) {
            val base = accCount * SPAN_STRIDE
            spans[base + SPAN_ROW] = y
            spans[base + SPAN_LX] = left
            spans[base + SPAN_RX] = right
            spans[base + SPAN_UNSCRX] = right
            writePrimitive(base, uniform)
            accCount++
            if (y <= accLastRow) accRowsAscending = false
            accLastRow = y
            if (y < accRowLo) accRowLo = y
            if (y > accRowHi) accRowHi = y
        }

        val pixels = (right - left + 1).toLong() * (bottom - top + 1).toLong()
        accPixels += pixels
        return pixels
    }

    fun flush() {
        if (accActive && accCount > 0) breakForced++
        flushBatch()
        surface?.flush()
    }

    fun flushIfTouches(byteLo: Int, byteHi: Int) {
        val s = surface ?: return
        if (!batchTouches(byteLo, byteHi) && !s.touches(byteLo, byteHi)) return
        if (accActive && accCount > 0) breakForced++
        flushBatch()
        s.flush()
    }

    private fun batchTouches(byteLo: Int, byteHi: Int): Boolean {
        if (!accActive || accCount == 0 || accRowHi < accRowLo) return false
        val bpp = if (accCs == 3) 4 else 2
        val cLo = accCi + accRowLo * accW * bpp
        val cHi = accCi + (accRowHi + 1) * accW * bpp
        if (byteLo < cHi && byteHi > cLo) return true
        if (accZi != 0) {
            val zLo = accZi + accRowLo * accW * 2
            val zHi = accZi + (accRowHi + 1) * accW * 2
            if (byteLo < zHi && byteHi > zLo) return true
        }
        return false
    }

    private fun writeKernarg(
        ka: GpuBuffer,
        sp: GpuBuffer,
        un: GpuBuffer,
        tm: GpuBuffer,
        rs: GpuBuffer,
        rl: GpuBuffer,
        groups: Int,
    ) {
        putPtr(ka, 0, rdramBuf!!)
        putPtr(ka, 2, hiddenBuf!!)
        putPtr(ka, 4, sp)
        putPtr(ka, 6, un)
        putPtr(ka, 8, zcomBuf!!)
        putPtr(ka, 10, zdecBuf!!)
        putPtr(ka, 12, deltazBuf!!)
        putPtr(ka, 14, tm)
        putPtr(ka, 16, tcdivBuf!!)
        putPtr(ka, 18, rs)
        putPtr(ka, 20, rl)
        ka.writeWord(22, groups)
    }

    private fun putPtr(ka: GpuBuffer, word: Int, buf: GpuBuffer) {
        ka.writeWord(word, (buf.gpuAddress and 0xFFFFFFFFL).toInt())
        ka.writeWord(word + 1, (buf.gpuAddress ushr 32).toInt())
    }

    private fun captureTmem(slot: Int) {
        val base = slot * TMEM_WORDS
        for (word in 0 until TMEM_WORDS) {
            val b = word shl 2
            tmemPacked[base + word] = (tmem[b].toInt() and 0xFF) or
                ((tmem[b + 1].toInt() and 0xFF) shl 8) or
                ((tmem[b + 2].toInt() and 0xFF) shl 16) or
                ((tmem[b + 3].toInt() and 0xFF) shl 24)
        }
    }

    private fun tmemSlotFor(tmemGen: Int): Int {
        for (i in 0 until accTmemCount) if (accTmemGens[i] == tmemGen) return i
        if (accTmemCount >= TMEM_SLOTS) return -1
        val slot = accTmemCount
        accTmemGens[slot] = tmemGen
        accTmemCount++
        captureTmem(slot)
        return slot
    }

    companion object {
        const val SPAN_STRIDE = 45
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
        const val SPAN_FLIP = 24
        const val SPAN_DR = 25
        const val SPAN_DG = 26
        const val SPAN_DB = 27
        const val SPAN_DA = 28
        const val SPAN_DZ = 29
        const val SPAN_DS = 30
        const val SPAN_DT = 31
        const val SPAN_DW = 32
        const val SPAN_DZPIX = 33
        const val SPAN_DZPIXENC = 34
        const val SPAN_TEXRECT = 35
        const val SPAN_TR_X0 = 36
        const val SPAN_TR_Y0 = 37
        const val SPAN_TR_S = 38
        const val SPAN_TR_T = 39
        const val SPAN_TR_DSDX = 40
        const val SPAN_TR_DTDY = 41
        const val SPAN_TR_SHIFT = 42
        const val SPAN_TR_FLIP = 43
        const val SPAN_TMEM = 44

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
        const val U_CYCLE2 = 88
        const val U_TEXRECT = 89
        const val U_COPY = 90
        const val U_TR_X0 = 91
        const val U_TR_Y0 = 92
        const val U_TR_S = 93
        const val U_TR_T = 94
        const val U_TR_DSDX = 95
        const val U_TR_DTDY = 96
        const val U_TR_SHIFT = 97
        const val U_TR_FLIP = 98
        const val U_FILL = 99
        const val U_FILLCOLOR = 100
        const val U_RECTSHADE = 101
        const val UNIFORM_WORDS = 102

        private val PER_PRIMITIVE = intArrayOf(
            U_FLIP, U_SPANS_DR, U_SPANS_DG, U_SPANS_DB, U_SPANS_DA, U_SPANS_DZ,
            U_SPANS_DS, U_SPANS_DT, U_SPANS_DW, U_SPANDZPIX, U_SPANDZPIXENC,
            U_TEXRECT, U_TR_X0, U_TR_Y0, U_TR_S, U_TR_T, U_TR_DSDX, U_TR_DTDY,
            U_TR_SHIFT, U_TR_FLIP,
        )

        private val SHARED = BooleanArray(UNIFORM_WORDS) { true }.also {
            for (i in PER_PRIMITIVE) it[i] = false
        }

        private const val SPANS = 4096
        private const val TMEM_WORDS = 1024
        private const val TMEM_SLOTS = 16
        private const val KERNARG_WORDS = 24
        private const val SLOTS = 8
        private const val MAX_ROWS = 4096
        private const val MAX_ROW_SPANS = 128
    }
}
