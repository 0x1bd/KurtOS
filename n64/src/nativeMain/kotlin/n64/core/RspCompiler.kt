package n64.core

const val RSP_PC = 0
const val RSP_STATE = 8
const val RSP_TARGET = 16
const val RSP_INSTRS = 24
const val RSP_SCR1 = 32
const val RSP_DISPATCH = 40
const val RSP_RETURN = 48
const val RSP_LINKSRC = 56
const val RSP_GPR = 64
const val RSP_DMEM = 72
const val RSP_EXEC = 80
const val RSP_VPR = 88
const val RSP_SWIZZLE = 96
const val RSP_ACCL = 104
const val RSP_ACCM = 112
const val RSP_ACCH = 120
const val RSP_VCOL = 128
const val RSP_VCOH = 136
const val RSP_VCCL = 144
const val RSP_VCCH = 152
const val RSP_VCE = 160

const val RSP_MAX_BLOCK = 64

class RspCompiler {
    val asm = Asm()

    private var vbase = 0
    private val exitJumps = ArrayList<Int>()

    fun buildTrampoline(): Int {
        asm.reset()
        asm.push(RBX)
        asm.push(R12)
        asm.push(R13)
        asm.push(R14)
        asm.push(R15)
        asm.movRR(RBX, RDI, 1)
        asm.movRM(R12, RBX, RSP_GPR, 1)
        asm.movRM(R13, RBX, RSP_VPR, 1)
        asm.movRM(R14, RBX, RSP_SWIZZLE, 1)
        asm.movRM(R15, RBX, RSP_ACCL, 1)
        asm.jmpMem(RBX, RSP_DISPATCH)
        val ret = asm.len
        asm.pop(R15)
        asm.pop(R14)
        asm.pop(R13)
        asm.pop(R12)
        asm.pop(RBX)
        asm.ret()
        return ret
    }

    fun compile(vbase: Int, ops: IntArray, count: Int, endsWithBranch: Boolean): Int {
        this.vbase = vbase
        exitJumps.clear()
        asm.reset()

        asm.aluMI(ALU_ADD, RBX, RSP_INSTRS, count, 1)

        val body = if (endsWithBranch) count - 2 else count
        var i = 0
        while (i < body) {
            emitOp(ops[i], i)
            i++
        }

        if (endsWithBranch) {
            emitBranch(ops[count - 2], ops[count - 1], count - 2)
        } else {
            emitStaticExit((vbase + count * 4) and 0xFFC)
        }

        for (at in exitJumps) asm.patch(at)
        asm.jmpMem(RBX, RSP_RETURN)
        return asm.len
    }

    private fun loadG(dst: Int, idx: Int) = asm.movRM(dst, R12, idx * 4, 0)

    private fun storeG(idx: Int, src: Int) {
        if (idx != 0) asm.movMR(R12, idx * 4, src, 0)
    }

    private fun emitStaticExit(pc: Int) {
        asm.movRI32(RAX, pc)
        asm.movMR(RBX, RSP_PC, RAX, 1)
        asm.movMI(RBX, RSP_STATE, STATE_STEP)
        exitJumps.add(asm.jmp())
    }

    private fun callout(op: Int) {
        asm.movRI32(RDI, op)
        asm.movRM(RAX, RBX, RSP_EXEC, 1)
        asm.callReg(RAX)
    }

