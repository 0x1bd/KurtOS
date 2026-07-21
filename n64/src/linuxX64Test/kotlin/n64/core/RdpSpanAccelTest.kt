package n64.core

import kapi.gfx.GfxPool
import kapi.gpu.Gpu
import kapi.gpu.GpuBackend
import kapi.gpu.GpuBuffer
import kapi.gpu.GpuKernel
import kotlin.test.Test

private class SpanBuffer(override val words: Int, override val gpuAddress: Long) : GpuBuffer {
    val data = IntArray(words)
    override fun writeWord(word: Int, value: Int) { data[word] = value }
    override fun readWord(word: Int): Int = data[word]
    override fun write(dstWord: Int, src: IntArray, srcWord: Int, count: Int) {
        src.copyInto(data, dstWord, srcWord, srcWord + count)
    }
    override fun read(srcWord: Int, dst: IntArray, dstWord: Int, count: Int) {
        data.copyInto(dst, dstWord, srcWord, srcWord + count)
    }
    override fun zero() = data.fill(0)
}

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

private object SpanMath {
    fun clamp255(v: Int): Int = if (v < 0) 0 else if (v > 255) 255 else v
    fun channel(color: Int, index: Int): Int = (color ushr (index * 8)) and 0xFF
    fun pack(r: Int, g: Int, b: Int, a: Int): Int =
        (r and 0xFF) or ((g and 0xFF) shl 8) or ((b and 0xFF) shl 16) or ((a and 0xFF) shl 24)
    fun sx16(v: Int): Int = v.toShort().toInt()
    fun maskbits(m: Int): Int = if (m == 0) 0x3FF else (0xFFFF ushr (16 - m)) and 0x3FF

    fun ram16(rd: IntArray, addr: Int): Int {
        val index = (addr and RDRAM_MASK) ushr 2
        val shift = 16 - ((addr and 2) shl 3)
        return (rd[index] ushr shift) and 0xFFFF
    }
    fun store16(rd: IntArray, addr: Int, value: Int) {
        val index = (addr and RDRAM_MASK) ushr 2
        val shift = 16 - ((addr and 2) shl 3)
        rd[index] = (rd[index] and (0xFFFF shl shift).inv()) or ((value and 0xFFFF) shl shift)
    }
    fun rgba16(value: Int): Int {
        val r5 = (value ushr 11) and 0x1F
        val g5 = (value ushr 6) and 0x1F
        val b5 = (value ushr 1) and 0x1F
        val a = if (value and 1 != 0) 0xFF else 0
        return pack((r5 shl 3) or (r5 ushr 2), (g5 shl 3) or (g5 ushr 2), (b5 shl 3) or (b5 ushr 2), a)
    }

    fun readColor(rd: IntArray, u: IntArray, x: Int, y: Int): Int {
        val size = u[RdpSpanAccel.U_COLORSIZE]
        val image = u[RdpSpanAccel.U_COLORIMAGE]
        val width = u[RdpSpanAccel.U_COLORWIDTH]
        if (size == 2) return rgba16(ram16(rd, image + (y * width + x) * 2))
        if (size == 3) {
            val v = rd[((image + (y * width + x) * 4) and RDRAM_MASK) ushr 2]
            return pack((v ushr 24) and 0xFF, (v ushr 16) and 0xFF, (v ushr 8) and 0xFF, v and 0xFF)
        }
        return 0
    }
    fun writeColor(rd: IntArray, u: IntArray, x: Int, y: Int, color: Int) {
        val r = channel(color, 0); val g = channel(color, 1); val b = channel(color, 2); val a = channel(color, 3)
        val size = u[RdpSpanAccel.U_COLORSIZE]
        val image = u[RdpSpanAccel.U_COLORIMAGE]
        val width = u[RdpSpanAccel.U_COLORWIDTH]
        if (size == 2) {
            val value = ((r * 31 / 255) shl 11) or ((g * 31 / 255) shl 6) or ((b * 31 / 255) shl 1) or (if (a >= 128) 1 else 0)
            store16(rd, image + (y * width + x) * 2, value)
        } else if (size == 3) {
            rd[((image + (y * width + x) * 4) and RDRAM_MASK) ushr 2] = (r shl 24) or (g shl 16) or (b shl 8) or a
        }
    }

