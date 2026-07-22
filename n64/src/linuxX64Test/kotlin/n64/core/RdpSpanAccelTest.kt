package n64.core

import kapi.gfx.GfxPool
import kapi.gpu.Gpu
import kapi.gpu.GpuBackend
import kapi.gpu.GpuBuffer
import kapi.gpu.GpuKernel
import kotlin.test.Test
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.pin
import kotlinx.cinterop.reinterpret
import rdpshader.rdpspan_pixel


private val NORM_POINT = intArrayOf(
    0x4000, 0x3F04, 0x3E10, 0x3D22, 0x3C3C, 0x3B5D, 0x3A83, 0x39B1,
    0x38E4, 0x381C, 0x375A, 0x369D, 0x35E5, 0x3532, 0x3483, 0x33D9,
    0x3333, 0x3291, 0x31F4, 0x3159, 0x30C3, 0x3030, 0x2FA1, 0x2F15,
    0x2E8C, 0x2E06, 0x2D83, 0x2D03, 0x2C86, 0x2C0B, 0x2B93, 0x2B1E,
    0x2AAB, 0x2A3A, 0x29CC, 0x2960, 0x28F6, 0x288E, 0x2828, 0x27C4,
    0x2762, 0x2702, 0x26A4, 0x2648, 0x25ED, 0x2594, 0x253D, 0x24E7,
    0x2492, 0x243F, 0x23EE, 0x239E, 0x234F, 0x2302, 0x22B6, 0x226C,
    0x2222, 0x21DA, 0x2193, 0x214D, 0x2108, 0x20C5, 0x2082, 0x2041,
)
private val NORM_SLOPE = intArrayOf(
    0xF03, 0xF0B, 0xF11, 0xF19, 0xF20, 0xF25, 0xF2D, 0xF32,
    0xF37, 0xF3D, 0xF42, 0xF47, 0xF4C, 0xF50, 0xF55, 0xF59,
    0xF5D, 0xF62, 0xF64, 0xF69, 0xF6C, 0xF70, 0xF73, 0xF76,
    0xF79, 0xF7C, 0xF7F, 0xF82, 0xF84, 0xF87, 0xF8A, 0xF8C,
    0xF8E, 0xF91, 0xF93, 0xF95, 0xF97, 0xF99, 0xF9B, 0xF9D,
    0xF9F, 0xFA1, 0xFA3, 0xFA4, 0xFA6, 0xFA8, 0xFA9, 0xFAA,
    0xFAC, 0xFAE, 0xFAF, 0xFB0, 0xFB2, 0xFB3, 0xFB5, 0xFB5,
    0xFB7, 0xFB8, 0xFB9, 0xFBA, 0xFBC, 0xFBC, 0xFBE, 0xFBE,
)

private object Luts {
    val zcom = IntArray(0x40000)
    val zdec = IntArray(0x4000)
    val deltaz = IntArray(0x10000)
    val tcdiv = IntArray(0x8000)

    init {
        for (z in 0 until 0x40000) {
            val exponent = (z shr 11) and 0x7F
            zcom[z] = when {
                exponent < 0x40 -> (z shr 4) and 0x1FFC
                exponent < 0x60 -> ((z shr 3) and 0x1FFC) or 0x2000
                exponent < 0x70 -> ((z shr 2) and 0x1FFC) or 0x4000
                exponent < 0x78 -> ((z shr 1) and 0x1FFC) or 0x6000
                exponent < 0x7C -> (z and 0x1FFC) or 0x8000
                exponent < 0x7E -> ((z shl 1) and 0x1FFC) or 0xA000
                exponent < 0x7F -> ((z shl 2) and 0x1FFC) or 0xC000
                else -> ((z shl 2) and 0x1FFC) or 0xE000
            }
        }
        val decShift = intArrayOf(6, 5, 4, 3, 2, 1, 0, 0)
        val decAdd = intArrayOf(0x00000, 0x20000, 0x30000, 0x38000, 0x3C000, 0x3E000, 0x3F000, 0x3F800)
        for (i in 0 until 0x4000) {
            val exponent = (i shr 11) and 7
            val mantissa = i and 0x7FF
            zdec[i] = ((mantissa shl decShift[exponent]) + decAdd[exponent]) and 0x3FFFF
        }
        for (i in 1 until 0x10000) {
            for (k in 15 downTo 0) {
                if (i and (1 shl k) != 0) { deltaz[i] = 1 shl k; break }
            }
        }
        for (i in 0 until 0x8000) {
            var k = 1
            while (k <= 14 && (i shl k) and 0x8000 == 0) k++
            val shift = k - 1
            var normout = (i shl shift) and 0x3FFF
            val wnorm = (normout and 0xFF) shl 2
            normout = normout shr 8
            val point = NORM_POINT[normout]
            val slope = (NORM_SLOPE[normout] or 0x3FF.inv()) + 1
            val tluRcp = (((slope * wnorm) shr 10) + point) and 0x7FFF
            tcdiv[i] = shift or (tluRcp shl 4)
        }
    }
}