    private fun emitOp(op: Int, i: Int) {
        val rs = (op ushr 21) and 0x1F
        val rt = (op ushr 16) and 0x1F
        val rd = (op ushr 11) and 0x1F
        val sa = (op ushr 6) and 0x1F
        val imm = op.toShort().toInt()
        val uimm = op and 0xFFFF

        when (op ushr 26) {
            0 -> emitSpecial(op, rs, rt, rd, sa)

            8, 9 -> {
                loadG(RAX, rs)
                if (imm != 0) asm.aluRI(ALU_ADD, RAX, imm, 0)
                storeG(rt, RAX)
            }

            10 -> {
                loadG(RAX, rs)
                asm.aluRI(ALU_CMP, RAX, imm, 0)
                asm.setcc(CC_L, RAX)
                asm.movzx8RR(RAX, RAX)
                storeG(rt, RAX)
            }

            11 -> {
                loadG(RAX, rs)
                asm.aluRI(ALU_CMP, RAX, imm, 0)
                asm.setcc(CC_B, RAX)
                asm.movzx8RR(RAX, RAX)
                storeG(rt, RAX)
            }

            12 -> {
                loadG(RAX, rs)
                asm.aluRI(ALU_AND, RAX, uimm, 0)
                storeG(rt, RAX)
            }

            13 -> {
                loadG(RAX, rs)
                asm.aluRI(ALU_OR, RAX, uimm, 0)
                storeG(rt, RAX)
            }

            14 -> {
                loadG(RAX, rs)
                asm.aluRI(ALU_XOR, RAX, uimm, 0)
                storeG(rt, RAX)
            }

            15 -> {
                asm.movRI32(RAX, uimm shl 16)
                storeG(rt, RAX)
            }

            18 -> if (!emitVector(op)) callout(op)

            32, 33, 35, 36, 37, 39, 40, 41, 43, 50, 58 -> callout(op)
        }
    }

    private fun loadVec(dst: Int, reg: Int) {
        asm.movdquLoad(dst, R13, reg * 32)
        asm.movdquLoad(2, R13, reg * 32 + 16)
        asm.packusdw(dst, 2)
    }

    private fun loadVecSwizzled(dst: Int, reg: Int, element: Int) {
        loadVec(dst, reg)
        asm.movdquLoad(4, R14, element * 16)
        asm.pshufbRR(dst, 4)
    }

    private fun notVec(xmm: Int) {
        asm.pcmpeqd(7, 7)
        asm.pxor(xmm, 7)
    }

    private fun storeVpr(vd: Int, src: Int) {
        asm.pxor(3, 3)
        asm.movdqaRR(2, src)
        asm.punpcklwd(2, 3)
        asm.movdquStore(R13, vd * 32, 2)
        asm.movdqaRR(2, src)
        asm.punpckhwd(2, 3)
        asm.movdquStore(R13, vd * 32 + 16, 2)
    }

    private fun storeAccl(src: Int) {
        asm.pxor(3, 3)
        asm.movdqaRR(2, src)
        asm.punpcklwd(2, 3)
        asm.movdquStore(R15, 0, 2)
        asm.movdqaRR(2, src)
        asm.punpckhwd(2, 3)
        asm.movdquStore(R15, 16, 2)
    }

    private fun zeroAccl() {
        asm.pxor(2, 2)
        asm.movdquStore(R15, 0, 2)
        asm.movdquStore(R15, 16, 2)
    }

    private fun storeCtxArray(ctxSlot: Int, src: Int) {
        asm.movRM(RAX, RBX, ctxSlot, 1)
        asm.pxor(3, 3)
        asm.movdqaRR(2, src)
        asm.punpcklwd(2, 3)
        asm.movdquStore(RAX, 0, 2)
        asm.movdqaRR(2, src)
        asm.punpckhwd(2, 3)
        asm.movdquStore(RAX, 16, 2)
    }

    private fun zeroCtxArray(ctxSlot: Int) {
        asm.movRM(RAX, RBX, ctxSlot, 1)
        asm.pxor(2, 2)
        asm.movdquStore(RAX, 0, 2)
        asm.movdquStore(RAX, 16, 2)
    }

    private fun signMask(dst: Int, value: Int) {
        asm.pxor(3, 3)
        asm.movdqaRR(dst, 3)
        asm.pcmpgtw(dst, value)
    }

    private fun loadAccl(dst: Int) {
        asm.movdquLoad(dst, R15, 0)
        asm.movdquLoad(2, R15, 16)
        asm.packusdw(dst, 2)
    }

    private fun loadCtxArray(dst: Int, ctxSlot: Int) {
        asm.movRM(RAX, RBX, ctxSlot, 1)
        asm.movdquLoad(dst, RAX, 0)
        asm.movdquLoad(2, RAX, 16)
        asm.packusdw(dst, 2)
    }