    private fun tmemByte(tget: (Int) -> Int, at: Int, row: Int): Int {
        var offset = at
        if (row and 1 != 0) offset = offset xor 4
        return tget(offset and 0xFFF)
    }
    private fun palette(u: IntArray, tget: (Int) -> Int, index: Int): Int {
        val at = 0x800 + (index and 0xFF) * 8
        val value = (tget(at and 0xFFF) shl 8) or tget((at + 1) and 0xFFF)
        if (u[RdpSpanAccel.U_TLUTTYPE] == 0) return rgba16(value)
        val inten = (value ushr 8) and 0xFF
        return pack(inten, inten, inten, value and 0xFF)
    }
    private fun decode4(u: IntArray, tget: (Int) -> Int, value: Int): Int = when (u[RdpSpanAccel.T_FORMAT]) {
        2 -> palette(u, tget, u[RdpSpanAccel.T_PALETTE] * 16 + value)
        3 -> {
            val three = (value ushr 1) and 7
            val inten = (three shl 5) or (three shl 2) or (three shr 1)
            pack(inten, inten, inten, if (value and 1 != 0) 0xFF else 0)
        }
        else -> { val inten = value * 17; pack(inten, inten, inten, inten) }
    }
    private fun decode8(u: IntArray, tget: (Int) -> Int, value: Int): Int = when (u[RdpSpanAccel.T_FORMAT]) {
        2 -> palette(u, tget, value)
        3 -> { val inten = ((value ushr 4) and 0xF) * 17; pack(inten, inten, inten, (value and 0xF) * 17) }
        else -> pack(value, value, value, value)
    }
    private fun decode16(u: IntArray, tget: (Int) -> Int, value: Int): Int = when (u[RdpSpanAccel.T_FORMAT]) {
        3 -> { val inten = (value ushr 8) and 0xFF; pack(inten, inten, inten, value and 0xFF) }
        2 -> palette(u, tget, value and 0xFF)
        else -> rgba16(value)
    }
    private fun fetch(u: IntArray, tget: (Int) -> Int, s: Int, t: Int): Int {
        val base = u[RdpSpanAccel.T_TMEM] * 8 + t * u[RdpSpanAccel.T_LINE] * 8
        return when (u[RdpSpanAccel.T_SIZE]) {
            0 -> {
                val at = base + s / 2
                val byte = tmemByte(tget, at, t)
                val nib = if (s and 1 == 0) (byte ushr 4) and 0xF else byte and 0xF
                decode4(u, tget, nib)
            }
            1 -> decode8(u, tget, tmemByte(tget, base + s, t))
            2 -> {
                val at = base + s * 2
                decode16(u, tget, (tmemByte(tget, at, t) shl 8) or tmemByte(tget, at + 1, t))
            }
            else -> {
                val at = base + s * 2
                var high = at
                if (t and 1 != 0) high = high xor 4
                pack(tget(high and 0x7FF), tget((high + 1) and 0x7FF), tget((high and 0x7FF) or 0x800), tget(((high + 1) and 0x7FF) or 0x800))
            }
        }
    }

    private fun tclodClamp(coord: Int): Int = when {
        coord and 0x40000 != 0 -> 0x7FFF
        coord and 0x20000 != 0 -> 0x8000
        else -> when (coord and 0x18000) { 0x8000 -> 0x7FFF; 0x10000 -> 0x8000; else -> coord and 0xFFFF }
    }
    private fun tcShift(coord: Int, shifter: Int): Int =
        if (shifter < 11) sx16(coord) shr shifter else sx16(coord shl (16 - shifter))

    fun sampleTex(u: IntArray, tget: (Int) -> Int, tcdiv: IntArray, sIn: Int, tIn: Int, wIn: Int): Int {
        val ss = (sIn shr 16) and 0xFFFF
        val st = (tIn shr 16) and 0xFFFF
        val sw = (wIn shr 16) and 0xFFFF
        var sss: Int
        var sst: Int
        if (u[RdpSpanAccel.U_PERSP] == 0) {
            sss = sx16(ss) and 0x1FFFF
            sst = sx16(st) and 0x1FFFF
        } else {
            val wCarry = sx16(sw) <= 0
            val entry = tcdiv[sw and 0x7FFF]
            val tluRcp = entry shr 4
            val shift = entry and 0xF
            val sprod = sx16(ss) * tluRcp
            val tprod = sx16(st) * tluRcp
            val tempmask = ((1 shl 30) - 1) and -((1 shl 29) shr shift)
            val oobS = sprod and tempmask
            val oobT = tprod and tempmask
            val temps: Int
            val tempt: Int
            if (shift != 0xE) { temps = sprod shr (13 - shift); tempt = tprod shr (13 - shift) } else { temps = sprod shl 1; tempt = tprod shl 1 }
            var ouS = 0; var ouT = 0
            if (oobS != tempmask && oobS != 0) ouS = if (sprod and (1 shl 29) == 0) 2 shl 17 else 1 shl 17
            if (oobT != tempmask && oobT != 0) ouT = if (tprod and (1 shl 29) == 0) 2 shl 17 else 1 shl 17
            if (wCarry) { ouS = ouS or (2 shl 17); ouT = ouT or (2 shl 17) }
            sss = (temps and 0x1FFFF) or ouS
            sst = (tempt and 0x1FFFF) or ouT
        }
        return fetchSampled(u, tget, tclodClamp(sss), tclodClamp(sst))
    }

