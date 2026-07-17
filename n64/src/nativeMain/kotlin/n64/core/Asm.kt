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
const val CC_P = 10
const val CC_NP = 11
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

class Asm {
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

    fun testMI(base: Int, disp: Int, imm: Int) {
        rex(0, 0, 0, base)
        byte(0xF7)
        modrmMem(0, base, disp)
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

    private fun sse66RR(op: Int, dst: Int, src: Int) {
        byte(0x66)
        rex(0, dst, 0, src)
        byte(0x0F)
        byte(op)
        modrmReg(dst, src)
    }

    private fun sse66RR38(op: Int, dst: Int, src: Int) {
        byte(0x66)
        rex(0, dst, 0, src)
        byte(0x0F)
        byte(0x38)
        byte(op)
        modrmReg(dst, src)
    }

    private fun sseMem(prefix: Int, has38: Boolean, op: Int, xmm: Int, base: Int, disp: Int) {
        if (prefix != 0) byte(prefix)
        rex(0, xmm, 0, base)
        byte(0x0F)
        if (has38) byte(0x38)
        byte(op)
        modrmMem(xmm, base, disp)
    }

    private fun sseRR(prefix: Int, op: Int, dst: Int, src: Int) {
        if (prefix != 0) byte(prefix)
        rex(0, dst, 0, src)
        byte(0x0F)
        byte(op)
        modrmReg(dst, src)
    }

    fun movssLoad(xmm: Int, base: Int, disp: Int) = sseMem(0xF3, false, 0x10, xmm, base, disp)
    fun movsdLoad(xmm: Int, base: Int, disp: Int) = sseMem(0xF2, false, 0x10, xmm, base, disp)

    fun addssMem(xmm: Int, base: Int, disp: Int) = sseMem(0xF3, false, 0x58, xmm, base, disp)
    fun subssMem(xmm: Int, base: Int, disp: Int) = sseMem(0xF3, false, 0x5C, xmm, base, disp)
    fun mulssMem(xmm: Int, base: Int, disp: Int) = sseMem(0xF3, false, 0x59, xmm, base, disp)
    fun divssMem(xmm: Int, base: Int, disp: Int) = sseMem(0xF3, false, 0x5E, xmm, base, disp)
    fun addsdMem(xmm: Int, base: Int, disp: Int) = sseMem(0xF2, false, 0x58, xmm, base, disp)
    fun subsdMem(xmm: Int, base: Int, disp: Int) = sseMem(0xF2, false, 0x5C, xmm, base, disp)
    fun mulsdMem(xmm: Int, base: Int, disp: Int) = sseMem(0xF2, false, 0x59, xmm, base, disp)
    fun divsdMem(xmm: Int, base: Int, disp: Int) = sseMem(0xF2, false, 0x5E, xmm, base, disp)

    fun sqrtss(dst: Int, src: Int) = sseRR(0xF3, 0x51, dst, src)
    fun sqrtsd(dst: Int, src: Int) = sseRR(0xF2, 0x51, dst, src)

    fun ucomiss(dst: Int, src: Int) = sseRR(0, 0x2E, dst, src)
    fun ucomisd(dst: Int, src: Int) = sseRR(0x66, 0x2E, dst, src)
    fun ucomissMem(xmm: Int, base: Int, disp: Int) = sseMem(0, false, 0x2E, xmm, base, disp)
    fun ucomisdMem(xmm: Int, base: Int, disp: Int) = sseMem(0x66, false, 0x2E, xmm, base, disp)

    fun cvtss2sd(dst: Int, src: Int) = sseRR(0xF3, 0x5A, dst, src)
    fun cvtsd2ss(dst: Int, src: Int) = sseRR(0xF2, 0x5A, dst, src)
    fun cvtsi2ssMem(xmm: Int, base: Int, disp: Int) = sseMem(0xF3, false, 0x2A, xmm, base, disp)
    fun cvtsi2sdMem(xmm: Int, base: Int, disp: Int) = sseMem(0xF2, false, 0x2A, xmm, base, disp)

    fun cvttss2si(gpr: Int, xmm: Int) = sseRR(0xF3, 0x2C, gpr, xmm)
    fun cvtss2si(gpr: Int, xmm: Int) = sseRR(0xF3, 0x2D, gpr, xmm)
    fun cvttsd2si(gpr: Int, xmm: Int) = sseRR(0xF2, 0x2C, gpr, xmm)
    fun cvtsd2si(gpr: Int, xmm: Int) = sseRR(0xF2, 0x2D, gpr, xmm)

    fun movdRX(gpr: Int, xmm: Int) {
        byte(0x66)
        rex(0, xmm, 0, gpr)
        byte(0x0F)
        byte(0x7E)
        modrmReg(xmm, gpr)
    }

    fun movqRX(gpr: Int, xmm: Int) {
        byte(0x66)
        rex(1, xmm, 0, gpr, force = true)
        byte(0x0F)
        byte(0x7E)
        modrmReg(xmm, gpr)
    }

    fun stmxcsr(base: Int, disp: Int) {
        rex(0, 3, 0, base)
        byte(0x0F)
        byte(0xAE)
        modrmMem(3, base, disp)
    }

    fun ldmxcsr(base: Int, disp: Int) {
        rex(0, 2, 0, base)
        byte(0x0F)
        byte(0xAE)
        modrmMem(2, base, disp)
    }

    fun movdquLoad(xmm: Int, base: Int, disp: Int) = sseMem(0xF3, false, 0x6F, xmm, base, disp)
    fun movdquStore(base: Int, disp: Int, xmm: Int) = sseMem(0xF3, false, 0x7F, xmm, base, disp)
    fun movqLoad(xmm: Int, base: Int, disp: Int) = sseMem(0xF3, false, 0x7E, xmm, base, disp)
    fun movqStore(base: Int, disp: Int, xmm: Int) = sseMem(0x66, false, 0xD6, xmm, base, disp)
    fun movdLoad(xmm: Int, base: Int, disp: Int) = sseMem(0x66, false, 0x6E, xmm, base, disp)
    fun movdStore(base: Int, disp: Int, xmm: Int) = sseMem(0x66, false, 0x7E, xmm, base, disp)

    fun bswap(reg: Int) {
        rex(0, 0, 0, reg)
        byte(0x0F)
        byte(0xC8 or (reg and 7))
    }
    fun pshufbMem(xmm: Int, base: Int, disp: Int) = sseMem(0x66, true, 0x00, xmm, base, disp)
    fun pshufbRR(dst: Int, src: Int) = sse66RR38(0x00, dst, src)

    fun movdqaRR(dst: Int, src: Int) = sse66RR(0x6F, dst, src)
    fun packusdw(dst: Int, src: Int) = sse66RR38(0x2B, dst, src)
    fun packssdw(dst: Int, src: Int) = sse66RR(0x6B, dst, src)
    fun punpcklwd(dst: Int, src: Int) = sse66RR(0x61, dst, src)
    fun punpckhwd(dst: Int, src: Int) = sse66RR(0x69, dst, src)

    fun pand(dst: Int, src: Int) = sse66RR(0xDB, dst, src)
    fun pandn(dst: Int, src: Int) = sse66RR(0xDF, dst, src)
    fun por(dst: Int, src: Int) = sse66RR(0xEB, dst, src)
    fun pxor(dst: Int, src: Int) = sse66RR(0xEF, dst, src)
    fun pcmpeqd(dst: Int, src: Int) = sse66RR(0x76, dst, src)

    fun paddw(dst: Int, src: Int) = sse66RR(0xFD, dst, src)
    fun psubw(dst: Int, src: Int) = sse66RR(0xF9, dst, src)
    fun paddsw(dst: Int, src: Int) = sse66RR(0xED, dst, src)
    fun psubsw(dst: Int, src: Int) = sse66RR(0xE9, dst, src)
    fun paddusw(dst: Int, src: Int) = sse66RR(0xDD, dst, src)
    fun psubusw(dst: Int, src: Int) = sse66RR(0xD9, dst, src)
    fun pminsw(dst: Int, src: Int) = sse66RR(0xEA, dst, src)
    fun pmaxsw(dst: Int, src: Int) = sse66RR(0xEE, dst, src)

    fun pcmpeqw(dst: Int, src: Int) = sse66RR(0x75, dst, src)
    fun pcmpgtw(dst: Int, src: Int) = sse66RR(0x65, dst, src)

    fun pmullw(dst: Int, src: Int) = sse66RR(0xD5, dst, src)
    fun pmulhw(dst: Int, src: Int) = sse66RR(0xE5, dst, src)
    fun pmulhuw(dst: Int, src: Int) = sse66RR(0xE4, dst, src)

    fun psllwI(xmm: Int, imm: Int) = pshiftI(6, xmm, imm)
    fun psrlwI(xmm: Int, imm: Int) = pshiftI(2, xmm, imm)
    fun psrawI(xmm: Int, imm: Int) = pshiftI(4, xmm, imm)

    private fun pshiftI(ext: Int, xmm: Int, imm: Int) {
        byte(0x66)
        rex(0, 0, 0, xmm)
        byte(0x0F)
        byte(0x71)
        modrmReg(ext, xmm)
        byte(imm)
    }
}