    private fun ucarry(dst: Int, a: Int, b: Int, bias: Int) {
        asm.movdqaRR(dst, a)
        asm.pxor(dst, bias)
        asm.movdqaRR(3, b)
        asm.pxor(3, bias)
        asm.pcmpgtw(dst, 3)
    }

    private fun add48() {
        asm.pcmpeqw(4, 4)
        asm.psllwI(4, 15)

        loadAccl(0)
        asm.movdqaRR(1, 0)
        asm.paddw(0, 5)
        ucarry(5, 1, 0, 4)
        storeAccl(0)

        loadCtxArray(0, RSP_ACCM)
        asm.movdqaRR(1, 0)
        asm.paddw(0, 6)
        ucarry(6, 1, 0, 4)
        asm.movdqaRR(1, 0)
        asm.psubw(0, 5)
        ucarry(5, 1, 0, 4)
        asm.por(5, 6)
        storeCtxArray(RSP_ACCM, 0)

        loadCtxArray(0, RSP_ACCH)
        asm.paddw(0, 7)
        asm.psubw(0, 5)
        storeCtxArray(RSP_ACCH, 0)
    }

    private fun emitClampMid(dst: Int) {
        loadCtxArray(5, RSP_ACCM)
        loadCtxArray(6, RSP_ACCH)
        asm.movdqaRR(dst, 5)
        asm.punpcklwd(dst, 6)
        asm.movdqaRR(2, 5)
        asm.punpckhwd(2, 6)
        asm.packssdw(dst, 2)
    }

    private fun emitClampLow(dst: Int) {
        loadAccl(0)
        loadCtxArray(1, RSP_ACCM)
        loadCtxArray(5, RSP_ACCH)
        signMask(6, 5)
        signMask(7, 1)
        asm.movdqaRR(2, 5)
        asm.pcmpeqw(2, 6)
        asm.movdqaRR(3, 6)
        asm.pcmpeqw(3, 7)
        asm.pand(2, 3)
        asm.pcmpeqw(7, 7)
        asm.pxor(6, 7)
        asm.pand(0, 2)
        asm.movdqaRR(dst, 2)
        asm.pandn(dst, 6)
        asm.por(dst, 0)
    }

    private fun emitVector(op: Int): Boolean {
        if ((op ushr 25) and 1 == 0) return false
        val function = op and 0x3F
        val element = (op ushr 21) and 0xF
        val vt = (op ushr 16) and 0x1F
        val vs = (op ushr 11) and 0x1F
        val vd = (op ushr 6) and 0x1F

        when (function) {
            0 -> emitVmulf(vs, vt, vd, element, true)
            1 -> emitVmulf(vs, vt, vd, element, false)
            4 -> emitVmudl(vs, vt, vd, element)
            5 -> emitVmudm(vs, vt, vd, element)
            6 -> emitVmudn(vs, vt, vd, element)
            7 -> emitVmudh(vs, vt, vd, element)
            8 -> emitVmacf(vs, vt, vd, element)
            12 -> emitVmadl(vs, vt, vd, element)
            13 -> emitVmadm(vs, vt, vd, element)
            14 -> emitVmadn(vs, vt, vd, element)
            15 -> emitVmadh(vs, vt, vd, element)
            16 -> emitVadd(vs, vt, vd, element)
            17 -> emitVsub(vs, vt, vd, element)
            19 -> emitVabs(vs, vt, vd, element)
            20 -> emitVaddc(vs, vt, vd, element)
            21 -> emitVsubc(vs, vt, vd, element)
            29 -> emitVsar(vd, element)
            32, 33, 34, 35 -> emitCompare(function, vs, vt, vd, element)
            39 -> emitVmrg(vs, vt, vd, element)
            40, 41, 42, 43, 44, 45 -> emitLogic(function - 40, vs, vt, vd, element)
            else -> return false
        }
        return true
    }