private fun primInto(rec: IntArray, u: IntArray) {
    rec[RdpSpanAccel.SPAN_FLIP] = u[RdpSpanAccel.U_FLIP]
    rec[RdpSpanAccel.SPAN_DR] = u[RdpSpanAccel.U_SPANS_DR]
    rec[RdpSpanAccel.SPAN_DG] = u[RdpSpanAccel.U_SPANS_DG]
    rec[RdpSpanAccel.SPAN_DB] = u[RdpSpanAccel.U_SPANS_DB]
    rec[RdpSpanAccel.SPAN_DA] = u[RdpSpanAccel.U_SPANS_DA]
    rec[RdpSpanAccel.SPAN_DZ] = u[RdpSpanAccel.U_SPANS_DZ]
    rec[RdpSpanAccel.SPAN_DS] = u[RdpSpanAccel.U_SPANS_DS]
    rec[RdpSpanAccel.SPAN_DT] = u[RdpSpanAccel.U_SPANS_DT]
    rec[RdpSpanAccel.SPAN_DW] = u[RdpSpanAccel.U_SPANS_DW]
    rec[RdpSpanAccel.SPAN_DZPIX] = u[RdpSpanAccel.U_SPANDZPIX]
    rec[RdpSpanAccel.SPAN_DZPIXENC] = u[RdpSpanAccel.U_SPANDZPIXENC]
    rec[RdpSpanAccel.SPAN_TEXRECT] = u[RdpSpanAccel.U_TEXRECT]
    rec[RdpSpanAccel.SPAN_TR_X0] = u[RdpSpanAccel.U_TR_X0]
    rec[RdpSpanAccel.SPAN_TR_Y0] = u[RdpSpanAccel.U_TR_Y0]
    rec[RdpSpanAccel.SPAN_TR_S] = u[RdpSpanAccel.U_TR_S]
    rec[RdpSpanAccel.SPAN_TR_T] = u[RdpSpanAccel.U_TR_T]
    rec[RdpSpanAccel.SPAN_TR_DSDX] = u[RdpSpanAccel.U_TR_DSDX]
    rec[RdpSpanAccel.SPAN_TR_DTDY] = u[RdpSpanAccel.U_TR_DTDY]
    rec[RdpSpanAccel.SPAN_TR_SHIFT] = u[RdpSpanAccel.U_TR_SHIFT]
    rec[RdpSpanAccel.SPAN_TR_FLIP] = u[RdpSpanAccel.U_TR_FLIP]
}

@OptIn(ExperimentalForeignApi::class)
private class NativeSpan(scene: SpanScene) {
    val rec = IntArray(RdpSpanAccel.SPAN_STRIDE)
    val uniforms = IntArray(RdpSpanAccel.UNIFORM_WORDS)
    val tmem = ByteArray(4096)

    private val pRd = scene.rdram.pin()
    private val pHd = scene.hidden.pin()
    private val pSp = rec.pin()
    private val pUn = uniforms.pin()
    private val pTm = tmem.pin()
    private val pZc = Luts.zcom.pin()
    private val pZd = Luts.zdec.pin()
    private val pDz = Luts.deltaz.pin()
    private val pTc = Luts.tcdiv.pin()

