package n64.core

const val SF_ZERO = 0
const val SF_NORMAL = 1
const val SF_INF = 2
const val SF_NAN = 3

class SoftFloat {
    var flags = 0
    var tiny = false
    var invalidRange = false

    private var aClass = 0
    private var aSign = 0
    private var aExp = 0
    private var aSig = 0L

    private var bClass = 0
    private var bSign = 0
    private var bExp = 0
    private var bSig = 0L

    private var rSign = 0
    private var rExp = 0
    private var rSig = 0L
    private var rSticky = false

    private fun reset() {
        flags = 0
        tiny = false
        invalidRange = false
    }

    private fun unpackSingle(bits: Int, second: Boolean) {
        val sign = (bits ushr 31) and 1
        val field = (bits ushr 23) and 0xFF
        val frac = bits and 0x7FFFFF

        var kind = SF_NORMAL
        var sig = 0L
        var exp = 0

        if (field == 0xFF) {
            kind = if (frac == 0) SF_INF else SF_NAN
        } else if (field == 0) {
            if (frac == 0) {
                kind = SF_ZERO
            } else {
                val shift = leadingZeros64(frac.toLong())
                sig = frac.toLong() shl shift
                exp = -126 - (shift - 40)
            }
        } else {
            sig = (0x800000L or frac.toLong()) shl 40
            exp = field - 127
        }

        store(second, kind, sign, exp, sig)
    }

    private fun unpackDouble(bits: Long, second: Boolean) {
        val sign = ((bits ushr 63) and 1L).toInt()
        val field = ((bits ushr 52) and 0x7FF).toInt()
        val frac = bits and 0xFFFFFFFFFFFFFL

        var kind = SF_NORMAL
        var sig = 0L
        var exp = 0

        if (field == 0x7FF) {
            kind = if (frac == 0L) SF_INF else SF_NAN
        } else if (field == 0) {
            if (frac == 0L) {
                kind = SF_ZERO
            } else {
                val shift = leadingZeros64(frac)
                sig = frac shl shift
                exp = -1022 - (shift - 11)
            }
        } else {
            sig = (0x10000000000000L or frac) shl 11
            exp = field - 1023
        }

        store(second, kind, sign, exp, sig)
    }

    private fun store(second: Boolean, kind: Int, sign: Int, exp: Int, sig: Long) {
        if (second) {
            bClass = kind; bSign = sign; bExp = exp; bSig = sig
        } else {
            aClass = kind; aSign = sign; aExp = exp; aSig = sig
        }
    }

    private fun packSingle(mode: Int): Int {
        val bits = round(mode, 24, 127, -126)
        if (bits == PACK_TINY) return rSign shl 31
        return (rSign shl 31) or bits.toInt()
    }

    private fun packDouble(mode: Int): Long {
        val bits = round(mode, 53, 1023, -1022)
        if (bits == PACK_TINY) return rSign.toLong() shl 63
        return (rSign.toLong() shl 63) or bits
    }

    private fun round(mode: Int, precision: Int, maxExp: Int, minExp: Int): Long {
        val shift = 64 - precision
        var keep = rSig ushr shift
        val rem = rSig and ((1L shl shift) - 1)
        val half = 1L shl (shift - 1)
        var exp = rExp

        val inexact = rem != 0L || rSticky

        val increment = when (mode) {
            0 -> when {
                unsignedGreater(rem, half) -> true
                rem == half -> rSticky || (keep and 1L) != 0L
                else -> false
            }

            1 -> false
            2 -> inexact && rSign == 0
            else -> inexact && rSign == 1
        }

        if (increment) {
            keep++
            if (keep == (1L shl precision)) {
                keep = keep ushr 1
                exp++
            }
        }

        if (exp > maxExp) {
            flags = flags or FP_OVERFLOW or FP_INEXACT
            val toInfinity = when (mode) {
                0 -> true
                1 -> false
                2 -> rSign == 0
                else -> rSign == 1
            }
            return if (toInfinity) {
                (2L * maxExp + 1) shl (precision - 1)
            } else {
                ((2L * maxExp) shl (precision - 1)) or ((1L shl (precision - 1)) - 1)
            }
        }

        if (exp < minExp) {
            tiny = true
            flags = flags or FP_UNDERFLOW or FP_INEXACT
            return PACK_TINY
        }

        if (inexact) flags = flags or FP_INEXACT

        return ((exp + maxExp).toLong() shl (precision - 1)) or (keep and ((1L shl (precision - 1)) - 1))
    }