    private fun emitVaddc(vs: Int, vt: Int, vd: Int, element: Int) {
        loadVec(0, vs)
        loadVecSwizzled(1, vt, element)
        asm.movdqaRR(5, 0)
        asm.paddw(5, 1)
        asm.pcmpeqw(4, 4)
        asm.psllwI(4, 15)
        ucarry(6, 0, 5, 4)
        storeAccl(5)
        storeCtxArray(RSP_VCOL, 6)
        zeroCtxArray(RSP_VCOH)
        storeVpr(vd, 5)
    }

    private fun emitVsubc(vs: Int, vt: Int, vd: Int, element: Int) {
        loadVec(0, vs)
        loadVecSwizzled(1, vt, element)
        asm.movdqaRR(5, 0)
        asm.psubw(5, 1)
        asm.pcmpeqw(4, 4)
        asm.psllwI(4, 15)
        ucarry(6, 1, 0, 4)
        asm.movdqaRR(7, 0)
        asm.pcmpeqw(7, 1)
        asm.pcmpeqw(2, 2)
        asm.pxor(7, 2)
        storeAccl(5)
        storeCtxArray(RSP_VCOL, 6)
        storeCtxArray(RSP_VCOH, 7)
        storeVpr(vd, 5)
    }

    private fun emitVadd(vs: Int, vt: Int, vd: Int, element: Int) {
        loadVec(0, vs)
        loadVecSwizzled(1, vt, element)
        loadCtxArray(3, RSP_VCOL)
        asm.psrlwI(3, 15)
        asm.movdqaRR(5, 0)
        asm.paddw(5, 1)
        asm.paddw(5, 3)
        asm.movdqaRR(6, 0)
        asm.pminsw(6, 1)
        asm.movdqaRR(7, 0)
        asm.pmaxsw(7, 1)
        asm.paddsw(6, 3)
        asm.paddsw(6, 7)
        storeAccl(5)
        zeroCtxArray(RSP_VCOL)
        zeroCtxArray(RSP_VCOH)
        storeVpr(vd, 6)
    }

    private fun emitVsub(vs: Int, vt: Int, vd: Int, element: Int) {
        loadVec(0, vs)
        loadVecSwizzled(1, vt, element)
        loadCtxArray(3, RSP_VCOL)
        asm.psrlwI(3, 15)
        asm.movdqaRR(5, 1)
        asm.paddw(5, 3)
        asm.movdqaRR(6, 1)
        asm.paddsw(6, 3)
        asm.movdqaRR(7, 0)
        asm.psubw(7, 5)
        storeAccl(7)
        asm.movdqaRR(7, 0)
        asm.psubsw(7, 6)
        asm.pcmpgtw(6, 5)
        asm.pcmpeqw(2, 2)
        asm.psrlwI(2, 15)
        asm.movdqaRR(5, 7)
        asm.psubsw(5, 2)
        asm.pand(5, 6)
        asm.movdqaRR(4, 6)
        asm.pandn(4, 7)
        asm.por(4, 5)
        zeroCtxArray(RSP_VCOL)
        zeroCtxArray(RSP_VCOH)
        storeVpr(vd, 4)
    }

    private fun emitVabs(vs: Int, vt: Int, vd: Int, element: Int) {
        loadVec(0, vs)
        loadVecSwizzled(1, vt, element)
        asm.movdqaRR(5, 0)
        asm.psrawI(5, 15)
        asm.pxor(3, 3)
        asm.movdqaRR(6, 0)
        asm.pcmpeqw(6, 3)
        asm.movdqaRR(7, 6)
        asm.pandn(7, 1)
        asm.pxor(7, 5)
        asm.movdqaRR(2, 7)
        asm.psubw(2, 5)
        storeAccl(2)
        asm.pcmpeqw(3, 3)
        asm.psrlwI(3, 15)
        asm.movdqaRR(6, 7)
        asm.paddsw(6, 3)
        asm.pand(6, 5)
        asm.movdqaRR(4, 5)
        asm.pandn(4, 7)
        asm.por(4, 6)
        storeVpr(vd, 4)
    }

    private fun emitVsar(vd: Int, element: Int) {
        when (element) {
            8 -> loadCtxArray(0, RSP_ACCH)
            9 -> loadCtxArray(0, RSP_ACCM)
            10 -> loadAccl(0)
            else -> asm.pxor(0, 0)
        }
        storeVpr(vd, 0)
    }