    private fun fetchSampled(u: IntArray, tget: (Int) -> Int, rawS: Int, rawT: Int): Int {
        var s = tcShift(rawS, u[RdpSpanAccel.T_SHIFTS])
        val maxS = (s shr 3) >= u[RdpSpanAccel.T_SH]
        var t = tcShift(rawT, u[RdpSpanAccel.T_SHIFTT])
        val maxT = (t shr 3) >= u[RdpSpanAccel.T_TH]
        s -= u[RdpSpanAccel.T_SL] shl 3
        t -= u[RdpSpanAccel.T_TL] shl 3
        val clampEnS = u[RdpSpanAccel.T_CLAMPENS] != 0
        val clampEnT = u[RdpSpanAccel.T_CLAMPENT] != 0
        val maskS = u[RdpSpanAccel.T_MASKS]
        val maskT = u[RdpSpanAccel.T_MASKT]

        if (u[RdpSpanAccel.U_SAMPLETYPE] == 0 && u[RdpSpanAccel.U_TLUTEN] == 0) {
            s = if (clampEnS) { if (maxS) u[RdpSpanAccel.T_CLAMPDIFFS] else if (s and 0x10000 != 0) 0 else s shr 5 } else s shr 5
            t = if (clampEnT) { if (maxT) u[RdpSpanAccel.T_CLAMPDIFFT] else if (t and 0x10000 != 0) 0 else t shr 5 } else t shr 5
            if (maskS != 0) {
                if (u[RdpSpanAccel.T_MIRRORS] != 0 && (s shr u[RdpSpanAccel.T_MASKSCLAMPED]) and 1 != 0) s = s.inv()
                s = s and maskbits(maskS)
            }
            if (maskT != 0) {
                if (u[RdpSpanAccel.T_MIRRORT] != 0 && (t shr u[RdpSpanAccel.T_MASKTCLAMPED]) and 1 != 0) t = t.inv()
                t = t and maskbits(maskT)
            }
            return fetch(u, tget, s, t)
        }

        var sFrac = s and 0x1F
        var tFrac = t and 0x1F
        if (clampEnS) {
            when { maxS -> { s = u[RdpSpanAccel.T_CLAMPDIFFS]; sFrac = 0 }; s and 0x10000 != 0 -> { s = 0; sFrac = 0 }; else -> s = s shr 5 }
        } else s = s shr 5
        if (clampEnT) {
            when { maxT -> { t = u[RdpSpanAccel.T_CLAMPDIFFT]; tFrac = 0 }; t and 0x10000 != 0 -> { t = 0; tFrac = 0 }; else -> t = t shr 5 }
        } else t = t shr 5

        var sDiff: Int
        var tDiff: Int
        if (maskS != 0) {
            val bits = maskbits(maskS)
            if (u[RdpSpanAccel.T_MIRRORS] != 0) {
                val wrap = (s shr u[RdpSpanAccel.T_MASKSCLAMPED]) and 1
                if (wrap != 0) s = s.inv()
                s = s and bits
                sDiff = if (((s - wrap) and bits) == bits) 0 else 1 - (wrap shl 1)
            } else { s = s and bits; sDiff = if (s == bits) -s else 1 }
        } else sDiff = 1
        if (maskT != 0) {
            val bits = maskbits(maskT)
            if (u[RdpSpanAccel.T_MIRRORT] != 0) {
                val wrap = (t shr u[RdpSpanAccel.T_MASKTCLAMPED]) and 1
                if (wrap != 0) t = t.inv()
                t = t and bits
                tDiff = if (((t - wrap) and bits) == bits) 0 else 1 - (wrap shl 1)
            } else { t = t and bits; tDiff = if (t == bits) -(t and 0xFF) else 1 }
        } else tDiff = 1
        if (u[RdpSpanAccel.U_SAMPLETYPE] == 0) { sDiff = 0; tDiff = 0 }

        val t0 = fetch(u, tget, s, t)
        val t1 = fetch(u, tget, s + sDiff, t)
        val t2 = fetch(u, tget, s, t + tDiff)
        val t3 = fetch(u, tget, s + sDiff, t + tDiff)
        val upper = (sFrac + tFrac) and 0x20 != 0
        val center = u[RdpSpanAccel.U_MIDTEXEL] != 0 && sFrac == 0x10 && tFrac == 0x10
        var result = 0
        for (index in 0 until 4) {
            val shift = index * 8
            val c0 = (t0 ushr shift) and 0xFF
            val c1 = (t1 ushr shift) and 0xFF
            val c2 = (t2 ushr shift) and 0xFF
            val c3 = (t3 ushr shift) and 0xFF
            val value = if (center) {
                c3 + ((((c1 + c2) shl 6) - (c3 shl 7) + ((c3.inv() + c0) shl 6) + 0xC0) shr 8)
            } else if (upper) {
                val invSf = 0x20 - sFrac; val invTf = 0x20 - tFrac
                c3 + ((invSf * (c2 - c3) + invTf * (c1 - c3) + 0x10) shr 5)
            } else {
                c0 + ((sFrac * (c1 - c0) + tFrac * (c2 - c0) + 0x10) shr 5)
            }
            result = result or (clamp255(value) shl shift)
        }
        return result
    }