    fun span() {
        val a = rec[RdpSpanAccel.SPAN_LX]
        val b = rec[RdpSpanAccel.SPAN_RX]
        val lo = if (a < b) a else b
        val hi = if (a < b) b else a
        for (x in lo..hi) {
            rdpspan_pixel(
                pSp.addressOf(0).reinterpret(),
                pUn.addressOf(0).reinterpret(),
                x,
                pRd.addressOf(0).reinterpret(),
                pHd.addressOf(0).reinterpret(),
                pZc.addressOf(0).reinterpret(),
                pZd.addressOf(0).reinterpret(),
                pDz.addressOf(0).reinterpret(),
                pTm.addressOf(0).reinterpret(),
                pTc.addressOf(0).reinterpret(),
            )
        }
    }

    fun close() {
        pRd.unpin(); pHd.unpin(); pSp.unpin(); pUn.unpin(); pTm.unpin()
        pZc.unpin(); pZd.unpin(); pDz.unpin(); pTc.unpin()
    }
}

private class SpanScene {
    val rdram = IntArray(RDRAM_SIZE / 4)
    val hidden = ByteArray(RDRAM_SIZE / 2)
}

private class TriDraw(
    val first: Int, val last: Int, val u: IntArray, val tmem: ByteArray, val tmemGen: Int,
    val valid: BooleanArray, val lx: IntArray, val rx: IntArray, val unscrx: IntArray,
    val r: IntArray, val g: IntArray, val b: IntArray, val a: IntArray, val z: IntArray,
    val s: IntArray, val t: IntArray, val w: IntArray,
    val minorX: IntArray, val majorX: IntArray, val invalY: IntArray,
)

private class FillDraw(
    val u: IntArray,
    val top: Int, val bottom: Int, val left: Int, val right: Int,
)

private class RectDraw(
    val u: IntArray, val tmem: ByteArray, val tmemGen: Int,
    val top: Int, val bottom: Int, val left: Int, val right: Int,
)

class RdpSpanAccelTest {
    private val width = 320
    private val image = 0x100000
    private val zImage = 0x300000

    private fun rng(seed: Long): () -> Int {
        var state = seed
        return {
            state = state * 6364136223846793005L + 1442695040888963407L
            ((state ushr 33).toInt()) and 0x7FFFFFFF
        }
    }