    private fun emitCompare(function: Int, vs: Int, vt: Int, vd: Int, element: Int) {
        loadVec(0, vs)
        loadVecSwizzled(1, vt, element)
        when (function) {
            32 -> {
                asm.movdqaRR(5, 1)
                asm.pcmpgtw(5, 0)
                asm.movdqaRR(6, 0)
                asm.pcmpeqw(6, 1)
                loadCtxArray(4, RSP_VCOH)
                loadCtxArray(3, RSP_VCOL)
                asm.pand(6, 4)
                asm.pand(6, 3)
                asm.por(5, 6)
            }

            33 -> {
                asm.movdqaRR(5, 0)
                asm.pcmpeqw(5, 1)
                loadCtxArray(4, RSP_VCOH)
                asm.pcmpeqw(6, 6)
                asm.pxor(6, 4)
                asm.pand(5, 6)
            }

            34 -> {
                asm.movdqaRR(5, 0)
                asm.pcmpeqw(5, 1)
                asm.pcmpeqw(6, 6)
                asm.pxor(5, 6)
                loadCtxArray(4, RSP_VCOH)
                asm.por(5, 4)
            }

            else -> {
                asm.movdqaRR(5, 0)
                asm.pcmpgtw(5, 1)
                asm.movdqaRR(6, 0)
                asm.pcmpeqw(6, 1)
                loadCtxArray(4, RSP_VCOH)
                loadCtxArray(3, RSP_VCOL)
                asm.pand(4, 3)
                asm.pcmpeqw(2, 2)
                asm.pxor(4, 2)
                asm.pand(6, 4)
                asm.por(5, 6)
            }
        }
        storeCtxArray(RSP_VCCL, 5)
        asm.movdqaRR(6, 5)
        asm.pand(6, 0)
        asm.movdqaRR(7, 5)
        asm.pandn(7, 1)
        asm.por(6, 7)
        storeAccl(6)
        zeroCtxArray(RSP_VCCH)
        zeroCtxArray(RSP_VCOH)
        zeroCtxArray(RSP_VCOL)
        storeVpr(vd, 6)
    }

    private fun emitVmrg(vs: Int, vt: Int, vd: Int, element: Int) {
        loadVec(0, vs)
        loadVecSwizzled(1, vt, element)
        loadCtxArray(5, RSP_VCCL)
        asm.movdqaRR(6, 5)
        asm.pand(6, 0)
        asm.movdqaRR(7, 5)
        asm.pandn(7, 1)
        asm.por(6, 7)
        storeAccl(6)
        zeroCtxArray(RSP_VCOH)
        zeroCtxArray(RSP_VCOL)
        storeVpr(vd, 6)
    }

    private fun emitVmulf(vs: Int, vt: Int, vd: Int, element: Int, signed: Boolean) {
        loadVec(0, vs)
        loadVecSwizzled(1, vt, element)
        asm.movdqaRR(5, 0)
        asm.pmullw(5, 1)
        asm.movdqaRR(6, 0)
        asm.pmulhw(6, 1)
        asm.movdqaRR(7, 5)
        asm.psllwI(7, 1)
        asm.psrlwI(5, 15)
        asm.psllwI(6, 1)
        asm.paddw(6, 5)
        asm.movdqaRR(5, 7)
        asm.psrlwI(5, 15)
        asm.paddw(6, 5)
        asm.pcmpeqw(5, 5)
        asm.psllwI(5, 15)
        asm.paddw(7, 5)
        storeAccl(7)
        storeCtxArray(RSP_ACCM, 6)
        asm.movdqaRR(5, 6)
        asm.psrawI(5, 15)
        asm.movdqaRR(7, 0)
        asm.pcmpeqw(7, 1)
        asm.pcmpeqw(2, 2)
        asm.movdqaRR(3, 7)
        asm.pxor(3, 2)
        asm.movdqaRR(4, 5)
        asm.pand(4, 3)
        storeCtxArray(RSP_ACCH, 4)
        if (signed) {
            asm.movdqaRR(4, 5)
            asm.pand(4, 7)
            asm.paddw(4, 6)
            storeVpr(vd, 4)
        } else {
            asm.movdqaRR(7, 6)
            asm.por(7, 5)
            asm.pcmpeqw(2, 2)
            asm.pxor(4, 2)
            asm.pand(7, 4)
            storeVpr(vd, 7)
        }
    }