    private fun csrc(s: Int, index: Int, texel0: Int, texel1: Int, shade: Int, combined: Int, u: IntArray, isA: Boolean): Int = when (s) {
        0 -> channel(combined, index)
        1 -> channel(texel0, index)
        2 -> channel(texel1, index)
        3 -> channel(u[RdpSpanAccel.U_PRIM], index)
        4 -> channel(shade, index)
        5 -> channel(u[RdpSpanAccel.U_ENV], index)
        6 -> if (isA) 0xFF else 0
        else -> 0
    }
    private fun cmul(s: Int, index: Int, texel0: Int, texel1: Int, shade: Int, shadeAlpha: Int, combined: Int, combinedAlpha: Int, u: IntArray): Int = when (s) {
        0 -> channel(combined, index)
        1 -> channel(texel0, index)
        2 -> channel(texel1, index)
        3 -> channel(u[RdpSpanAccel.U_PRIM], index)
        4 -> channel(shade, index)
        5 -> channel(u[RdpSpanAccel.U_ENV], index)
        6 -> 0xFF
        7 -> combinedAlpha
        8 -> (texel0 ushr 24) and 0xFF
        9 -> (texel1 ushr 24) and 0xFF
        10 -> (u[RdpSpanAccel.U_PRIM] ushr 24) and 0xFF
        11 -> shadeAlpha
        12 -> (u[RdpSpanAccel.U_ENV] ushr 24) and 0xFF
        14 -> u[RdpSpanAccel.U_PRIMLOD]
        else -> 0
    }
    private fun cadd(s: Int, index: Int, texel0: Int, texel1: Int, shade: Int, combined: Int, u: IntArray): Int = when (s) {
        0 -> channel(combined, index)
        1 -> channel(texel0, index)
        2 -> channel(texel1, index)
        3 -> channel(u[RdpSpanAccel.U_PRIM], index)
        4 -> channel(shade, index)
        5 -> channel(u[RdpSpanAccel.U_ENV], index)
        6 -> 0xFF
        else -> 0
    }
    private fun asrc(s: Int, texel0: Int, texel1: Int, shadeAlpha: Int, combinedAlpha: Int, u: IntArray): Int = when (s) {
        0 -> combinedAlpha
        1 -> (texel0 ushr 24) and 0xFF
        2 -> (texel1 ushr 24) and 0xFF
        3 -> (u[RdpSpanAccel.U_PRIM] ushr 24) and 0xFF
        4 -> shadeAlpha
        5 -> (u[RdpSpanAccel.U_ENV] ushr 24) and 0xFF
        6 -> 0xFF
        else -> 0
    }
    private fun amul(s: Int, texel0: Int, texel1: Int, shadeAlpha: Int, u: IntArray): Int = when (s) {
        1 -> (texel0 ushr 24) and 0xFF
        2 -> (texel1 ushr 24) and 0xFF
        3 -> (u[RdpSpanAccel.U_PRIM] ushr 24) and 0xFF
        4 -> shadeAlpha
        5 -> (u[RdpSpanAccel.U_ENV] ushr 24) and 0xFF
        6 -> u[RdpSpanAccel.U_PRIMLOD]
        else -> 0
    }
    fun combine(texel0: Int, texel1: Int, shade: Int, shadeAlpha: Int, u: IntArray): Int {
        var combined = 0
        var combinedAlpha = 0
        val c = RdpSpanAccel.U_CRGB
        val al = RdpSpanAccel.U_CALPHA
        val cycles = if (u[RdpSpanAccel.U_CYCLE2] != 0) 2 else 1
        for (cycle in 0 until cycles) {
            var result = 0
            for (index in 0 until 3) {
                val a = csrc(u[c + 0 + cycle], index, texel0, texel1, shade, combined, u, true)
                val b = csrc(u[c + 2 + cycle], index, texel0, texel1, shade, combined, u, false)
                val m = cmul(u[c + 4 + cycle], index, texel0, texel1, shade, shadeAlpha, combined, combinedAlpha, u)
                val d = cadd(u[c + 6 + cycle], index, texel0, texel1, shade, combined, u)
                result = result or (clamp255((((a - b) * m) shr 8) + d) shl (index * 8))
            }
            val aa = asrc(u[al + 0 + cycle], texel0, texel1, shadeAlpha, combinedAlpha, u)
            val ab = asrc(u[al + 2 + cycle], texel0, texel1, shadeAlpha, combinedAlpha, u)
            val ac = amul(u[al + 4 + cycle], texel0, texel1, shadeAlpha, u)
            val ad = asrc(u[al + 6 + cycle], texel0, texel1, shadeAlpha, combinedAlpha, u)
            combinedAlpha = clamp255((((aa - ab) * ac) shr 8) + ad)
            combined = result
        }
        return (combined and 0xFFFFFF) or ((combinedAlpha and 0xFF) shl 24)
    }

    private fun blendSelect(code: Int, chained: Int, rd: IntArray, u: IntArray, x: Int, y: Int): Int = when (code) {
        0 -> chained
        1 -> readColor(rd, u, x, y)
        2 -> u[RdpSpanAccel.U_BLEND]
        else -> u[RdpSpanAccel.U_FOG]
    }
    private fun blendEquation(cycle: Int, chained: Int, pixelAlpha: Int, rd: IntArray, u: IntArray, x: Int, y: Int, shadeAlpha: Int): Int {
        val bl = RdpSpanAccel.U_BLENDSEL
        val bA = u[bl + 0 + cycle]; val bB = u[bl + 2 + cycle]; val bC = u[bl + 4 + cycle]; val bD = u[bl + 6 + cycle]
        val aMul = when (bB) { 0 -> pixelAlpha; 1 -> (u[RdpSpanAccel.U_FOG] ushr 24) and 0xFF; 2 -> shadeAlpha; else -> 0 }
        val bMul = when (bD) { 0 -> aMul.inv() and 0xFF; 1 -> 0xE0; 2 -> 0xFF; else -> 0 }
        val blend1a = aMul shr 3
        var blend2a = bMul shr 3
        if (bD == 1) blend2a = blend2a or 3
        val mulb = blend2a + 1
        val p = blendSelect(bA, chained, rd, u, x, y)
        val m = blendSelect(bC, chained, rd, u, x, y)
        var result = 0
        for (idx in 0 until 3) {
            val value = channel(p, idx) * blend1a + channel(m, idx) * mulb
            result = result or (((value shr 5) and 0xFF) shl (idx * 8))
        }
        return result
    }
    fun blendPixel(rd: IntArray, u: IntArray, x: Int, y: Int, color: Int, shadeAlpha: Int): Int {
        var alpha = (color ushr 24) and 0xFF
        if (alpha == 0xFF) alpha = 0x100
        val bl = RdpSpanAccel.U_BLENDSEL
        var chained = color
        if (u[RdpSpanAccel.U_CYCLE2] != 0) {
            chained = (blendEquation(0, color, alpha, rd, u, x, y, shadeAlpha) and 0xFFFFFF) or (color and 0xFF000000.toInt())
        }
        val cycle = if (u[RdpSpanAccel.U_CYCLE2] != 0) 1 else 0
        val partialReject = u[bl + 2 + cycle] == 0 && u[bl + 6 + cycle] == 0
        val result = if (u[RdpSpanAccel.U_FORCEBLEND] == 0 || (partialReject && alpha >= 0xFF)) {
            blendSelect(u[bl + 0 + cycle], chained, rd, u, x, y)
        } else blendEquation(cycle, chained, alpha, rd, u, x, y, shadeAlpha)
        return (result and 0xFFFFFF) or (((color ushr 24) and 0xFF) shl 24)
    }