    private fun baseUniform(next: () -> Int, flip: Boolean, colorSize: Int, depth: Boolean): IntArray {
        val u = IntArray(RdpSpanAccel.UNIFORM_WORDS)
        u[RdpSpanAccel.U_FLIP] = if (flip) 1 else 0
        u[RdpSpanAccel.U_SCISSOR_XH] = 0
        u[RdpSpanAccel.U_SCISSOR_XL] = width
        u[RdpSpanAccel.U_COLORSIZE] = colorSize
        u[RdpSpanAccel.U_COLORIMAGE] = image
        u[RdpSpanAccel.U_COLORWIDTH] = width
        u[RdpSpanAccel.U_SPANS_DR] = (next() % 0x40000) - 0x20000
        u[RdpSpanAccel.U_SPANS_DG] = (next() % 0x40000) - 0x20000
        u[RdpSpanAccel.U_SPANS_DB] = (next() % 0x40000) - 0x20000
        u[RdpSpanAccel.U_SPANS_DA] = (next() % 0x40000) - 0x20000
        u[RdpSpanAccel.U_SPANS_DZ] = (next() % 0x400000) - 0x200000
        u[RdpSpanAccel.U_SPANS_DS] = (next() % 0x40000) - 0x20000
        u[RdpSpanAccel.U_SPANS_DT] = (next() % 0x40000) - 0x20000
        u[RdpSpanAccel.U_SPANS_DW] = (next() % 0x40000) - 0x20000
        u[RdpSpanAccel.U_PRIM] = next() or (next() shl 1)
        u[RdpSpanAccel.U_ENV] = next() or (next() shl 1)
        u[RdpSpanAccel.U_BLEND] = next() or (next() shl 1)
        u[RdpSpanAccel.U_FOG] = next() or (next() shl 1)
        u[RdpSpanAccel.U_PRIMLOD] = next() and 0xFF
        u[RdpSpanAccel.U_ZIMAGE] = zImage
        u[RdpSpanAccel.U_ZMODE] = next() % 4
        u[RdpSpanAccel.U_ZCOMPARE] = next() and 1
        u[RdpSpanAccel.U_ZUPDATE] = next() and 1
        u[RdpSpanAccel.U_ZSOURCE] = if (next() % 4 == 0) 1 else 0
        u[RdpSpanAccel.U_ALPHACOMPARE] = next() and 1
        u[RdpSpanAccel.U_CTA] = next() and 1
        u[RdpSpanAccel.U_ACS] = next() and 1
        u[RdpSpanAccel.U_FORCEBLEND] = next() and 1
        u[RdpSpanAccel.U_PRIMDEPTH] = next() % 0x40000
        u[RdpSpanAccel.U_PRIMDZ] = next() % 0x10000
        u[RdpSpanAccel.U_PRIMDZENC] = next() % 16
        u[RdpSpanAccel.U_SPANDZPIX] = next() % 0x10000
        u[RdpSpanAccel.U_SPANDZPIXENC] = next() % 16
        u[RdpSpanAccel.U_USEDEPTH] = if (depth) 1 else 0
        u[RdpSpanAccel.U_SHADE] = next() and 1
        u[RdpSpanAccel.U_TEXTURE] = if (next() % 3 != 0) 1 else 0
        u[RdpSpanAccel.U_PERSP] = next() and 1
        u[RdpSpanAccel.U_SAMPLETYPE] = next() and 1
        u[RdpSpanAccel.U_TLUTEN] = next() % 3 / 2
        u[RdpSpanAccel.U_TLUTTYPE] = next() and 1
        u[RdpSpanAccel.U_MIDTEXEL] = next() and 1
        u[RdpSpanAccel.U_CYCLE2] = next() and 1
        u[RdpSpanAccel.T_FORMAT] = next() % 5
        u[RdpSpanAccel.T_SIZE] = next() % 4
        u[RdpSpanAccel.T_LINE] = next() % 32
        u[RdpSpanAccel.T_TMEM] = next() % 256
        u[RdpSpanAccel.T_PALETTE] = next() % 16
        u[RdpSpanAccel.T_CLAMPS] = next() and 1
        u[RdpSpanAccel.T_MIRRORS] = next() and 1
        u[RdpSpanAccel.T_MASKS] = next() % 11
        u[RdpSpanAccel.T_SHIFTS] = next() % 16
        u[RdpSpanAccel.T_CLAMPT] = next() and 1
        u[RdpSpanAccel.T_MIRRORT] = next() and 1
        u[RdpSpanAccel.T_MASKT] = next() % 11
        u[RdpSpanAccel.T_SHIFTT] = next() % 16
        val sl = next() % 256; val tl = next() % 256
        val sh = sl + next() % 256; val th = tl + next() % 256
        u[RdpSpanAccel.T_SL] = sl
        u[RdpSpanAccel.T_TL] = tl
        u[RdpSpanAccel.T_SH] = sh
        u[RdpSpanAccel.T_TH] = th
        u[RdpSpanAccel.T_CLAMPDIFFS] = ((sh shr 2) - (sl shr 2)) and 0x3FF
        u[RdpSpanAccel.T_CLAMPDIFFT] = ((th shr 2) - (tl shr 2)) and 0x3FF
        u[RdpSpanAccel.T_CLAMPENS] = if (u[RdpSpanAccel.T_CLAMPS] != 0 || u[RdpSpanAccel.T_MASKS] == 0) 1 else 0
        u[RdpSpanAccel.T_CLAMPENT] = if (u[RdpSpanAccel.T_CLAMPT] != 0 || u[RdpSpanAccel.T_MASKT] == 0) 1 else 0
        u[RdpSpanAccel.T_MASKSCLAMPED] = if (u[RdpSpanAccel.T_MASKS] <= 10) u[RdpSpanAccel.T_MASKS] else 10
        u[RdpSpanAccel.T_MASKTCLAMPED] = if (u[RdpSpanAccel.T_MASKT] <= 10) u[RdpSpanAccel.T_MASKT] else 10
        val configs = arrayOf(
            intArrayOf(0, 0, 0, 4), intArrayOf(4, 0, 3, 0), intArrayOf(3, 4, 5, 0),
            intArrayOf(1, 0, 4, 0), intArrayOf(1, 3, 0, 0),
        )
        val cfg = configs[next() % configs.size]
        val c = RdpSpanAccel.U_CRGB
        for (k in 0 until 8) u[c + k] = if (k and 1 == 0) cfg[k / 2] else 0
        val al = RdpSpanAccel.U_CALPHA
        val acfg = intArrayOf(4, 0, 1, 0)
        for (k in 0 until 8) u[al + k] = if (k and 1 == 0) acfg[k / 2] else 0
        val bl = RdpSpanAccel.U_BLENDSEL
        for (k in 0 until 8) u[bl + k] = next() % 4
        return u
    }