    private fun emitVmadl(vs: Int, vt: Int, vd: Int, element: Int) {
        loadVec(0, vs)
        loadVecSwizzled(1, vt, element)
        asm.movdqaRR(5, 0)
        asm.pmulhuw(5, 1)
        asm.pxor(6, 6)
        asm.pxor(7, 7)
        add48()
        emitClampLow(4)
        storeVpr(vd, 4)
    }

    private fun emitVmadm(vs: Int, vt: Int, vd: Int, element: Int) {
        loadVec(0, vs)
        loadVecSwizzled(1, vt, element)
        asm.movdqaRR(5, 0)
        asm.pmullw(5, 1)
        asm.movdqaRR(6, 0)
        asm.pmulhw(6, 1)
        signMask(2, 1)
        asm.movdqaRR(3, 0)
        asm.pand(3, 2)
        asm.paddw(6, 3)
        signMask(7, 6)
        add48()
        emitClampMid(4)
        storeVpr(vd, 4)
    }

    private fun emitVmadn(vs: Int, vt: Int, vd: Int, element: Int) {
        loadVec(0, vs)
        loadVecSwizzled(1, vt, element)
        asm.movdqaRR(5, 0)
        asm.pmullw(5, 1)
        asm.movdqaRR(6, 0)
        asm.pmulhw(6, 1)
        signMask(2, 0)
        asm.movdqaRR(3, 1)
        asm.pand(3, 2)
        asm.paddw(6, 3)
        signMask(7, 6)
        add48()
        emitClampLow(4)
        storeVpr(vd, 4)
    }

    private fun emitVmadh(vs: Int, vt: Int, vd: Int, element: Int) {
        loadVec(0, vs)
        loadVecSwizzled(1, vt, element)
        asm.pxor(5, 5)
        asm.movdqaRR(6, 0)
        asm.pmullw(6, 1)
        asm.movdqaRR(7, 0)
        asm.pmulhw(7, 1)
        add48()
        emitClampMid(4)
        storeVpr(vd, 4)
    }

    private fun emitVmacf(vs: Int, vt: Int, vd: Int, element: Int) {
        loadVec(0, vs)
        loadVecSwizzled(1, vt, element)
        asm.movdqaRR(5, 0)
        asm.pmullw(5, 1)
        asm.movdqaRR(6, 0)
        asm.pmulhw(6, 1)
        signMask(7, 6)
        asm.movdqaRR(2, 6)
        asm.psllwI(2, 1)
        asm.movdqaRR(3, 5)
        asm.psrlwI(3, 15)
        asm.por(2, 3)
        asm.psllwI(5, 1)
        asm.movdqaRR(6, 2)
        add48()
        emitClampMid(4)
        storeVpr(vd, 4)
    }

    private fun emitVmudl(vs: Int, vt: Int, vd: Int, element: Int) {
        loadVec(0, vs)
        loadVecSwizzled(1, vt, element)
        asm.movdqaRR(5, 0)
        asm.pmulhuw(5, 1)
        storeAccl(5)
        zeroCtxArray(RSP_ACCM)
        zeroCtxArray(RSP_ACCH)
        storeVpr(vd, 5)
    }

    private fun emitVmudm(vs: Int, vt: Int, vd: Int, element: Int) {
        loadVec(0, vs)
        loadVecSwizzled(1, vt, element)
        asm.movdqaRR(5, 0)
        asm.pmullw(5, 1)
        asm.movdqaRR(6, 0)
        asm.pmulhw(6, 1)
        signMask(2, 1)
        asm.movdqaRR(7, 0)
        asm.pand(7, 2)
        asm.paddw(6, 7)
        signMask(7, 6)
        storeAccl(5)
        storeCtxArray(RSP_ACCM, 6)
        storeCtxArray(RSP_ACCH, 7)
        storeVpr(vd, 6)
    }

