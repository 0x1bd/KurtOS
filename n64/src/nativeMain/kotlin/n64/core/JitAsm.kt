package n64.core

const val RAX = 0
const val RCX = 1
const val RDX = 2
const val RBX = 3
const val RSP_REG = 4
const val RBP = 5
const val RSI = 6
const val RDI = 7
const val R8 = 8
const val R9 = 9
const val R10 = 10
const val R11 = 11
const val R12 = 12
const val R13 = 13
const val R14 = 14
const val R15 = 15

const val CC_O = 0
const val CC_B = 2
const val CC_AE = 3
const val CC_E = 4
const val CC_NE = 5
const val CC_BE = 6
const val CC_A = 7
const val CC_S = 8
const val CC_NS = 9
const val CC_L = 12
const val CC_GE = 13
const val CC_LE = 14
const val CC_G = 15

const val ALU_ADD = 0
const val ALU_OR = 1
const val ALU_AND = 4
const val ALU_SUB = 5
const val ALU_XOR = 6
const val ALU_CMP = 7

const val SH_SHL = 4
const val SH_SHR = 5
const val SH_SAR = 7

class JitAsm {
    var buf = ByteArray(16384)
    var len = 0

    fun reset() {
        len = 0
    }

    fun byte(v: Int) {
        if (len == buf.size) buf = buf.copyOf(buf.size * 2)
        buf[len++] = v.toByte()
    }

    fun int32(v: Int) {
        byte(v)
        byte(v ushr 8)
        byte(v ushr 16)
        byte(v ushr 24)
    }

    fun int64(v: Long) {
        int32(v.toInt())
        int32((v ushr 32).toInt())
    }

    private fun rex(w: Int, reg: Int, index: Int, base: Int, force: Boolean = false) {
        val v = 0x40 or (w shl 3) or (((reg ushr 3) and 1) shl 2) or
            (((index ushr 3) and 1) shl 1) or ((base ushr 3) and 1)
        if (v != 0x40 || force) byte(v)
    }

    private fun modrmReg(reg: Int, rm: Int) {
        byte(0xC0 or ((reg and 7) shl 3) or (rm and 7))
    }

    private fun modrmMem(reg: Int, base: Int, disp: Int) {
        val r = reg and 7
        val b = base and 7
        val mod = if (disp == 0 && b != 5) 0 else if (disp in -128..127) 1 else 2
        if (b == 4) {
            byte((mod shl 6) or (r shl 3) or 4)
            byte(0x24)
        } else {
            byte((mod shl 6) or (r shl 3) or b)
        }
        if (mod == 1) byte(disp) else if (mod == 2) int32(disp)
    }

    private fun modrmSib(reg: Int, base: Int, index: Int, scale: Int, disp: Int) {
        val b = base and 7
        val mod = if (disp == 0 && b != 5) 0 else if (disp in -128..127) 1 else 2
        byte((mod shl 6) or ((reg and 7) shl 3) or 4)
        val s = when (scale) {
            1 -> 0
            2 -> 1
            4 -> 2
            else -> 3
        }
        byte((s shl 6) or ((index and 7) shl 3) or b)
        if (mod == 1) byte(disp) else if (mod == 2) int32(disp)
    }

    fun movRI64(reg: Int, imm: Long) {
        rex(1, 0, 0, reg)
        byte(0xB8 or (reg and 7))
        int64(imm)
    }

    fun movRI32sx(reg: Int, imm: Int) {
        rex(1, 0, 0, reg)
        byte(0xC7)
        modrmReg(0, reg)
        int32(imm)
    }

    fun movRI32(reg: Int, imm: Int) {
        rex(0, 0, 0, reg)
        byte(0xB8 or (reg and 7))
        int32(imm)
    }

    fun movMI(base: Int, disp: Int, imm: Int) {
        rex(1, 0, 0, base)
        byte(0xC7)
        modrmMem(0, base, disp)
        int32(imm)
    }

    fun movRR(dst: Int, src: Int, w: Int) {
        rex(w, src, 0, dst)
        byte(0x89)
        modrmReg(src, dst)
    }