    private fun makeTri(next: () -> Int, colorSize: Int, depth: Boolean, pool: Array<ByteArray>): TriDraw {
        val flip = next() and 1 == 0
        val u = baseUniform(next, flip, colorSize, depth)
        val tmemGen = next() % pool.size
        return triGeometry(next, u, pool[tmemGen], tmemGen)
    }

    private fun triGeometry(next: () -> Int, u: IntArray, tmem: ByteArray, tmemGen: Int): TriDraw {
        val flip = u[RdpSpanAccel.U_FLIP] != 0
        val first = next() % 40
        val rows = 4 + next() % 24
        val last = first + rows

        val size = 1024
        val valid = BooleanArray(size)
        val lx = IntArray(size); val rx = IntArray(size); val unscrx = IntArray(size)
        val r = IntArray(size); val gg = IntArray(size); val bb = IntArray(size); val aa = IntArray(size)
        val z = IntArray(size); val ss = IntArray(size); val tt = IntArray(size); val ww = IntArray(size)
        val minorX = IntArray(size * 4); val majorX = IntArray(size * 4); val invalY = IntArray(size * 4)
        for (i in first..last) {
            valid[i] = next() % 5 != 0
            val left = next() % (width - 20)
            val len = 1 + next() % 40
            val right = minOf(left + len, width - 1)
            if (flip) { lx[i] = right; rx[i] = left } else { lx[i] = left; rx[i] = right }
            unscrx[i] = rx[i]
            r[i] = (next() % 0x800) shl 16
            gg[i] = (next() % 0x800) shl 16
            bb[i] = (next() % 0x800) shl 16
            aa[i] = (next() % 0x800) shl 16
            z[i] = (next() % 0x4000000) shl 5
            ss[i] = next() or (next() shl 1)
            tt[i] = next() or (next() shl 1)
            ww[i] = (1 + next() % 0x7FFF) shl 16
            for (k in 0 until 4) {
                minorX[i * 4 + k] = (left + next() % (right - left + 1)) shl 3 or (next() % 8)
                majorX[i * 4 + k] = (left + next() % (right - left + 1)) shl 3 or (next() % 8)
                invalY[i * 4 + k] = if (next() % 6 == 0) 1 else 0
            }
        }
        return TriDraw(first, last, u, tmem, tmemGen, valid, lx, rx, unscrx, r, gg, bb, aa, z, ss, tt, ww, minorX, majorX, invalY)
    }