    private fun emitVmudn(vs: Int, vt: Int, vd: Int, element: Int) {
        loadVec(0, vs)
        loadVecSwizzled(1, vt, element)
        asm.movdqaRR(5, 0)
        asm.pmullw(5, 1)
        asm.movdqaRR(6, 0)
        asm.pmulhw(6, 1)
        signMask(2, 0)
        asm.movdqaRR(7, 1)
        asm.pand(7, 2)
        asm.paddw(6, 7)
        signMask(7, 6)
        storeAccl(5)
        storeCtxArray(RSP_ACCM, 6)
        storeCtxArray(RSP_ACCH, 7)
        storeVpr(vd, 5)
    }

    private fun emitVmudh(vs: Int, vt: Int, vd: Int, element: Int) {
        loadVec(0, vs)
        loadVecSwizzled(1, vt, element)
        asm.movdqaRR(5, 0)
        asm.pmullw(5, 1)
        asm.movdqaRR(6, 0)
        asm.pmulhw(6, 1)
        asm.movdqaRR(7, 5)
        asm.punpcklwd(7, 6)
        asm.movdqaRR(2, 5)
        asm.punpckhwd(2, 6)
        asm.packssdw(7, 2)
        zeroAccl()
        storeCtxArray(RSP_ACCM, 5)
        storeCtxArray(RSP_ACCH, 6)
        storeVpr(vd, 7)
    }

    private fun emitLogic(kind: Int, vs: Int, vt: Int, vd: Int, element: Int) {
        loadVec(0, vs)
        loadVecSwizzled(1, vt, element)
        when (kind) {
            0 -> asm.pand(0, 1)
            1 -> {
                asm.pand(0, 1)
                notVec(0)
            }

            2 -> asm.por(0, 1)
            3 -> {
                asm.por(0, 1)
                notVec(0)
            }

            4 -> asm.pxor(0, 1)
            else -> {
                asm.pxor(0, 1)
                notVec(0)
            }
        }
        storeAccl(0)
        storeVpr(vd, 0)
    }

    private fun emitSpecial(op: Int, rs: Int, rt: Int, rd: Int, sa: Int) {
        when (op and 0x3F) {
            0 -> {
                loadG(RAX, rt)
                if (sa != 0) asm.shiftRI(SH_SHL, RAX, sa, 0)
                storeG(rd, RAX)
            }

            2 -> {
                loadG(RAX, rt)
                if (sa != 0) asm.shiftRI(SH_SHR, RAX, sa, 0)
                storeG(rd, RAX)
            }

            3 -> {
                loadG(RAX, rt)
                if (sa != 0) asm.shiftRI(SH_SAR, RAX, sa, 0)
                storeG(rd, RAX)
            }

            4 -> {
                loadG(RCX, rs)
                loadG(RAX, rt)
                asm.shiftRC(SH_SHL, RAX, 0)
                storeG(rd, RAX)
            }

            6 -> {
                loadG(RCX, rs)
                loadG(RAX, rt)
                asm.shiftRC(SH_SHR, RAX, 0)
                storeG(rd, RAX)
            }

            7 -> {
                loadG(RCX, rs)
                loadG(RAX, rt)
                asm.shiftRC(SH_SAR, RAX, 0)
                storeG(rd, RAX)
            }

            32, 33 -> {
                loadG(RAX, rs)
                asm.aluRM(ALU_ADD, RAX, R12, rt * 4, 0)
                storeG(rd, RAX)
            }

            34, 35 -> {
                loadG(RAX, rs)
                asm.aluRM(ALU_SUB, RAX, R12, rt * 4, 0)
                storeG(rd, RAX)
            }

            36 -> {
                loadG(RAX, rs)
                asm.aluRM(ALU_AND, RAX, R12, rt * 4, 0)
                storeG(rd, RAX)
            }

            37 -> {
                loadG(RAX, rs)
                asm.aluRM(ALU_OR, RAX, R12, rt * 4, 0)
                storeG(rd, RAX)
            }

            38 -> {
                loadG(RAX, rs)
                asm.aluRM(ALU_XOR, RAX, R12, rt * 4, 0)
                storeG(rd, RAX)
            }

            39 -> {
                loadG(RAX, rs)
                asm.aluRM(ALU_OR, RAX, R12, rt * 4, 0)
                asm.onePop(2, RAX, 0)
                storeG(rd, RAX)
            }

            42 -> {
                loadG(RAX, rs)
                asm.aluRM(ALU_CMP, RAX, R12, rt * 4, 0)
                asm.setcc(CC_L, RAX)
                asm.movzx8RR(RAX, RAX)
                storeG(rd, RAX)
            }

            43 -> {
                loadG(RAX, rs)
                asm.aluRM(ALU_CMP, RAX, R12, rt * 4, 0)
                asm.setcc(CC_B, RAX)
                asm.movzx8RR(RAX, RAX)
                storeG(rd, RAX)
            }
        }
    }

