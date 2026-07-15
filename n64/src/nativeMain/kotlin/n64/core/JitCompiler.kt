package n64.core

const val CTX_PC = 0
const val CTX_COUNT = 8
const val CTX_HI = 16
const val CTX_LO = 24
const val CTX_STATE = 32
const val CTX_TARGET = 40
const val CTX_INITMODE = 48
const val CTX_SCR1 = 56
const val CTX_SCR2 = 64
const val CTX_LIMIT = 72
const val CTX_GPR = 80
const val CTX_RDRAM = 88
const val CTX_LUTR = 96
const val CTX_LUTW = 104
const val CTX_PAGES = 112
const val CTX_DISPATCH = 120
const val CTX_RETURN = 128
const val CTX_LINKSRC = 136
const val CTX_INSTRS = 144

const val DELAY_NONE = 0
const val DELAY_COND_IMM = 1
const val DELAY_ALWAYS_REG = 2
const val DELAY_ALWAYS_IMM = 3

class JitBail(
    val vaddr: Int,
    val delta: Int,
    val kind: Int,
    val target: Int,
) {
    val patches = ArrayList<Int>()
}

class JitCallbacks(
    val read8: Long,
    val read16: Long,
    val read32: Long,
    val read64: Long,
    val write8: Long,
    val write16: Long,
    val write32: Long,
    val write64: Long,
    val writeMasked: Long,
    val invalidate: Long,
)

class JitCompiler(private val callbacks: JitCallbacks) {
    val asm = JitAsm()

    private var vbase = 0
    private var cycles = 0
    private var flushed = 0
    private var delayKind = DELAY_NONE
    private var delayTarget = 0
    private val exitJumps = ArrayList<Int>()
    private val bails = ArrayList<JitBail>()
    private var currentBail: JitBail? = null
    private var currentVaddr = 0

    fun buildTrampoline(): Int {
        asm.reset()
        asm.push(RBX)
        asm.push(R12)
        asm.push(R13)
        asm.push(R14)
        asm.push(R15)
        asm.movRR(RBX, RDI, 1)
        asm.movRM(R12, RBX, CTX_GPR, 1)
        asm.movRM(R13, RBX, CTX_RDRAM, 1)
        asm.movRM(R14, RBX, CTX_LUTR, 1)
        asm.movRM(R15, RBX, CTX_LUTW, 1)
        asm.jmpMem(RBX, CTX_DISPATCH)
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
        cycles = 0
        flushed = 0
        delayKind = DELAY_NONE
        exitJumps.clear()
        bails.clear()
        asm.reset()

        asm.movRM(RAX, RBX, CTX_COUNT, 1)
        asm.aluRM(ALU_CMP, RAX, RBX, CTX_LIMIT, 1)
        val runIt = asm.jcc(CC_LE)
        asm.movMI(RBX, CTX_LINKSRC, 0)
        asm.movRI32sx(RAX, vbase)
        asm.movMR(RBX, CTX_PC, RAX, 1)
        asm.movRI32(RAX, 0)
        exitJumps.add(asm.jmp())
        asm.patch(runIt)
        asm.aluMI(ALU_ADD, RBX, CTX_INSTRS, count, 1)

        val body = if (endsWithBranch) count - 2 else count
        var i = 0
        while (i < body) {
            beginOp(i)
            emitOp(ops[i], i)
            cycles += 1 + penalty(ops[i])
            i++
        }

        if (endsWithBranch) {
            emitBranch(ops[count - 2], ops[count - 1], count - 2)
        } else {
            emitExit(vbase + count * 4, -1, cycles)
        }

        for (bail in bails) {
            for (at in bail.patches) asm.patch(at)
            if (bail.delta != 0) asm.aluMI(ALU_ADD, RBX, CTX_COUNT, bail.delta, 1)
            asm.movRI32sx(RAX, bail.vaddr)
            asm.movMR(RBX, CTX_PC, RAX, 1)
            when (bail.kind) {
                DELAY_NONE -> asm.movMI(RBX, CTX_STATE, STATE_STEP)
                DELAY_COND_IMM -> {
                    asm.test8RR(R11, R11)
                    val not = asm.jcc(CC_E)
                    asm.movMI(RBX, CTX_STATE, STATE_DELAY_TAKEN)
                    asm.movRI32sx(RAX, bail.target)
                    asm.movMR(RBX, CTX_TARGET, RAX, 1)
                    val over = asm.jmp()
                    asm.patch(not)
                    asm.movMI(RBX, CTX_STATE, STATE_DELAY_NOT_TAKEN)
                    asm.patch(over)
                }

                DELAY_ALWAYS_REG -> {
                    asm.movMI(RBX, CTX_STATE, STATE_DELAY_TAKEN)
                    asm.movMR(RBX, CTX_TARGET, R11, 1)
                }

                DELAY_ALWAYS_IMM -> {
                    asm.movMI(RBX, CTX_STATE, STATE_DELAY_TAKEN)
                    asm.movRI32sx(RAX, bail.target)
                    asm.movMR(RBX, CTX_TARGET, RAX, 1)
                }
            }
            asm.movRI32(RAX, 1)
            exitJumps.add(asm.jmp())
        }

        for (at in exitJumps) asm.patch(at)
        asm.jmpMem(RBX, CTX_RETURN)
        return asm.len
    }

