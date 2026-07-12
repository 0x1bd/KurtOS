package gameboy.core

class Registers(initialA: Int = 0x01) {
    var a = initialA
    var f = 0xB0
    var b = 0x00
    var c = 0x13
    var d = 0x00
    var e = 0xD8
    var h = 0x01
    var l = 0x4D
    var sp = 0xFFFE
    var pc = 0x0100

    var bc: Int
        get() = (b shl 8) or c
        set(value) {
            b = (value shr 8) and 0xFF
            c = value and 0xFF
        }

    var de: Int
        get() = (d shl 8) or e
        set(value) {
            d = (value shr 8) and 0xFF
            e = value and 0xFF
        }

    var hl: Int
        get() = (h shl 8) or l
        set(value) {
            h = (value shr 8) and 0xFF
            l = value and 0xFF
        }

    var af: Int
        get() = (a shl 8) or f
        set(value) {
            a = (value shr 8) and 0xFF
            f = value and 0xF0
        }

    val zero: Boolean get() = f and FLAG_Z != 0
    val negative: Boolean get() = f and FLAG_N != 0
    val halfCarry: Boolean get() = f and FLAG_H != 0
    val carry: Boolean get() = f and FLAG_C != 0

    fun setFlags(zero: Boolean, negative: Boolean, halfCarry: Boolean, carry: Boolean) {
        f = 0
        if (zero) f = f or FLAG_Z
        if (negative) f = f or FLAG_N
        if (halfCarry) f = f or FLAG_H
        if (carry) f = f or FLAG_C
    }

    companion object {
        const val FLAG_Z = 0x80
        const val FLAG_N = 0x40
        const val FLAG_H = 0x20
        const val FLAG_C = 0x10
    }
}