    private fun emitBranch(op: Int, slot: Int, i: Int) {
        val vaddr = vbase + i * 4
        val rs = (op ushr 21) and 0x1F
        val rt = (op ushr 16) and 0x1F
        val rd = (op ushr 11) and 0x1F
        val imm = op.toShort().toInt()
        val major = op ushr 26
        val func = op and 0x3F

        val condTarget = (vaddr + 4 + (imm shl 2)) and 0xFFC
        val jTarget = ((op and 0x3FFFFFF) shl 2) and 0xFFC
        val fall = (vaddr + 8) and 0xFFC
        val link = (vaddr + 8) and 0xFFF

        if (major == 0 && (func == 8 || func == 9)) {
            loadG(RAX, rs)
            asm.aluRI(ALU_AND, RAX, 0xFFC, 0)
            asm.movMR(RBX, RSP_TARGET, RAX, 1)
            if (func == 9) {
                asm.movRI32(RAX, link)
                storeG(rd, RAX)
            }
            emitOp(slot, i + 1)
            asm.movRM(RAX, RBX, RSP_TARGET, 1)
            asm.movMR(RBX, RSP_PC, RAX, 1)
            asm.movMI(RBX, RSP_STATE, STATE_STEP)
            exitJumps.add(asm.jmp())
            return
        }

        if (major == 2 || major == 3) {
            if (major == 3) {
                asm.movRI32(RAX, link)
                storeG(31, RAX)
            }
            emitOp(slot, i + 1)
            emitStaticExit(jTarget)
            return
        }

        when (major) {
            4 -> {
                loadG(RAX, rs)
                asm.aluRM(ALU_CMP, RAX, R12, rt * 4, 0)
                asm.setcc(CC_E, RAX)
            }

            5 -> {
                loadG(RAX, rs)
                asm.aluRM(ALU_CMP, RAX, R12, rt * 4, 0)
                asm.setcc(CC_NE, RAX)
            }

            6 -> {
                asm.aluMI(ALU_CMP, R12, rs * 4, 0, 0)
                asm.setcc(CC_LE, RAX)
            }

            7 -> {
                asm.aluMI(ALU_CMP, R12, rs * 4, 0, 0)
                asm.setcc(CC_G, RAX)
            }

            1 -> {
                asm.aluMI(ALU_CMP, R12, rs * 4, 0, 0)
                asm.setcc(if (rt and 1 == 0) CC_L else CC_GE, RAX)
            }
        }
        asm.movzx8RR(RAX, RAX)
        asm.movMR(RBX, RSP_SCR1, RAX, 1)

        if (major == 1 && (rt == 16 || rt == 17)) {
            asm.movRI32(RAX, link)
            storeG(31, RAX)
        }

        emitOp(slot, i + 1)

        asm.movRM(RAX, RBX, RSP_SCR1, 1)
        asm.testRR(RAX, RAX, 0)
        val notTaken = asm.jcc(CC_E)
        emitStaticExit(condTarget)
        asm.patch(notTaken)
        emitStaticExit(fall)
    }
}