    private fun beginOp(i: Int) {
        currentVaddr = vbase + i * 4
        currentBail = null
    }

    private fun bailCc(cc: Int) {
        var bail = currentBail
        if (bail == null) {
            bail = JitBail(currentVaddr, cycles - flushed, delayKind, delayTarget)
            bails.add(bail)
            currentBail = bail
        }
        bail.patches.add(asm.jcc(cc))
    }

    private fun flush() {
        if (cycles != flushed) {
            asm.aluMI(ALU_ADD, RBX, CTX_COUNT, cycles - flushed, 1)
            flushed = cycles
        }
    }

    private fun emitExit(pcImm: Int, pcReg: Int, cyclesVal: Int) {
        if (cyclesVal != flushed) asm.aluMI(ALU_ADD, RBX, CTX_COUNT, cyclesVal - flushed, 1)
        if (pcReg >= 0) {
            asm.movMR(RBX, CTX_PC, pcReg, 1)
            asm.movMI(RBX, CTX_LINKSRC, 0)
            asm.movRI32(RAX, 0)
            exitJumps.add(asm.jmp())
            return
        }
        val jmpStart = asm.len
        val slot = asm.jmp()
        asm.patch(slot)
        val leaDisp = asm.leaRip(RAX)
        asm.patchTo(leaDisp, jmpStart)
        asm.movMR(RBX, CTX_LINKSRC, RAX, 1)
        asm.movRI32sx(RAX, pcImm)
        asm.movMR(RBX, CTX_PC, RAX, 1)
        asm.movRI32(RAX, 0)
        exitJumps.add(asm.jmp())
    }

    private fun penalty(op: Int): Int {
        if (op ushr 26 != 0) return 0
        return when (op and 0x3F) {
            24, 25 -> 4
            26, 27 -> 36
            28, 29 -> 7
            30, 31 -> 68
            else -> 0
        }
    }

    private fun loadG(dst: Int, idx: Int, w: Int) = asm.movRM(dst, R12, idx * 8, w)

    private fun storeG(idx: Int, src: Int) {
        if (idx != 0) asm.movMR(R12, idx * 8, src, 1)
    }

    private fun storeSx32(idx: Int) {
        asm.movsxdRR(RAX, RAX)
        storeG(idx, RAX)
    }