    fun zcorrect(walked: Int): Int {
        val sz = ((walked shr 10) and 0x3FFFFF) shr 3
        return when ((sz and 0x60000) shr 17) { 2 -> 0x3FFFF; 3 -> 0; else -> sz and 0x3FFFF }
    }
    fun zpass(rd: IntArray, hget: (Int) -> Int, u: IntArray, x: Int, y: Int, szIn: Int, dzPix: Int): Boolean {
        val sz = szIn and 0x3FFFF
        val at = u[RdpSpanAccel.U_ZIMAGE] + (y * u[RdpSpanAccel.U_COLORWIDTH] + x) * 2
        val zval = ram16(rd, at)
        val hval = hget((at and RDRAM_MASK) ushr 1) and 3
        val oz = Luts.zdec[(zval shr 2) and 0x3FFF]
        val rawDzMem = ((zval and 3) shl 2) or hval
        var dzMem = 1 shl rawDzMem
        var forceCoplanar = false
        val precision = (zval shr 13) and 0xF
        if (precision < 3) {
            if (dzMem != 0x8000) {
                val modifier = 16 shr precision
                dzMem = dzMem shl 1
                if (dzMem < modifier) dzMem = modifier
            } else { forceCoplanar = true; dzMem = 0xFFFF }
        }
        val dzNew = Luts.deltaz[(dzPix or dzMem) and 0xFFFF] shl 3
        val farther = forceCoplanar || (sz + dzNew >= oz)
        val max = oz == 0x3FFFF
        val inFront = sz < oz
        return when (u[RdpSpanAccel.U_ZMODE]) {
            0 -> max || inFront
            1 -> if (!inFront || !farther) max || inFront else true
            2 -> inFront || max
            else -> { val nearer = forceCoplanar || (sz - dzNew <= oz); farther && nearer && !max }
        }
    }
    fun zstore(rd: IntArray, hset: (Int, Int) -> Unit, u: IntArray, x: Int, y: Int, z: Int, dzPixEnc: Int) {
        val at = u[RdpSpanAccel.U_ZIMAGE] + (y * u[RdpSpanAccel.U_COLORWIDTH] + x) * 2
        store16(rd, at, Luts.zcom[z and 0x3FFFF] or (dzPixEnc shr 2))
        hset((at and RDRAM_MASK) ushr 1, dzPixEnc and 3)
    }

    private fun rightcvghex(x: Int, fmask: Int): Int = (0xF0 ushr (((x and 7) + 1) shr 1)) and fmask
    private fun leftcvghex(x: Int, fmask: Int): Int = (0xF ushr (((x and 7) + 1) shr 1)) and fmask
    private fun popcount8(v: Int): Int {
        var x = v and 0xFF
        x -= (x shr 1) and 0x55
        x = (x and 0x33) + ((x shr 2) and 0x33)
        return (x + (x shr 4)) and 0xF
    }
    private fun coverageAt(x: Int, flip: Boolean, ps: Int, pe: Int, rec: IntArray, base: Int): Int {
        var mask = 0xFF
        for (sub in 0 until 4) {
            val fmask = 0xA ushr (sub and 1)
            val maskshift = (sub - 2) and 4
            val fms = fmask shl maskshift
            if (rec[base + RdpSpanAccel.SPAN_INVALY0 + sub] != 0) { mask = mask and fms.inv(); continue }
            val minorcur = rec[base + RdpSpanAccel.SPAN_MINORX0 + sub]
            val majorcur = rec[base + RdpSpanAccel.SPAN_MAJORX0 + sub]
            val minorint = minorcur shr 3
            val majorint = majorcur shr 3
            if (flip) {
                val lo = if (majorint < pe) majorint else pe
                val hi = if (minorint > ps) minorint else ps
                if (x <= lo || x >= hi) mask = mask and fms.inv()
                if (minorint > majorint) {
                    if (x == minorint) mask = mask or (rightcvghex(minorcur, fmask) shl maskshift)
                    if (x == majorint) mask = mask or (leftcvghex(majorcur, fmask) shl maskshift)
                } else if (minorint == majorint) {
                    if (x == majorint) mask = mask or ((rightcvghex(minorcur, fmask) and leftcvghex(majorcur, fmask)) shl maskshift)
                }
            } else {
                val lo = if (minorint < pe) minorint else pe
                val hi = if (majorint > ps) majorint else ps
                if (x <= lo || x >= hi) mask = mask and fms.inv()
                if (majorint > minorint) {
                    if (x == minorint) mask = mask or (leftcvghex(minorcur, fmask) shl maskshift)
                    if (x == majorint) mask = mask or (rightcvghex(majorcur, fmask) shl maskshift)
                } else if (minorint == majorint) {
                    if (x == majorint) mask = mask or ((leftcvghex(minorcur, fmask) and rightcvghex(majorcur, fmask)) shl maskshift)
                }
            }
        }
        return mask
    }