    fun movRM(reg: Int, base: Int, disp: Int, w: Int) {
        rex(w, reg, 0, base)
        byte(0x8B)
        modrmMem(reg, base, disp)
    }

    fun movMR(base: Int, disp: Int, reg: Int, w: Int) {
        rex(w, reg, 0, base)
        byte(0x89)
        modrmMem(reg, base, disp)
    }

    fun movRMIndexed(reg: Int, base: Int, index: Int, scale: Int, disp: Int, w: Int) {
        rex(w, reg, index, base)
        byte(0x8B)
        modrmSib(reg, base, index, scale, disp)
    }

    fun movMRIndexed(base: Int, index: Int, scale: Int, disp: Int, reg: Int, w: Int) {
        rex(w, reg, index, base)
        byte(0x89)
        modrmSib(reg, base, index, scale, disp)
    }

    fun movMR16Indexed(base: Int, index: Int, reg: Int) {
        byte(0x66)
        rex(0, reg, index, base)
        byte(0x89)
        modrmSib(reg, base, index, 1, 0)
    }

    fun movMR8Indexed(base: Int, index: Int, reg: Int) {
        rex(0, reg, index, base, force = reg in 4..7)
        byte(0x88)
        modrmSib(reg, base, index, 1, 0)
    }

    fun movsxdRR(dst: Int, src: Int) {
        rex(1, dst, 0, src)
        byte(0x63)
        modrmReg(dst, src)
    }

    fun movsx8RR(dst: Int, src: Int) {
        rex(1, dst, 0, src, force = src in 4..7)
        byte(0x0F)
        byte(0xBE)
        modrmReg(dst, src)
    }

    fun movsx16RR(dst: Int, src: Int) {
        rex(1, dst, 0, src)
        byte(0x0F)
        byte(0xBF)
        modrmReg(dst, src)
    }

    fun movsx8RMIndexed(dst: Int, base: Int, index: Int) {
        rex(1, dst, index, base)
        byte(0x0F)
        byte(0xBE)
        modrmSib(dst, base, index, 1, 0)
    }

    fun movzx8RMIndexed(dst: Int, base: Int, index: Int) {
        rex(0, dst, index, base)
        byte(0x0F)
        byte(0xB6)
        modrmSib(dst, base, index, 1, 0)
    }

    fun movzx16RMIndexed(dst: Int, base: Int, index: Int) {
        rex(0, dst, index, base)
        byte(0x0F)
        byte(0xB7)
        modrmSib(dst, base, index, 1, 0)
    }

    fun movzx8RR(dst: Int, src: Int) {
        rex(0, dst, 0, src, force = src in 4..7)
        byte(0x0F)
        byte(0xB6)
        modrmReg(dst, src)
    }

    fun rol16I(reg: Int, imm: Int) {
        byte(0x66)
        rex(0, 0, 0, reg)
        byte(0xC1)
        modrmReg(0, reg)
        byte(imm)
    }

    fun aluRR(ext: Int, dst: Int, src: Int, w: Int) {
        rex(w, src, 0, dst)
        byte((ext shl 3) or 0x01)
        modrmReg(src, dst)
    }

    fun aluRM(ext: Int, reg: Int, base: Int, disp: Int, w: Int) {
        rex(w, reg, 0, base)
        byte((ext shl 3) or 0x03)
        modrmMem(reg, base, disp)
    }

    fun aluRI(ext: Int, reg: Int, imm: Int, w: Int) {
        rex(w, 0, 0, reg)
        if (imm in -128..127) {
            byte(0x83)
            modrmReg(ext, reg)
            byte(imm)
        } else {
            byte(0x81)
            modrmReg(ext, reg)
            int32(imm)
        }
    }

    fun aluMI(ext: Int, base: Int, disp: Int, imm: Int, w: Int) {
        rex(w, 0, 0, base)
        if (imm in -128..127) {
            byte(0x83)
            modrmMem(ext, base, disp)
            byte(imm)
        } else {
            byte(0x81)
            modrmMem(ext, base, disp)
            int32(imm)
        }
    }