    private fun makeRect(next: () -> Int, colorSize: Int, pool: Array<ByteArray>): RectDraw {
        val copy = next() and 1 == 0
        val u = baseUniform(next, false, colorSize, false)
        u[RdpSpanAccel.U_FLIP] = 0
        u[RdpSpanAccel.U_SHADE] = 0
        u[RdpSpanAccel.U_TEXTURE] = 1
        u[RdpSpanAccel.U_USEDEPTH] = 0
        u[RdpSpanAccel.U_TEXRECT] = 1
        u[RdpSpanAccel.U_RECTSHADE] = -1
        u[RdpSpanAccel.U_COPY] = if (copy) 1 else 0
        if (!copy && next() % 4 == 0) {
            u[RdpSpanAccel.U_TEXTURE] = 0
            u[RdpSpanAccel.U_RECTSHADE] = 0
        }
        u[RdpSpanAccel.U_CYCLE2] = if (!copy) next() and 1 else 0

        val left = next() % (width - 40)
        val w = 4 + next() % 60
        val right = minOf(left + w, width - 1)
        val top = next() % 200
        val h = 4 + next() % 40
        val bottom = top + h
        u[RdpSpanAccel.U_TR_X0] = left - next() % 4
        u[RdpSpanAccel.U_TR_Y0] = top - next() % 4
        u[RdpSpanAccel.U_TR_S] = next() % 0x8000
        u[RdpSpanAccel.U_TR_T] = next() % 0x8000
        u[RdpSpanAccel.U_TR_DSDX] = (next() % 0x800) - 0x400
        u[RdpSpanAccel.U_TR_DTDY] = (next() % 0x800) - 0x400
        u[RdpSpanAccel.U_TR_SHIFT] = if (copy) 0 else 5
        u[RdpSpanAccel.U_TR_FLIP] = next() and 1

        val tmemGen = next() % pool.size
        return RectDraw(u, pool[tmemGen], tmemGen, top, bottom, left, right)
    }

    private fun makeFill(next: () -> Int, colorSize: Int): FillDraw {
        val u = IntArray(RdpSpanAccel.UNIFORM_WORDS)
        u[RdpSpanAccel.U_FILL] = 1
        u[RdpSpanAccel.U_FILLCOLOR] = next() or (next() shl 1)
        u[RdpSpanAccel.U_COLORSIZE] = colorSize
        u[RdpSpanAccel.U_COLORIMAGE] = image
        u[RdpSpanAccel.U_COLORWIDTH] = width
        u[RdpSpanAccel.U_SCISSOR_XH] = 0
        u[RdpSpanAccel.U_SCISSOR_XL] = width
        val left = next() % (width - 40)
        val right = minOf(left + 4 + next() % 60, width - 1)
        val top = next() % 200
        return FillDraw(u, top, top + 4 + next() % 40, left, right)
    }

    private fun makeScene(seed: Long): List<Any> {
        val next = rng(seed)
        val colorSize = if (next() and 1 == 0) 2 else 3
        val depth = next() % 3 != 0
        val pool = Array(3) { ByteArray(4096) { next().toByte() } }
        val groups = 1 + next() % 6
        val draws = ArrayList<Any>()
        repeat(groups) {
            if (next() % 5 == 0) {
                draws.add(makeFill(next, colorSize))
            } else if (next() % 3 == 0) {
                draws.add(makeRect(next, colorSize, pool))
            } else {
                val d = makeTri(next, colorSize, depth, pool)
                draws.add(d)
                repeat(next() % 4) {
                    val g = next() % pool.size
                    draws.add(triGeometry(next, d.u, pool[g], g))
                }
            }
        }
        return draws
    }