    private fun emitBranch(op: Int, slot: Int, i: Int) {
        beginOp(i)
        val vaddr = vbase + i * 4
        val rs = (op ushr 21) and 0x1F
        val rt = (op ushr 16) and 0x1F
        val rd = (op ushr 11) and 0x1F
        val imm = op.toShort().toInt()
        val target = vaddr + 4 + (imm shl 2)
        val jumpTarget = ((vaddr + 4) and 0xF0000000.toInt()) or ((op and 0x3FFFFFF) shl 2)
        val link = vaddr + 8

        var kind = DELAY_COND_IMM
        var likely = false
        var immTarget = target

        when (op ushr 26) {
            0 -> {
                loadG(R11, rs, 1)
                if ((op and 0x3F) == 9 && rd != 0) {
                    asm.movRI32sx(RAX, link)
                    storeG(rd, RAX)
                }
                kind = DELAY_ALWAYS_REG
            }

            2 -> {
                kind = DELAY_ALWAYS_IMM
                immTarget = jumpTarget
            }

            3 -> {
                asm.movRI32sx(RAX, link)
                storeG(31, RAX)
                kind = DELAY_ALWAYS_IMM
                immTarget = jumpTarget
            }

            4, 20 -> {
                loadG(RAX, rs, 1)
                asm.aluRM(ALU_CMP, RAX, R12, rt * 8, 1)
                asm.setcc(CC_E, R11)
                likely = op ushr 26 == 20
            }

            5, 21 -> {
                loadG(RAX, rs, 1)
                asm.aluRM(ALU_CMP, RAX, R12, rt * 8, 1)
                asm.setcc(CC_NE, R11)
                likely = op ushr 26 == 21
            }

            6, 22 -> {
                asm.aluMI(ALU_CMP, R12, rs * 8, 0, 1)
                asm.setcc(CC_LE, R11)
                likely = op ushr 26 == 22
            }

            7, 23 -> {
                asm.aluMI(ALU_CMP, R12, rs * 8, 0, 1)
                asm.setcc(CC_G, R11)
                likely = op ushr 26 == 23
            }

            1 -> {
                val which = rt
                asm.aluMI(ALU_CMP, R12, rs * 8, 0, 1)
                asm.setcc(if (which and 1 == 0) CC_L else CC_GE, R11)
                if (which >= 16) {
                    asm.movRI32sx(RAX, link)
                    storeG(31, RAX)
                }
                likely = which == 2 || which == 3 || which == 18 || which == 19
            }
        }

        cycles += 1

        if (kind == DELAY_ALWAYS_REG || kind == DELAY_ALWAYS_IMM) {
            delayKind = kind
            delayTarget = immTarget
            beginOp(i + 1)
            emitOp(slot, i + 1)
            cycles += 1 + penalty(slot)
            delayKind = DELAY_NONE
            if (kind == DELAY_ALWAYS_REG) {
                emitExit(0, R11, cycles)
            } else {
                emitExit(immTarget, -1, cycles)
            }
            return
        }

        if (likely) {
            val c0 = cycles
            val f0 = flushed
            asm.test8RR(R11, R11)
            val skip = asm.jcc(CC_E)
            delayKind = DELAY_ALWAYS_IMM
            delayTarget = immTarget
            beginOp(i + 1)
            emitOp(slot, i + 1)
            cycles += 1 + penalty(slot)
            delayKind = DELAY_NONE
            emitExit(immTarget, -1, cycles)
            asm.patch(skip)
            val fTaken = flushed
            flushed = f0
            emitExit(vaddr + 8, -1, c0)
            flushed = fTaken
            cycles = c0
            return
        }

        delayKind = DELAY_COND_IMM
        delayTarget = immTarget
        beginOp(i + 1)
        emitOp(slot, i + 1)
        cycles += 1 + penalty(slot)
        delayKind = DELAY_NONE
        asm.test8RR(R11, R11)
        val fall = asm.jcc(CC_E)
        val f0 = flushed
        emitExit(immTarget, -1, cycles)
        asm.patch(fall)
        flushed = f0
        emitExit(vaddr + 8, -1, cycles)
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

            8 -> {
                loadG(RAX, rs, 0)
                asm.aluRI(ALU_ADD, RAX, imm, 0)
                bailCc(CC_O)
                storeSx32(rt)
            }

            9 -> {
                loadG(RAX, rs, 0)
                if (imm != 0) asm.aluRI(ALU_ADD, RAX, imm, 0)
                storeSx32(rt)
            }

            10 -> {
                asm.aluMI(ALU_CMP, R12, rs * 8, imm, 1)
                asm.setcc(CC_L, RAX)
                asm.movzx8RR(RAX, RAX)
                storeG(rt, RAX)
            }

            11 -> {
                asm.aluMI(ALU_CMP, R12, rs * 8, imm, 1)
                asm.setcc(CC_B, RAX)
                asm.movzx8RR(RAX, RAX)
                storeG(rt, RAX)
            }

            12 -> {
                loadG(RAX, rs, 1)
                asm.aluRI(ALU_AND, RAX, uimm, 1)
                storeG(rt, RAX)
            }

            13 -> {
                loadG(RAX, rs, 1)
                asm.aluRI(ALU_OR, RAX, uimm, 1)
                storeG(rt, RAX)
            }

            14 -> {
                loadG(RAX, rs, 1)
                asm.aluRI(ALU_XOR, RAX, uimm, 1)
                storeG(rt, RAX)
            }

            15 -> {
                asm.movRI32sx(RAX, uimm shl 16)
                storeG(rt, RAX)
            }

            24 -> {
                loadG(RAX, rs, 1)
                asm.aluRI(ALU_ADD, RAX, imm, 1)
                bailCc(CC_O)
                storeG(rt, RAX)
            }

            25 -> {
                loadG(RAX, rs, 1)
                if (imm != 0) asm.aluRI(ALU_ADD, RAX, imm, 1)
                storeG(rt, RAX)
            }

            32 -> emitLoadByte(rs, rt, imm, true)
            33 -> emitLoadHalf(rs, rt, imm, true)
            34 -> emitLwSide(rs, rt, imm, true)
            35 -> emitLw(rs, rt, imm, true)
            36 -> emitLoadByte(rs, rt, imm, false)
            37 -> emitLoadHalf(rs, rt, imm, false)
            38 -> emitLwSide(rs, rt, imm, false)
            39 -> emitLw(rs, rt, imm, false)

            40 -> emitSb(rs, rt, imm)
            41 -> emitSh(rs, rt, imm)
            42 -> emitSwSide(rs, rt, imm, true)
            43 -> emitSw(rs, rt, imm)
            46 -> emitSwSide(rs, rt, imm, false)
            47 -> {}

            55 -> emitLd(rs, rt, imm)
            63 -> emitSd(rs, rt, imm)
        }
    }

    private fun emitSpecial(op: Int, rs: Int, rt: Int, rd: Int, sa: Int) {
        when (op and 0x3F) {
            0 -> {
                loadG(RAX, rt, 0)
                if (sa != 0) asm.shiftRI(SH_SHL, RAX, sa, 0)
                storeSx32(rd)
            }

            2 -> {
                loadG(RAX, rt, 0)
                if (sa != 0) asm.shiftRI(SH_SHR, RAX, sa, 0)
                storeSx32(rd)
            }

            3 -> {
                loadG(RAX, rt, 1)
                if (sa != 0) asm.shiftRI(SH_SAR, RAX, sa, 1)
                storeSx32(rd)
            }

            4 -> {
                loadG(RCX, rs, 0)
                loadG(RAX, rt, 0)
                asm.shiftRC(SH_SHL, RAX, 0)
                storeSx32(rd)
            }

            6 -> {
                loadG(RCX, rs, 0)
                loadG(RAX, rt, 0)
                asm.shiftRC(SH_SHR, RAX, 0)
                storeSx32(rd)
            }

            7 -> {
                loadG(RCX, rs, 0)
                asm.aluRI(ALU_AND, RCX, 0x1F, 0)
                loadG(RAX, rt, 1)
                asm.shiftRC(SH_SAR, RAX, 1)
                storeSx32(rd)
            }

            15 -> {}

            16 -> {
                asm.movRM(RAX, RBX, CTX_HI, 1)
                storeG(rd, RAX)
            }

            17 -> {
                loadG(RAX, rs, 1)
                asm.movMR(RBX, CTX_HI, RAX, 1)
            }

            18 -> {
                asm.movRM(RAX, RBX, CTX_LO, 1)
                storeG(rd, RAX)
            }

            19 -> {
                loadG(RAX, rs, 1)
                asm.movMR(RBX, CTX_LO, RAX, 1)
            }

            20 -> {
                loadG(RCX, rs, 0)
                loadG(RAX, rt, 1)
                asm.shiftRC(SH_SHL, RAX, 1)
                storeG(rd, RAX)
            }

            22 -> {
                loadG(RCX, rs, 0)
                loadG(RAX, rt, 1)
                asm.shiftRC(SH_SHR, RAX, 1)
                storeG(rd, RAX)
            }

            23 -> {
                loadG(RCX, rs, 0)
                loadG(RAX, rt, 1)
                asm.shiftRC(SH_SAR, RAX, 1)
                storeG(rd, RAX)
            }

            24 -> {
                loadG(RAX, rs, 0)
                asm.onePopM(5, R12, rt * 8, 0)
                asm.movsxdRR(RAX, RAX)
                asm.movMR(RBX, CTX_LO, RAX, 1)
                asm.movsxdRR(RDX, RDX)
                asm.movMR(RBX, CTX_HI, RDX, 1)
            }

            25 -> {
                loadG(RAX, rs, 0)
                asm.onePopM(4, R12, rt * 8, 0)
                asm.movsxdRR(RAX, RAX)
                asm.movMR(RBX, CTX_LO, RAX, 1)
                asm.movsxdRR(RDX, RDX)
                asm.movMR(RBX, CTX_HI, RDX, 1)
            }

            26 -> emitDiv(rs, rt)
            27 -> emitDivu(rs, rt)

            28 -> {
                loadG(RAX, rs, 1)
                asm.onePopM(5, R12, rt * 8, 1)
                asm.movMR(RBX, CTX_LO, RAX, 1)
                asm.movMR(RBX, CTX_HI, RDX, 1)
            }

            29 -> {
                loadG(RAX, rs, 1)
                asm.onePopM(4, R12, rt * 8, 1)
                asm.movMR(RBX, CTX_LO, RAX, 1)
                asm.movMR(RBX, CTX_HI, RDX, 1)
            }

            30 -> emitDdiv(rs, rt)
            31 -> emitDdivu(rs, rt)

            32 -> {
                loadG(RAX, rs, 0)
                asm.aluRM(ALU_ADD, RAX, R12, rt * 8, 0)
                bailCc(CC_O)
                storeSx32(rd)
            }

            33 -> {
                loadG(RAX, rs, 0)
                asm.aluRM(ALU_ADD, RAX, R12, rt * 8, 0)
                storeSx32(rd)
            }

            34 -> {
                loadG(RAX, rs, 0)
                asm.aluRM(ALU_SUB, RAX, R12, rt * 8, 0)
                bailCc(CC_O)
                storeSx32(rd)
            }

            35 -> {
                loadG(RAX, rs, 0)
                asm.aluRM(ALU_SUB, RAX, R12, rt * 8, 0)
                storeSx32(rd)
            }

            36 -> {
                loadG(RAX, rs, 1)
                asm.aluRM(ALU_AND, RAX, R12, rt * 8, 1)
                storeG(rd, RAX)
            }

            37 -> {
                loadG(RAX, rs, 1)
                asm.aluRM(ALU_OR, RAX, R12, rt * 8, 1)
                storeG(rd, RAX)
            }

            38 -> {
                loadG(RAX, rs, 1)
                asm.aluRM(ALU_XOR, RAX, R12, rt * 8, 1)
                storeG(rd, RAX)
            }

            39 -> {
                loadG(RAX, rs, 1)
                asm.aluRM(ALU_OR, RAX, R12, rt * 8, 1)
                asm.onePop(2, RAX, 1)
                storeG(rd, RAX)
            }

            42 -> {
                loadG(RAX, rs, 1)
                asm.aluRM(ALU_CMP, RAX, R12, rt * 8, 1)
                asm.setcc(CC_L, RAX)
                asm.movzx8RR(RAX, RAX)
                storeG(rd, RAX)
            }

            43 -> {
                loadG(RAX, rs, 1)
                asm.aluRM(ALU_CMP, RAX, R12, rt * 8, 1)
                asm.setcc(CC_B, RAX)
                asm.movzx8RR(RAX, RAX)
                storeG(rd, RAX)
            }

            44 -> {
                loadG(RAX, rs, 1)
                asm.aluRM(ALU_ADD, RAX, R12, rt * 8, 1)
                bailCc(CC_O)
                storeG(rd, RAX)
            }

            45 -> {
                loadG(RAX, rs, 1)
                asm.aluRM(ALU_ADD, RAX, R12, rt * 8, 1)
                storeG(rd, RAX)
            }

            46 -> {
                loadG(RAX, rs, 1)
                asm.aluRM(ALU_SUB, RAX, R12, rt * 8, 1)
                bailCc(CC_O)
                storeG(rd, RAX)
            }

            47 -> {
                loadG(RAX, rs, 1)
                asm.aluRM(ALU_SUB, RAX, R12, rt * 8, 1)
                storeG(rd, RAX)
            }

            56 -> {
                loadG(RAX, rt, 1)
                if (sa != 0) asm.shiftRI(SH_SHL, RAX, sa, 1)
                storeG(rd, RAX)
            }

            58 -> {
                loadG(RAX, rt, 1)
                if (sa != 0) asm.shiftRI(SH_SHR, RAX, sa, 1)
                storeG(rd, RAX)
            }

            59 -> {
                loadG(RAX, rt, 1)
                if (sa != 0) asm.shiftRI(SH_SAR, RAX, sa, 1)
                storeG(rd, RAX)
            }

            60 -> {
                loadG(RAX, rt, 1)
                asm.shiftRI(SH_SHL, RAX, sa + 32, 1)
                storeG(rd, RAX)
            }

            62 -> {
                loadG(RAX, rt, 1)
                asm.shiftRI(SH_SHR, RAX, sa + 32, 1)
                storeG(rd, RAX)
            }

            63 -> {
                loadG(RAX, rt, 1)
                asm.shiftRI(SH_SAR, RAX, sa + 32, 1)
                storeG(rd, RAX)
            }
        }
    }

    private fun emitDiv(rs: Int, rt: Int) {
        loadG(RAX, rs, 0)
        loadG(RCX, rt, 0)
        asm.testRR(RCX, RCX, 0)
        val zero = asm.jcc(CC_E)
        asm.aluRI(ALU_CMP, RCX, -1, 0)
        val doDiv = asm.jcc(CC_NE)
        asm.aluRI(ALU_CMP, RAX, 0x80000000.toInt(), 0)
        val doDiv2 = asm.jcc(CC_NE)
        asm.movRI32sx(RDX, 0x80000000.toInt())
        asm.movMR(RBX, CTX_LO, RDX, 1)
        asm.movRI32(RDX, 0)
        asm.movMR(RBX, CTX_HI, RDX, 1)
        val end1 = asm.jmp()
        asm.patch(doDiv)
        asm.patch(doDiv2)
        asm.cdq()
        asm.onePop(7, RCX, 0)
        asm.movsxdRR(RAX, RAX)
        asm.movMR(RBX, CTX_LO, RAX, 1)
        asm.movsxdRR(RDX, RDX)
        asm.movMR(RBX, CTX_HI, RDX, 1)
        val end2 = asm.jmp()
        asm.patch(zero)
        asm.movRR(RDX, RAX, 0)
        asm.shiftRI(SH_SAR, RDX, 31, 0)
        asm.aluRR(ALU_ADD, RDX, RDX, 0)
        asm.onePop(2, RDX, 0)
        asm.movsxdRR(RDX, RDX)
        asm.movMR(RBX, CTX_LO, RDX, 1)
        asm.movsxdRR(RAX, RAX)
        asm.movMR(RBX, CTX_HI, RAX, 1)
        asm.patch(end1)
        asm.patch(end2)
    }

    private fun emitDivu(rs: Int, rt: Int) {
        loadG(RAX, rs, 0)
        loadG(RCX, rt, 0)
        asm.testRR(RCX, RCX, 0)
        val zero = asm.jcc(CC_E)
        asm.aluRR(ALU_XOR, RDX, RDX, 0)
        asm.onePop(6, RCX, 0)
        asm.movsxdRR(RAX, RAX)
        asm.movMR(RBX, CTX_LO, RAX, 1)
        asm.movsxdRR(RDX, RDX)
        asm.movMR(RBX, CTX_HI, RDX, 1)
        val end = asm.jmp()
        asm.patch(zero)
        asm.movRI32sx(RDX, -1)
        asm.movMR(RBX, CTX_LO, RDX, 1)
        asm.movsxdRR(RAX, RAX)
        asm.movMR(RBX, CTX_HI, RAX, 1)
        asm.patch(end)
    }

    private fun emitDdiv(rs: Int, rt: Int) {
        loadG(RAX, rs, 1)
        loadG(RCX, rt, 1)
        asm.testRR(RCX, RCX, 1)
        val zero = asm.jcc(CC_E)
        asm.aluRI(ALU_CMP, RCX, -1, 1)
        val doDiv = asm.jcc(CC_NE)
        asm.movRI64(RDX, Long.MIN_VALUE)
        asm.aluRR(ALU_CMP, RAX, RDX, 1)
        val doDiv2 = asm.jcc(CC_NE)
        asm.movMR(RBX, CTX_LO, RDX, 1)
        asm.movRI32(RDX, 0)
        asm.movMR(RBX, CTX_HI, RDX, 1)
        val end1 = asm.jmp()
        asm.patch(doDiv)
        asm.patch(doDiv2)
        asm.cqo()
        asm.onePop(7, RCX, 1)
        asm.movMR(RBX, CTX_LO, RAX, 1)
        asm.movMR(RBX, CTX_HI, RDX, 1)
        val end2 = asm.jmp()
        asm.patch(zero)
        asm.movRR(RDX, RAX, 1)
        asm.shiftRI(SH_SAR, RDX, 63, 1)
        asm.aluRR(ALU_ADD, RDX, RDX, 1)
        asm.onePop(2, RDX, 1)
        asm.movMR(RBX, CTX_LO, RDX, 1)
        asm.movMR(RBX, CTX_HI, RAX, 1)
        asm.patch(end1)
        asm.patch(end2)
    }

    private fun emitDdivu(rs: Int, rt: Int) {
        loadG(RAX, rs, 1)
        loadG(RCX, rt, 1)
        asm.testRR(RCX, RCX, 1)
        val zero = asm.jcc(CC_E)
        asm.aluRR(ALU_XOR, RDX, RDX, 0)
        asm.onePop(6, RCX, 1)
        asm.movMR(RBX, CTX_LO, RAX, 1)
        asm.movMR(RBX, CTX_HI, RDX, 1)
        val end = asm.jmp()
        asm.patch(zero)
        asm.movRI32sx(RDX, -1)
        asm.movMR(RBX, CTX_LO, RDX, 1)
        asm.movMR(RBX, CTX_HI, RAX, 1)
        asm.patch(end)
    }

    private fun emitTranslate(rs: Int, imm: Int, alignMask: Int, write: Boolean) {
        loadG(RAX, rs, 1)
        if (imm != 0) asm.aluRI(ALU_ADD, RAX, imm, 1)
        asm.movsxdRR(RCX, RAX)
        asm.aluRR(ALU_CMP, RCX, RAX, 1)
        bailCc(CC_NE)
        if (alignMask != 0) {
            asm.testRI(RAX, alignMask)
            bailCc(CC_NE)
        }
        asm.movRR(RCX, RAX, 0)
        asm.aluRI(ALU_AND, RCX, 0xC0000000.toInt(), 0)
        asm.aluRI(ALU_CMP, RCX, 0x80000000.toInt(), 0)
        val tlb = asm.jcc(CC_NE)
        asm.movRR(RCX, RAX, 0)
        asm.aluRI(ALU_AND, RCX, 0x1FFFFFFF, 0)
        val have = asm.jmp()
        asm.patch(tlb)
        asm.movRR(RCX, RAX, 0)
        asm.shiftRI(SH_SHR, RCX, 12, 0)
        asm.movRMIndexed(RCX, if (write) R15 else R14, RCX, 4, 0, 0)
        asm.testRR(RCX, RCX, 0)
        bailCc(CC_E)
        asm.aluRI(ALU_AND, RCX, 0x1FFFF000, 0)
        asm.movRR(RDX, RAX, 0)
        asm.aluRI(ALU_AND, RDX, 0xFFF, 0)
        asm.aluRR(ALU_OR, RCX, RDX, 0)
        asm.patch(have)
    }

    private fun calloutPrologue(savePhys: Boolean) {
        asm.movMR(RBX, CTX_SCR1, R11, 1)
        if (savePhys) asm.movMR(RBX, CTX_SCR2, RCX, 1)
    }

    private fun calloutEpilogue(savePhys: Boolean) {
        asm.movRM(R11, RBX, CTX_SCR1, 1)
        if (savePhys) asm.movRM(RCX, RBX, CTX_SCR2, 1)
    }

    private fun call(fn: Long) {
        asm.movRI64(RAX, fn)
        asm.callReg(RAX)
    }

    private fun emitInvalidateCheck(physReg: Int) {
        asm.movRM(RSI, RBX, CTX_PAGES, 1)
        asm.movRR(RDX, physReg, 0)
        asm.shiftRI(SH_SHR, RDX, JIT_PAGE_SHIFT, 0)
        asm.cmpMI8(RSI, RDX, 0)
        val skip = asm.jcc(CC_E)
        calloutPrologue(false)
        asm.movRR(RDI, RDX, 0)
        call(callbacks.invalidate)
        calloutEpilogue(false)
        asm.patch(skip)
    }

    private fun emitLw(rs: Int, rt: Int, imm: Int, signed: Boolean) {
        flush()
        emitTranslate(rs, imm, 3, false)
        asm.aluRI(ALU_CMP, RCX, RDRAM_SIZE, 0)
        val mmio = asm.jcc(CC_AE)
        asm.movRMIndexed(RAX, R13, RCX, 1, 0, 0)
        val done = asm.jmp()
        asm.patch(mmio)
        calloutPrologue(false)
        asm.movRR(RDI, RCX, 0)
        call(callbacks.read32)
        calloutEpilogue(false)
        asm.patch(done)
        if (rt != 0) {
            if (signed) asm.movsxdRR(RAX, RAX) else asm.movRR(RAX, RAX, 0)
            storeG(rt, RAX)
        }
    }

    private fun emitLoadByte(rs: Int, rt: Int, imm: Int, signed: Boolean) {
        flush()
        emitTranslate(rs, imm, 0, false)
        asm.aluRI(ALU_CMP, RCX, RDRAM_SIZE, 0)
        val mmio = asm.jcc(CC_AE)
        asm.movRR(RDX, RCX, 0)
        asm.aluRI(ALU_XOR, RDX, 3, 0)
        if (signed) asm.movsx8RMIndexed(RAX, R13, RDX) else asm.movzx8RMIndexed(RAX, R13, RDX)
        val done = asm.jmp()
        asm.patch(mmio)
        calloutPrologue(false)
        asm.movRR(RDI, RCX, 0)
        call(callbacks.read8)
        calloutEpilogue(false)
        if (signed) asm.movsx8RR(RAX, RAX) else asm.movRR(RAX, RAX, 0)
        asm.patch(done)
        storeG(rt, RAX)
    }

    private fun emitLoadHalf(rs: Int, rt: Int, imm: Int, signed: Boolean) {
        flush()
        emitTranslate(rs, imm, 1, false)
        asm.aluRI(ALU_CMP, RCX, RDRAM_SIZE, 0)
        val mmio = asm.jcc(CC_AE)
        asm.movRR(RDX, RCX, 0)
        asm.aluRI(ALU_XOR, RDX, 2, 0)
        asm.movzx16RMIndexed(RAX, R13, RDX)
        val done = asm.jmp()
        asm.patch(mmio)
        calloutPrologue(false)
        asm.movRR(RDI, RCX, 0)
        call(callbacks.read16)
        calloutEpilogue(false)
        asm.patch(done)
        if (signed) asm.movsx16RR(RAX, RAX) else asm.movRR(RAX, RAX, 0)
        storeG(rt, RAX)
    }

    private fun emitLd(rs: Int, rt: Int, imm: Int) {
        flush()
        emitTranslate(rs, imm, 7, false)
        asm.aluRI(ALU_CMP, RCX, RDRAM_SIZE, 0)
        val mmio = asm.jcc(CC_AE)
        asm.movRMIndexed(RAX, R13, RCX, 1, 0, 0)
        asm.shiftRI(SH_SHL, RAX, 32, 1)
        asm.movRMIndexed(RDX, R13, RCX, 1, 4, 0)
        asm.aluRR(ALU_OR, RAX, RDX, 1)
        val done = asm.jmp()
        asm.patch(mmio)
        calloutPrologue(false)
        asm.movRR(RDI, RCX, 0)
        call(callbacks.read64)
        calloutEpilogue(false)
        asm.patch(done)
        storeG(rt, RAX)
    }

    private fun emitLwSide(rs: Int, rt: Int, imm: Int, left: Boolean) {
        flush()
        emitTranslate(rs, imm, 0, false)
        asm.movRR(RDX, RCX, 0)
        asm.aluRI(ALU_AND, RDX, -4, 0)
        asm.aluRI(ALU_CMP, RDX, RDRAM_SIZE, 0)
        val mmio = asm.jcc(CC_AE)
        asm.movRMIndexed(RAX, R13, RDX, 1, 0, 0)
        val done = asm.jmp()
        asm.patch(mmio)
        calloutPrologue(true)
        asm.movRR(RDI, RDX, 0)
        call(callbacks.read32)
        calloutEpilogue(true)
        asm.patch(done)

        asm.movRR(R8, RCX, 0)
        asm.aluRI(ALU_AND, R8, 3, 0)
        asm.shiftRI(SH_SHL, R8, 3, 0)

        if (left) {
            asm.movRR(RCX, R8, 0)
            asm.movRI32(RDX, 1)
            asm.shiftRC(SH_SHL, RDX, 0)
            asm.aluRI(ALU_SUB, RDX, 1, 0)
            asm.movRM(R9, R12, rt * 8, 0)
            asm.aluRR(ALU_AND, R9, RDX, 0)
            asm.shiftRC(SH_SHL, RAX, 0)
            asm.aluRR(ALU_OR, RAX, R9, 0)
        } else {
            asm.movRI32(R9, 24)
            asm.aluRR(ALU_SUB, R9, R8, 0)
            asm.movRI32(RCX, 32)
            asm.aluRR(ALU_SUB, RCX, R9, 0)
            asm.movRI32sx(RDX, -1)
            asm.shiftRC(SH_SHL, RDX, 1)
            asm.movRM(R8, R12, rt * 8, 0)
            asm.aluRR(ALU_AND, R8, RDX, 0)
            asm.movRR(RCX, R9, 0)
            asm.shiftRC(SH_SHR, RAX, 0)
            asm.aluRR(ALU_OR, RAX, R8, 0)
        }
        storeSx32(rt)
    }

    private fun emitStoreCommon(rs: Int, imm: Int, alignMask: Int): Pair<Int, Int> {
        flush()
        emitTranslate(rs, imm, alignMask, true)
        asm.aluRI(ALU_CMP, RCX, RDRAM_SIZE, 0)
        val mmio1 = asm.jcc(CC_AE)
        asm.cmpMI8Disp(RBX, CTX_INITMODE, 0)
        val mmio2 = asm.jcc(CC_NE)
        return Pair(mmio1, mmio2)
    }

    private fun emitSw(rs: Int, rt: Int, imm: Int) {
        val (m1, m2) = emitStoreCommon(rs, imm, 3)
        loadG(RAX, rt, 0)
        asm.movMRIndexed(R13, RCX, 1, 0, RAX, 0)
        emitInvalidateCheck(RCX)
        val end = asm.jmp()
        asm.patch(m1)
        asm.patch(m2)
        loadG(RAX, rt, 0)
        calloutPrologue(false)
        asm.movRR(RSI, RAX, 0)
        asm.movRR(RDI, RCX, 0)
        call(callbacks.write32)
        calloutEpilogue(false)
        asm.patch(end)
    }

    private fun emitSb(rs: Int, rt: Int, imm: Int) {
        val (m1, m2) = emitStoreCommon(rs, imm, 0)
        loadG(RAX, rt, 0)
        asm.movRR(RDX, RCX, 0)
        asm.aluRI(ALU_XOR, RDX, 3, 0)
        asm.movMR8Indexed(R13, RDX, RAX)
        emitInvalidateCheck(RCX)
        val end = asm.jmp()
        asm.patch(m1)
        asm.patch(m2)
        loadG(RAX, rt, 0)
        calloutPrologue(false)
        asm.movRR(RSI, RAX, 0)
        asm.movRR(RDI, RCX, 0)
        call(callbacks.write8)
        calloutEpilogue(false)
        asm.patch(end)
    }

    private fun emitSh(rs: Int, rt: Int, imm: Int) {
        val (m1, m2) = emitStoreCommon(rs, imm, 1)
        loadG(RAX, rt, 0)
        asm.movRR(RSI, RCX, 0)
        asm.aluRI(ALU_XOR, RSI, 2, 0)
        asm.movMR16Indexed(R13, RSI, RAX)
        emitInvalidateCheck(RCX)
        val end = asm.jmp()
        asm.patch(m1)
        asm.patch(m2)
        loadG(RAX, rt, 0)
        calloutPrologue(false)
        asm.movRR(RSI, RAX, 0)
        asm.movRR(RDI, RCX, 0)
        call(callbacks.write16)
        calloutEpilogue(false)
        asm.patch(end)
    }

    private fun emitSd(rs: Int, rt: Int, imm: Int) {
        val (m1, m2) = emitStoreCommon(rs, imm, 7)
        loadG(RAX, rt, 1)
        asm.movRR(RDX, RAX, 1)
        asm.shiftRI(SH_SHR, RDX, 32, 1)
        asm.movMRIndexed(R13, RCX, 1, 0, RDX, 0)
        asm.movMRIndexed(R13, RCX, 1, 4, RAX, 0)
        emitInvalidateCheck(RCX)
        val end = asm.jmp()
        asm.patch(m1)
        asm.patch(m2)
        loadG(RAX, rt, 1)
        calloutPrologue(false)
        asm.movRR(RSI, RAX, 1)
        asm.movRR(RDI, RCX, 0)
        call(callbacks.write64)
        calloutEpilogue(false)
        asm.patch(end)
    }

    private fun emitSwSide(rs: Int, rt: Int, imm: Int, left: Boolean) {
        flush()
        emitTranslate(rs, imm, 0, true)
        asm.movRR(R9, RCX, 0)
        asm.movRR(R8, RCX, 0)
        asm.aluRI(ALU_AND, R8, 3, 0)
        asm.shiftRI(SH_SHL, R8, 3, 0)
        if (!left) {
            asm.movRI32(R10, 24)
            asm.aluRR(ALU_SUB, R10, R8, 0)
            asm.movRR(R8, R10, 0)
        }
        loadG(RAX, rt, 0)
        asm.movRR(RCX, R8, 0)
        if (left) {
            asm.shiftRC(SH_SHR, RAX, 0)
            asm.movRI32(RDX, -1)
            asm.shiftRC(SH_SHR, RDX, 0)
        } else {
            asm.shiftRC(SH_SHL, RAX, 0)
            asm.movRI32(RDX, -1)
            asm.shiftRC(SH_SHL, RDX, 0)
        }
        asm.movRR(R10, R9, 0)
        asm.aluRI(ALU_AND, R10, -4, 0)
        asm.aluRI(ALU_CMP, R10, RDRAM_SIZE, 0)
        val mmio1 = asm.jcc(CC_AE)
        asm.cmpMI8Disp(RBX, CTX_INITMODE, 0)
        val mmio2 = asm.jcc(CC_NE)
        asm.movRMIndexed(R8, R13, R10, 1, 0, 0)
        asm.movRR(RSI, RDX, 0)
        asm.onePop(2, RSI, 0)
        asm.aluRR(ALU_AND, R8, RSI, 0)
        asm.aluRR(ALU_AND, RAX, RDX, 0)
        asm.aluRR(ALU_OR, R8, RAX, 0)
        asm.movMRIndexed(R13, R10, 1, 0, R8, 0)
        emitInvalidateCheck(R10)
        val end = asm.jmp()
        asm.patch(mmio1)
        asm.patch(mmio2)
        calloutPrologue(false)
        asm.movRR(RDI, R10, 0)
        asm.movRR(RSI, RAX, 0)
        call(callbacks.writeMasked)
        calloutEpilogue(false)
        asm.patch(end)
    }
}