    fun shiftRI(ext: Int, reg: Int, imm: Int, w: Int) {
        rex(w, 0, 0, reg)
        byte(0xC1)
        modrmReg(ext, reg)
        byte(imm)
    }

    fun shiftRC(ext: Int, reg: Int, w: Int) {
        rex(w, 0, 0, reg)
        byte(0xD3)
        modrmReg(ext, reg)
    }

    fun testRI(reg: Int, imm: Int) {
        rex(0, 0, 0, reg)
        byte(0xF7)
        modrmReg(0, reg)
        int32(imm)
    }

    fun cmpMI8Disp(base: Int, disp: Int, imm: Int) {
        rex(0, 0, 0, base)
        byte(0x80)
        modrmMem(7, base, disp)
        byte(imm)
    }

    fun testRR(a: Int, b: Int, w: Int) {
        rex(w, b, 0, a)
        byte(0x85)
        modrmReg(b, a)
    }

    fun test8RR(a: Int, b: Int) {
        rex(0, b, 0, a, force = a in 4..7 || b in 4..7)
        byte(0x84)
        modrmReg(b, a)
    }

    fun cmpMI8(base: Int, index: Int, imm: Int) {
        rex(0, 0, index, base)
        byte(0x80)
        modrmSib(7, base, index, 1, 0)
        byte(imm)
    }

    fun imulRR(dst: Int, src: Int) {
        rex(1, dst, 0, src)
        byte(0x0F)
        byte(0xAF)
        modrmReg(dst, src)
    }

    fun onePop(ext: Int, reg: Int, w: Int) {
        rex(w, 0, 0, reg)
        byte(0xF7)
        modrmReg(ext, reg)
    }

    fun onePopM(ext: Int, base: Int, disp: Int, w: Int) {
        rex(w, 0, 0, base)
        byte(0xF7)
        modrmMem(ext, base, disp)
    }

    fun cdq() = byte(0x99)

    fun cqo() {
        byte(0x48)
        byte(0x99)
    }

    fun setcc(cc: Int, reg: Int) {
        rex(0, 0, 0, reg, force = reg in 4..7)
        byte(0x0F)
        byte(0x90 or cc)
        modrmReg(0, reg)
    }

    fun jcc(cc: Int): Int {
        byte(0x0F)
        byte(0x80 or cc)
        val at = len
        int32(0)
        return at
    }

    fun jmp(): Int {
        byte(0xE9)
        val at = len
        int32(0)
        return at
    }

    fun patch(at: Int) {
        val rel = len - (at + 4)
        buf[at] = rel.toByte()
        buf[at + 1] = (rel ushr 8).toByte()
        buf[at + 2] = (rel ushr 16).toByte()
        buf[at + 3] = (rel ushr 24).toByte()
    }

    fun patchTo(at: Int, target: Int) {
        val rel = target - (at + 4)
        buf[at] = rel.toByte()
        buf[at + 1] = (rel ushr 8).toByte()
        buf[at + 2] = (rel ushr 16).toByte()
        buf[at + 3] = (rel ushr 24).toByte()
    }

    fun leaRip(reg: Int): Int {
        rex(1, reg, 0, 0)
        byte(0x8D)
        byte(0x05 or ((reg and 7) shl 3))
        val at = len
        int32(0)
        return at
    }

    fun jmpMem(base: Int, disp: Int) {
        rex(0, 0, 0, base)
        byte(0xFF)
        modrmMem(4, base, disp)
    }

    fun jmpReg(reg: Int) {
        rex(0, 0, 0, reg)
        byte(0xFF)
        modrmReg(4, reg)
    }

    fun callReg(reg: Int) {
        rex(0, 0, 0, reg)
        byte(0xFF)
        modrmReg(2, reg)
    }

    fun push(reg: Int) {
        rex(0, 0, 0, reg)
        byte(0x50 or (reg and 7))
    }

    fun pop(reg: Int) {
        rex(0, 0, 0, reg)
        byte(0x58 or (reg and 7))
    }

    fun ret() = byte(0xC3)
}