    fun writePixel(
        rd: IntArray, hget: (Int) -> Int, hset: (Int, Int) -> Unit, u: IntArray,
        x: Int, y: Int, color: Int, z: Int, shadeAlpha: Int, cvg: Int,
        spanDzPix: Int, spanDzEnc: Int,
    ) {
        var alpha = (color ushr 24) and 0xFF
        if (u[RdpSpanAccel.U_CTA] != 0) {
            val scaled = (alpha * cvg + 4) shr 3
            if (scaled shr 5 == 0) return
            if (u[RdpSpanAccel.U_ACS] != 0) alpha = minOf(scaled, 0xFF)
        } else if (u[RdpSpanAccel.U_ACS] != 0) {
            alpha = if (cvg >= 8) 0xFF else cvg shl 5
        }
        if (u[RdpSpanAccel.U_ALPHACOMPARE] != 0) {
            val ref = (u[RdpSpanAccel.U_BLEND] ushr 24) and 0xFF
            if (alpha < ref) return
        }
        val recolor = (color and 0xFFFFFF) or (alpha shl 24)
        if (u[RdpSpanAccel.U_USEDEPTH] != 0 && u[RdpSpanAccel.U_ZIMAGE] != 0) {
            val src = u[RdpSpanAccel.U_ZSOURCE] != 0
            val dzPix = if (src) u[RdpSpanAccel.U_PRIMDZ] else spanDzPix
            val dzEnc = if (src) u[RdpSpanAccel.U_PRIMDZENC] else spanDzEnc
            if (u[RdpSpanAccel.U_ZCOMPARE] != 0 && !zpass(rd, hget, u, x, y, z, dzPix)) return
            writeColor(rd, u, x, y, blendPixel(rd, u, x, y, recolor, shadeAlpha))
            if (u[RdpSpanAccel.U_ZUPDATE] != 0) zstore(rd, hset, u, x, y, z, dzEnc)
            return
        }
        writeColor(rd, u, x, y, blendPixel(rd, u, x, y, recolor, shadeAlpha))
    }

    private fun samplePoint(u: IntArray, tget: (Int) -> Int, rawS: Int, rawT: Int): Int {
        var s = tcShift(rawS, u[RdpSpanAccel.T_SHIFTS])
        val maxS = (s shr 3) >= u[RdpSpanAccel.T_SH]
        var t = tcShift(rawT, u[RdpSpanAccel.T_SHIFTT])
        val maxT = (t shr 3) >= u[RdpSpanAccel.T_TH]
        s -= u[RdpSpanAccel.T_SL] shl 3
        t -= u[RdpSpanAccel.T_TL] shl 3
        val clampEnS = u[RdpSpanAccel.T_CLAMPENS] != 0
        val clampEnT = u[RdpSpanAccel.T_CLAMPENT] != 0
        s = if (clampEnS) { if (maxS) u[RdpSpanAccel.T_CLAMPDIFFS] else if (s and 0x10000 != 0) 0 else s shr 5 } else s shr 5
        t = if (clampEnT) { if (maxT) u[RdpSpanAccel.T_CLAMPDIFFT] else if (t and 0x10000 != 0) 0 else t shr 5 } else t shr 5
        val maskS = u[RdpSpanAccel.T_MASKS]
        val maskT = u[RdpSpanAccel.T_MASKT]
        if (maskS != 0) {
            if (u[RdpSpanAccel.T_MIRRORS] != 0 && (s shr u[RdpSpanAccel.T_MASKSCLAMPED]) and 1 != 0) s = s.inv()
            s = s and maskbits(maskS)
        }
        if (maskT != 0) {
            if (u[RdpSpanAccel.T_MIRRORT] != 0 && (t shr u[RdpSpanAccel.T_MASKTCLAMPED]) and 1 != 0) t = t.inv()
            t = t and maskbits(maskT)
        }
        return fetch(u, tget, s, t)
    }

    fun runFillSpan(rd: IntArray, hset: (Int, Int) -> Unit, u: IntArray, rec: IntArray, base: Int) {
        val row = rec[base + RdpSpanAccel.SPAN_ROW]
        val left = rec[base + RdpSpanAccel.SPAN_LX]
        val right = rec[base + RdpSpanAccel.SPAN_RX]
        val fillColor = u[RdpSpanAccel.U_FILLCOLOR]
        val size = u[RdpSpanAccel.U_COLORSIZE]
        val image = u[RdpSpanAccel.U_COLORIMAGE]
        val width = u[RdpSpanAccel.U_COLORWIDTH]
        for (x in left..right) {
            if (size == 2) {
                val value = if (x and 1 == 0) (fillColor ushr 16) and 0xFFFF else fillColor and 0xFFFF
                val at = image + (row * width + x) * 2
                store16(rd, at, value)
                hset((at and RDRAM_MASK) ushr 1, if (value and 1 != 0) 3 else 0)
            } else if (size == 3) {
                rd[((image + (row * width + x) * 4) and RDRAM_MASK) ushr 2] = fillColor
            }
        }
    }

    fun runTexrectSpan(rd: IntArray, hget: (Int) -> Int, hset: (Int, Int) -> Unit, tget: (Int) -> Int, u: IntArray, rec: IntArray, base: Int) {
        val row = rec[base + RdpSpanAccel.SPAN_ROW]
        val left = rec[base + RdpSpanAccel.SPAN_LX]
        val right = rec[base + RdpSpanAccel.SPAN_RX]
        val copy = u[RdpSpanAccel.U_COPY] != 0
        val trX0 = rec[base + RdpSpanAccel.SPAN_TR_X0]; val trY0 = rec[base + RdpSpanAccel.SPAN_TR_Y0]
        val trS = rec[base + RdpSpanAccel.SPAN_TR_S]; val trT = rec[base + RdpSpanAccel.SPAN_TR_T]
        val trDsdx = rec[base + RdpSpanAccel.SPAN_TR_DSDX]; val trDtdy = rec[base + RdpSpanAccel.SPAN_TR_DTDY]
        val trShift = rec[base + RdpSpanAccel.SPAN_TR_SHIFT]
        val trFlip = rec[base + RdpSpanAccel.SPAN_TR_FLIP] != 0
        val spanDzPix = rec[base + RdpSpanAccel.SPAN_DZPIX]
        val spanDzEnc = rec[base + RdpSpanAccel.SPAN_DZPIXENC]
        val scXh = u[RdpSpanAccel.U_SCISSOR_XH]; val scXl = u[RdpSpanAccel.U_SCISSOR_XL]
        for (x in left..right) {
            if (x < scXh || x >= scXl) continue
            val dx = x - trX0; val dy = row - trY0
            val texS: Int; val texT: Int
            if (trFlip) {
                texS = trS + ((dy * trDsdx) shr trShift)
                texT = trT + ((dx * trDtdy) shr trShift)
            } else {
                texS = trS + ((dx * trDsdx) shr trShift)
                texT = trT + ((dy * trDtdy) shr trShift)
            }
            if (copy) {
                val texel = samplePoint(u, tget, texS, texT)
                if (u[RdpSpanAccel.U_ALPHACOMPARE] == 0 || (texel ushr 24) and 0xFF != 0) writeColor(rd, u, x, row, texel)
            } else {
                val texel = if (u[RdpSpanAccel.U_TEXTURE] != 0) fetchSampled(u, tget, texS, texT) else 0
                val color = combine(texel, texel, u[RdpSpanAccel.U_RECTSHADE], 0xFF, u)
                writePixel(rd, hget, hset, u, x, row, color, 0, 0, 8, spanDzPix, spanDzEnc)
            }
        }
    }