    private fun addMagnitudes(sameSign: Boolean) {
        var expA = aExp
        var expB = bExp
        var sigA = aSig
        var sigB = bSig

        if (expA < expB || (expA == expB && unsignedLess(sigA, sigB))) {
            val exp = expA; expA = expB; expB = exp
            val sig = sigA; sigA = sigB; sigB = sig
            rSign = bSign
        } else {
            rSign = aSign
        }

        var sticky = false
        val distance = expA - expB

        if (distance >= 64) {
            sticky = sigB != 0L
            sigB = 0L
        } else if (distance > 0) {
            sticky = (sigB and ((1L shl distance) - 1)) != 0L
            sigB = sigB ushr distance
        }

        rExp = expA

        if (sameSign) {
            var sum = sigA + sigB
            if (unsignedLess(sum, sigA)) {
                sticky = sticky || (sum and 1L) != 0L
                sum = (sum ushr 1) or Long.MIN_VALUE
                rExp++
            }
            rSig = sum
            rSticky = sticky
        } else {
            var diff = sigA - sigB
            if (sticky) diff--
            if (diff == 0L) {
                rSig = 0L
                rSticky = sticky
                return
            }
            val shift = leadingZeros64(diff)
            rSig = diff shl shift
            rExp -= shift
            rSticky = sticky
        }
    }

    private fun mulCore() {
        val high = unsignedMultiplyHigh(aSig, bSig)
        val low = aSig * bSig

        rSign = aSign xor bSign

        if (high < 0) {
            rSig = high
            rSticky = low != 0L
            rExp = aExp + bExp + 1
        } else {
            rSig = (high shl 1) or (low ushr 63)
            rSticky = (low shl 1) != 0L
            rExp = aExp + bExp
        }
    }

    private fun divCore() {
        var numerator = aSig
        var exp = aExp

        if (!unsignedLess(numerator, bSig)) {
            numerator = numerator ushr 1
            exp++
        }

        var remainder = numerator
        var quotient = 0L

        for (i in 0 until 64) {
            val carry = remainder < 0
            remainder = remainder shl 1
            quotient = quotient shl 1
            if (carry || !unsignedLess(remainder, bSig)) {
                remainder -= bSig
                quotient = quotient or 1L
            }
        }

        rSign = aSign xor bSign
        rExp = exp - bExp - 1
        rSig = quotient
        rSticky = remainder != 0L
    }

    private fun sqrtCore() {
        var high: Long
        var low: Long
        val exp: Int

        if ((aExp and 1) == 0) {
            high = aSig ushr 1
            low = aSig shl 63
            exp = aExp / 2
        } else {
            high = aSig
            low = 0L
            exp = (aExp - 1) / 2
        }

        var root = 0L
        var remHigh = 0L
        var remLow = 0L

        for (i in 0 until 64) {
            val digits = (high ushr 62) and 3L
            high = (high shl 2) or (low ushr 62)
            low = low shl 2

            remHigh = (remHigh shl 2) or (remLow ushr 62)
            remLow = (remLow shl 2) or digits

            val trialHigh = root ushr 62
            val trialLow = (root shl 2) or 1L
            root = root shl 1

            if (unsignedGreater(remHigh, trialHigh) ||
                (remHigh == trialHigh && unsignedGreaterOrEqual(remLow, trialLow))
            ) {
                val borrow = unsignedLess(remLow, trialLow)
                remLow -= trialLow
                remHigh -= trialHigh + (if (borrow) 1L else 0L)
                root = root or 1L
            }
        }

        rSign = 0
        rExp = exp
        rSig = root
        rSticky = (remHigh or remLow) != 0L
    }

    fun addSingle(a: Int, b: Int, subtract: Boolean, mode: Int): Int {
        reset()
        unpackSingle(a, false)
        unpackSingle(b, true)
        if (subtract) bSign = bSign xor 1

        if (aClass == SF_INF || bClass == SF_INF) {
            if (aClass == SF_INF && bClass == SF_INF && aSign != bSign) {
                flags = flags or FP_INVALID
                return QUIET_SINGLE
            }
            return infinitySingle(if (aClass == SF_INF) aSign else bSign)
        }

        if (aClass == SF_ZERO && bClass == SF_ZERO) {
            return (if (aSign == bSign) aSign else if (mode == 3) 1 else 0) shl 31
        }

        if (aClass == SF_ZERO) {
            rSign = bSign; rExp = bExp; rSig = bSig; rSticky = false
            return packSingle(mode)
        }

        if (bClass == SF_ZERO) {
            rSign = aSign; rExp = aExp; rSig = aSig; rSticky = false
            return packSingle(mode)
        }

        addMagnitudes(aSign == bSign)
        if (rSig == 0L && !rSticky) return (if (mode == 3) 1 else 0) shl 31
        return packSingle(mode)
    }