    private fun reference(scene: SpanScene, draws: List<Any>) {
        val run = NativeSpan(scene)
        try {
        val rec = run.rec
        for (d in draws) {
            if (d is FillDraw) {
                d.u.copyInto(run.uniforms)
                for (row in d.top..d.bottom) {
                    rec[RdpSpanAccel.SPAN_ROW] = row
                    rec[RdpSpanAccel.SPAN_LX] = d.left
                    rec[RdpSpanAccel.SPAN_RX] = d.right
                    run.span()
                }
                continue
            }
            if (d is RectDraw) {
                d.tmem.copyInto(run.tmem)
                d.u.copyInto(run.uniforms)
                for (row in d.top..d.bottom) {
                    rec[RdpSpanAccel.SPAN_ROW] = row
                    rec[RdpSpanAccel.SPAN_LX] = d.left
                    rec[RdpSpanAccel.SPAN_RX] = d.right
                    rec[RdpSpanAccel.SPAN_UNSCRX] = d.right
                    primInto(rec, d.u)
                    run.span()
                }
                continue
            }
            d as TriDraw
            d.tmem.copyInto(run.tmem)
            d.u.copyInto(run.uniforms)
            for (i in d.first..d.last) {
                if (!d.valid[i]) continue
                val length = if (d.u[RdpSpanAccel.U_FLIP] != 0) d.lx[i] - d.rx[i] else d.rx[i] - d.lx[i]
                if (length < 0) continue
                rec[RdpSpanAccel.SPAN_ROW] = i
                rec[RdpSpanAccel.SPAN_LX] = d.lx[i]
                rec[RdpSpanAccel.SPAN_RX] = d.rx[i]
                rec[RdpSpanAccel.SPAN_UNSCRX] = d.unscrx[i]
                rec[RdpSpanAccel.SPAN_R] = d.r[i]
                rec[RdpSpanAccel.SPAN_G] = d.g[i]
                rec[RdpSpanAccel.SPAN_B] = d.b[i]
                rec[RdpSpanAccel.SPAN_A] = d.a[i]
                rec[RdpSpanAccel.SPAN_Z] = d.z[i]
                rec[RdpSpanAccel.SPAN_S] = d.s[i]
                rec[RdpSpanAccel.SPAN_T] = d.t[i]
                rec[RdpSpanAccel.SPAN_W] = d.w[i]
                for (k in 0 until 4) {
                    rec[RdpSpanAccel.SPAN_MINORX0 + k] = d.minorX[i * 4 + k]
                    rec[RdpSpanAccel.SPAN_MAJORX0 + k] = d.majorX[i * 4 + k]
                    rec[RdpSpanAccel.SPAN_INVALY0 + k] = d.invalY[i * 4 + k]
                }
                primInto(rec, d.u)
                run.span()
            }
        }
        } finally {
            run.close()
        }
    }

    private fun accel(scene: SpanScene, draws: List<Any>, reverse: Boolean = false) {
        val accelTmem = ByteArray(4096)
        val sa = RdpSpanAccel(scene.rdram, scene.hidden, accelTmem, Luts.zcom, Luts.zdec, Luts.deltaz, Luts.tcdiv) { _, _ -> }
        for (d in draws) {
            if (d is FillDraw) {
                sa.renderFill(d.u, d.top, d.bottom, d.left, d.right, 0)
                continue
            }
            if (d is RectDraw) {
                d.tmem.copyInto(accelTmem)
                sa.renderTexrect(d.u, d.top, d.bottom, d.left, d.right, d.tmemGen)
                continue
            }
            d as TriDraw
            d.tmem.copyInto(accelTmem)
            sa.render(
                d.first, d.last, d.u, d.valid, d.lx, d.rx, d.unscrx,
                d.r, d.g, d.b, d.a, d.z, d.s, d.t, d.w, d.minorX, d.majorX, d.invalY, d.tmemGen,
            )
        }
        sa.flush()
    }

    private fun assertSame(seed: Long, reverse: Boolean = false) {
        val draws = makeScene(seed)
        val ref = SpanScene()
        reference(ref, draws)
        val acc = SpanScene()
        Gpu.unregister()
        Gpu.register(SoftwareGpu(if (reverse) SoftwareGpu.Order.Reverse else SoftwareGpu.Order.Forward))
        GfxPool.reset()
        accel(acc, draws)
        for (i in ref.rdram.indices) {
            if (ref.rdram[i] != acc.rdram[i]) {
                throw AssertionError("seed $seed: rdram[$i] accel=${acc.rdram[i].toString(16)} ref=${ref.rdram[i].toString(16)}")
            }
        }
        if (!ref.hidden.contentEquals(acc.hidden)) throw AssertionError("seed $seed: hidden mismatch")
    }

    @Test
    fun residentSpansMatchReference() {
        GfxPool.reset()
        Gpu.register(SoftwareGpu())
        try {
            for (seed in 0 until 400) assertSame(seed.toLong() * 2654435761L + 1)
        } finally {
            Gpu.unregister()
            GfxPool.reset()
        }
    }

    @Test
    fun resultIsIndependentOfWorkgroupOrder() {
        GfxPool.reset()
        Gpu.register(SoftwareGpu())
        try {
            for (seed in 0 until 400) assertSame(seed.toLong() * 2654435761L + 1, reverse = true)
        } finally {
            Gpu.unregister()
            GfxPool.reset()
        }
    }
}