    fun runSpan(rd: IntArray, hget: (Int) -> Int, hset: (Int, Int) -> Unit, tget: (Int) -> Int, tcdiv: IntArray, u: IntArray, rec: IntArray, base: Int) {
        val row = rec[base + RdpSpanAccel.SPAN_ROW]
        val xstart = rec[base + RdpSpanAccel.SPAN_LX]
        val xendsc = rec[base + RdpSpanAccel.SPAN_RX]
        val xend = rec[base + RdpSpanAccel.SPAN_UNSCRX]
        var r = rec[base + RdpSpanAccel.SPAN_R]
        var g = rec[base + RdpSpanAccel.SPAN_G]
        var b = rec[base + RdpSpanAccel.SPAN_B]
        var a = rec[base + RdpSpanAccel.SPAN_A]
        var z = rec[base + RdpSpanAccel.SPAN_Z]
        var s = rec[base + RdpSpanAccel.SPAN_S]
        var t = rec[base + RdpSpanAccel.SPAN_T]
        var w = rec[base + RdpSpanAccel.SPAN_W]

        val flip = rec[base + RdpSpanAccel.SPAN_FLIP] != 0
        val spanDzPix = rec[base + RdpSpanAccel.SPAN_DZPIX]
        val spanDzEnc = rec[base + RdpSpanAccel.SPAN_DZPIXENC]
        val scXh = u[RdpSpanAccel.U_SCISSOR_XH]
        val scXl = u[RdpSpanAccel.U_SCISSOR_XL]
        val drinc = if (flip) rec[base + RdpSpanAccel.SPAN_DR] else -rec[base + RdpSpanAccel.SPAN_DR]
        val dginc = if (flip) rec[base + RdpSpanAccel.SPAN_DG] else -rec[base + RdpSpanAccel.SPAN_DG]
        val dbinc = if (flip) rec[base + RdpSpanAccel.SPAN_DB] else -rec[base + RdpSpanAccel.SPAN_DB]
        val dainc = if (flip) rec[base + RdpSpanAccel.SPAN_DA] else -rec[base + RdpSpanAccel.SPAN_DA]
        val dzinc = if (flip) rec[base + RdpSpanAccel.SPAN_DZ] else -rec[base + RdpSpanAccel.SPAN_DZ]
        val dsinc = if (flip) rec[base + RdpSpanAccel.SPAN_DS] else -rec[base + RdpSpanAccel.SPAN_DS]
        val dtinc = if (flip) rec[base + RdpSpanAccel.SPAN_DT] else -rec[base + RdpSpanAccel.SPAN_DT]
        val dwinc = if (flip) rec[base + RdpSpanAccel.SPAN_DW] else -rec[base + RdpSpanAccel.SPAN_DW]
        val xinc = if (flip) 1 else -1

        val length = if (flip) xstart - xendsc else xendsc - xstart
        var scdiff = if (flip) xendsc - xend else xend - xendsc
        if (scdiff != 0) {
            scdiff = scdiff and 0xFFF
            r += drinc * scdiff; g += dginc * scdiff; b += dbinc * scdiff; a += dainc * scdiff
            s += dsinc * scdiff; t += dtinc * scdiff; w += dwinc * scdiff; z += dzinc * scdiff
        }

        val forceBlend = u[RdpSpanAccel.U_FORCEBLEND] != 0
        val cta = u[RdpSpanAccel.U_CTA] != 0
        val acs = u[RdpSpanAccel.U_ACS] != 0
        val ownerMode = forceBlend && !cta && !acs
        val needCvg = cta || acs || ownerMode
        val shadeOn = u[RdpSpanAccel.U_SHADE] != 0
        val textureOn = u[RdpSpanAccel.U_TEXTURE] != 0
        val zSource = u[RdpSpanAccel.U_ZSOURCE] != 0
        val ps = if (flip) xendsc else xstart
        val pe = if (flip) xstart else xendsc

        var x = xendsc
        for (pixel in 0..length) {
            if (x >= scXh && x < scXl) {
                var draw = true
                var drawCvg = 8
                if (needCvg) {
                    val mask = coverageAt(x, flip, ps, pe, rec, base)
                    val cvg = popcount8(mask)
                    if (ownerMode) { draw = cvg > 4 || (cvg == 4 && (mask and 0x80) != 0); drawCvg = 8 } else { draw = cvg != 0; drawCvg = cvg }
                }
                if (draw) {
                    val shade = if (shadeOn) pack(clamp255(r shr 16), clamp255(g shr 16), clamp255(b shr 16), clamp255(a shr 16)) else 0
                    val shadeAlpha = (shade ushr 24) and 0xFF
                    val texel = if (textureOn) sampleTex(u, tget, tcdiv, s, t, w) else 0
                    val color = combine(texel, texel, shade, shadeAlpha, u)
                    val pixelZ = zcorrect(if (zSource) u[RdpSpanAccel.U_PRIMDEPTH] shl 16 else z)
                    writePixel(rd, hget, hset, u, x, row, color, pixelZ, shadeAlpha, drawCvg, spanDzPix, spanDzEnc)
                }
            }
            r += drinc; g += dginc; b += dbinc; a += dainc
            s += dsinc; t += dtinc; w += dwinc; z += dzinc; x += xinc
        }
    }
}