    fun addDouble(a: Long, b: Long, subtract: Boolean, mode: Int): Long {
        reset()
        unpackDouble(a, false)
        unpackDouble(b, true)
        if (subtract) bSign = bSign xor 1

        if (aClass == SF_INF || bClass == SF_INF) {
            if (aClass == SF_INF && bClass == SF_INF && aSign != bSign) {
                flags = flags or FP_INVALID
                return QUIET_DOUBLE
            }
            return infinityDouble(if (aClass == SF_INF) aSign else bSign)
        }

        if (aClass == SF_ZERO && bClass == SF_ZERO) {
            return (if (aSign == bSign) aSign else if (mode == 3) 1 else 0).toLong() shl 63
        }

        if (aClass == SF_ZERO) {
            rSign = bSign; rExp = bExp; rSig = bSig; rSticky = false
            return packDouble(mode)
        }

        if (bClass == SF_ZERO) {
            rSign = aSign; rExp = aExp; rSig = aSig; rSticky = false
            return packDouble(mode)
        }

        addMagnitudes(aSign == bSign)
        if (rSig == 0L && !rSticky) return (if (mode == 3) 1L else 0L) shl 63
        return packDouble(mode)
    }

    fun mulSingle(a: Int, b: Int, mode: Int): Int {
        reset()
        unpackSingle(a, false)
        unpackSingle(b, true)

        if (aClass == SF_INF || bClass == SF_INF) {
            if (aClass == SF_ZERO || bClass == SF_ZERO) {
                flags = flags or FP_INVALID
                return QUIET_SINGLE
            }
            return infinitySingle(aSign xor bSign)
        }

        if (aClass == SF_ZERO || bClass == SF_ZERO) return (aSign xor bSign) shl 31

        mulCore()
        return packSingle(mode)
    }

    fun mulDouble(a: Long, b: Long, mode: Int): Long {
        reset()
        unpackDouble(a, false)
        unpackDouble(b, true)

        if (aClass == SF_INF || bClass == SF_INF) {
            if (aClass == SF_ZERO || bClass == SF_ZERO) {
                flags = flags or FP_INVALID
                return QUIET_DOUBLE
            }
            return infinityDouble(aSign xor bSign)
        }

        if (aClass == SF_ZERO || bClass == SF_ZERO) return (aSign xor bSign).toLong() shl 63

        mulCore()
        return packDouble(mode)
    }

    fun divSingle(a: Int, b: Int, mode: Int): Int {
        reset()
        unpackSingle(a, false)
        unpackSingle(b, true)

        val sign = aSign xor bSign

        if (aClass == SF_INF) {
            if (bClass == SF_INF) {
                flags = flags or FP_INVALID
                return QUIET_SINGLE
            }
            return infinitySingle(sign)
        }

        if (bClass == SF_INF) return sign shl 31

        if (bClass == SF_ZERO) {
            if (aClass == SF_ZERO) {
                flags = flags or FP_INVALID
                return QUIET_SINGLE
            }
            flags = flags or FP_DIVZERO
            return infinitySingle(sign)
        }

        if (aClass == SF_ZERO) return sign shl 31

        divCore()
        return packSingle(mode)
    }

    fun divDouble(a: Long, b: Long, mode: Int): Long {
        reset()
        unpackDouble(a, false)
        unpackDouble(b, true)

        val sign = aSign xor bSign

        if (aClass == SF_INF) {
            if (bClass == SF_INF) {
                flags = flags or FP_INVALID
                return QUIET_DOUBLE
            }
            return infinityDouble(sign)
        }

        if (bClass == SF_INF) return sign.toLong() shl 63

        if (bClass == SF_ZERO) {
            if (aClass == SF_ZERO) {
                flags = flags or FP_INVALID
                return QUIET_DOUBLE
            }
            flags = flags or FP_DIVZERO
            return infinityDouble(sign)
        }

        if (aClass == SF_ZERO) return sign.toLong() shl 63

        divCore()
        return packDouble(mode)
    }

    fun sqrtSingle(a: Int, mode: Int): Int {
        reset()
        unpackSingle(a, false)

        if (aClass == SF_ZERO) return aSign shl 31

        if (aSign == 1) {
            flags = flags or FP_INVALID
            return QUIET_SINGLE
        }

        if (aClass == SF_INF) return infinitySingle(0)

        sqrtCore()
        return packSingle(mode)
    }

    fun sqrtDouble(a: Long, mode: Int): Long {
        reset()
        unpackDouble(a, false)

        if (aClass == SF_ZERO) return aSign.toLong() shl 63

        if (aSign == 1) {
            flags = flags or FP_INVALID
            return QUIET_DOUBLE
        }

        if (aClass == SF_INF) return infinityDouble(0)

        sqrtCore()
        return packDouble(mode)
    }

    fun singleToDouble(a: Int): Long {
        reset()
        unpackSingle(a, false)

        if (aClass == SF_ZERO) return aSign.toLong() shl 63
        if (aClass == SF_INF) return infinityDouble(aSign)

        rSign = aSign; rExp = aExp; rSig = aSig; rSticky = false
        return packDouble(0)
    }