private class SpanFakeBackend : GpuBackend {
    override val name = "fake"
    private val buffers = ArrayList<SpanBuffer>()
    private var nextAddr = 0x100000L
    override fun available() = true
    override fun kernel(name: String): GpuKernel? =
        if (name == "rdpspan") object : GpuKernel { override val name = "rdpspan" } else null
    override fun alloc(words: Int): GpuBuffer {
        val b = SpanBuffer(words, nextAddr)
        nextAddr += words.toLong() * 4L + 0x1000L
        buffers.add(b)
        return b
    }
    override fun free(buffer: GpuBuffer) {}

    override fun dispatch(kernel: GpuKernel, kernargs: GpuBuffer, groups: Int, threadsPerGroup: Int): Boolean {
        val ka = kernargs as SpanBuffer
        val rd = find(addr(ka, 0, 1))
        val hd = find(addr(ka, 2, 3))
        val sp = find(addr(ka, 4, 5))
        val un = find(addr(ka, 6, 7))
        val tm = find(addr(ka, 14, 15))
        val tcd = find(addr(ka, 16, 17))
        val count = ka.data[18]
        val hget = { j: Int -> (hd.data[j ushr 2] ushr ((j and 3) shl 3)) and 0xFF }
        val hset = { j: Int, v: Int ->
            val wi = j ushr 2; val sh = (j and 3) shl 3
            hd.data[wi] = (hd.data[wi] and (0xFF shl sh).inv()) or ((v and 0xFF) shl sh)
        }
        val tget = { j: Int -> (tm.data[j ushr 2] ushr ((j and 3) shl 3)) and 0xFF }
        val stride = RdpSpanAccel.SPAN_STRIDE
        for (gid in 0 until groups) {
            if (gid >= count) break
            val base = gid * stride
            if (un.data[RdpSpanAccel.U_FILL] != 0)
                SpanMath.runFillSpan(rd.data, hset, un.data, sp.data, base)
            else if (sp.data[base + RdpSpanAccel.SPAN_TEXRECT] != 0)
                SpanMath.runTexrectSpan(rd.data, hget, hset, tget, un.data, sp.data, base)
            else SpanMath.runSpan(rd.data, hget, hset, tget, tcd.data, un.data, sp.data, base)
        }
        return true
    }

    private fun addr(ka: SpanBuffer, lo: Int, hi: Int): Long =
        (ka.data[lo].toLong() and 0xFFFFFFFFL) or (ka.data[hi].toLong() shl 32)
    private fun find(a: Long): SpanBuffer = buffers.first { it.gpuAddress == a }
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
                repeat(next() % 4) { draws.add(triGeometry(next, d.u, d.tmem, d.tmemGen)) }
            }
        }
        return draws
    }

    private fun reference(scene: SpanScene, draws: List<Any>) {
        val hget = { j: Int -> scene.hidden[j].toInt() and 0xFF }
        val hset = { j: Int, v: Int -> scene.hidden[j] = v.toByte() }
        val rec = IntArray(RdpSpanAccel.SPAN_STRIDE)
        for (d in draws) {
            if (d is FillDraw) {
                for (row in d.top..d.bottom) {
                    rec[RdpSpanAccel.SPAN_ROW] = row
                    rec[RdpSpanAccel.SPAN_LX] = d.left
                    rec[RdpSpanAccel.SPAN_RX] = d.right
                    SpanMath.runFillSpan(scene.rdram, hset, d.u, rec, 0)
                }
                continue
            }
            if (d is RectDraw) {
                val tget = { j: Int -> d.tmem[j].toInt() and 0xFF }
                for (row in d.top..d.bottom) {
                    rec[RdpSpanAccel.SPAN_ROW] = row
                    rec[RdpSpanAccel.SPAN_LX] = d.left
                    rec[RdpSpanAccel.SPAN_RX] = d.right
                    rec[RdpSpanAccel.SPAN_UNSCRX] = d.right
                    primInto(rec, d.u)
                    SpanMath.runTexrectSpan(scene.rdram, hget, hset, tget, d.u, rec, 0)
                }
                continue
            }
            d as TriDraw
            val tmem = d.tmem
            val tget = { j: Int -> tmem[j].toInt() and 0xFF }
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
                SpanMath.runSpan(scene.rdram, hget, hset, tget, Luts.tcdiv, d.u, rec, 0)
            }
        }
    }

    private fun accel(scene: SpanScene, draws: List<Any>) {
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

    private fun assertSame(seed: Long) {
        val draws = makeScene(seed)
        val ref = SpanScene()
        reference(ref, draws)
        val acc = SpanScene()
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
        Gpu.register(SpanFakeBackend())
        try {
            for (seed in 0 until 400) assertSame(seed.toLong() * 2654435761L + 1)
        } finally {
            Gpu.unregister()
            GfxPool.reset()
        }
    }
}