    fun doubleToSingle(a: Long, mode: Int): Int {
        reset()
        unpackDouble(a, false)

        if (aClass == SF_ZERO) return aSign shl 31
        if (aClass == SF_INF) return infinitySingle(aSign)

        rSign = aSign; rExp = aExp; rSig = aSig; rSticky = false
        return packSingle(mode)
    }

    fun fixedToSingle(value: Long, mode: Int): Int {
        reset()
        if (value == 0L) return 0

        rSign = if (value < 0) 1 else 0
        rSticky = false

        if (value == Long.MIN_VALUE) {
            rSig = Long.MIN_VALUE
            rExp = 63
        } else {
            val magnitude = if (value < 0) -value else value
            val shift = leadingZeros64(magnitude)
            rSig = magnitude shl shift
            rExp = 63 - shift
        }

        return packSingle(mode)
    }

    fun fixedToDouble(value: Long, mode: Int): Long {
        reset()
        if (value == 0L) return 0L

        rSign = if (value < 0) 1 else 0
        rSticky = false

        if (value == Long.MIN_VALUE) {
            rSig = Long.MIN_VALUE
            rExp = 63
        } else {
            val magnitude = if (value < 0) -value else value
            val shift = leadingZeros64(magnitude)
            rSig = magnitude shl shift
            rExp = 63 - shift
        }

        return packDouble(mode)
    }

    fun toFixed(bits: Long, isDouble: Boolean, mode: Int, wide: Boolean): Long {
        reset()
        if (isDouble) unpackDouble(bits, false) else unpackSingle(bits.toInt(), false)

        if (aClass == SF_NAN || aClass == SF_INF) {
            invalidRange = true
            return 0L
        }

        if (aClass == SF_ZERO) return 0L

        val width = if (wide) 52 else 31
        if (aExp > width) {
            invalidRange = true
            return 0L
        }

        var magnitude: Long
        val inexact: Boolean

        if (aExp < 0) {
            inexact = true
            val roundUp = when (mode) {
                0 -> aExp == -1 && unsignedGreater(aSig, Long.MIN_VALUE)
                1 -> false
                2 -> aSign == 0
                else -> aSign == 1
            }
            magnitude = if (roundUp) 1L else 0L
        } else {
            val shift = 63 - aExp
            magnitude = aSig ushr shift
            val rem = if (shift == 0) 0L else aSig and ((1L shl shift) - 1)
            val half = if (shift == 0) 0L else 1L shl (shift - 1)
            inexact = rem != 0L

            val increment = when (mode) {
                0 -> when {
                    unsignedGreater(rem, half) -> true
                    rem == half && shift != 0 -> (magnitude and 1L) != 0L
                    else -> false
                }

                1 -> false
                2 -> inexact && aSign == 0
                else -> inexact && aSign == 1
            }

            if (increment) magnitude++
        }

        val limit = if (wide) Long.MIN_VALUE else 0x80000000L
        val outOfRange = if (aSign == 0) {
            unsignedGreaterOrEqual(magnitude, limit)
        } else {
            unsignedGreater(magnitude, limit)
        }

        if (outOfRange) {
            invalidRange = true
            return 0L
        }

        if (inexact) flags = flags or FP_INEXACT
        return if (aSign == 1) -magnitude else magnitude
    }

    private fun infinitySingle(sign: Int): Int = (sign shl 31) or 0x7F800000

    private fun infinityDouble(sign: Int): Long = (sign.toLong() shl 63) or 0x7FF0000000000000L

    companion object {
        private const val PACK_TINY = Long.MIN_VALUE
    }
}

private fun leadingZeros64(value: Long): Int {
    if (value == 0L) return 64
    var count = 0
    var current = value
    while (current > 0) {
        current = current shl 1
        count++
    }
    return count
}

private fun unsignedLess(a: Long, b: Long): Boolean = (a xor Long.MIN_VALUE) < (b xor Long.MIN_VALUE)

private fun unsignedGreater(a: Long, b: Long): Boolean = (a xor Long.MIN_VALUE) > (b xor Long.MIN_VALUE)

private fun unsignedGreaterOrEqual(a: Long, b: Long): Boolean = (a xor Long.MIN_VALUE) >= (b xor Long.MIN_VALUE)

private fun unsignedMultiplyHigh(a: Long, b: Long): Long {
    val aLow = a and 0xFFFFFFFFL
    val aHigh = a ushr 32
    val bLow = b and 0xFFFFFFFFL
    val bHigh = b ushr 32

    val low = aLow * bLow
    val middleOne = aHigh * bLow + (low ushr 32)
    val middleTwo = aLow * bHigh + (middleOne and 0xFFFFFFFFL)

    return aHigh * bHigh + (middleOne ushr 32) + (middleTwo ushr 32)
}
