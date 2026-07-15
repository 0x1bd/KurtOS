package n64.core

const val COP0_INDEX = 0
const val COP0_RANDOM = 1
const val COP0_ENTRYLO0 = 2
const val COP0_ENTRYLO1 = 3
const val COP0_CONTEXT = 4
const val COP0_PAGEMASK = 5
const val COP0_WIRED = 6
const val COP0_BADVADDR = 8
const val COP0_COUNT = 9
const val COP0_ENTRYHI = 10
const val COP0_COMPARE = 11
const val COP0_STATUS = 12
const val COP0_CAUSE = 13
const val COP0_EPC = 14
const val COP0_PRID = 15
const val COP0_CONFIG = 16
const val COP0_LLADDR = 17
const val COP0_XCONTEXT = 20
const val COP0_TAGLO = 28
const val COP0_ERROREPC = 30
const val COP0_PARITYERROR = 26

const val STATUS_IE = 1L shl 0
const val STATUS_EXL = 1L shl 1
const val STATUS_ERL = 1L shl 2
const val STATUS_UX = 1L shl 5
const val STATUS_SX = 1L shl 6
const val STATUS_KX = 1L shl 7
const val STATUS_BEV = 1L shl 22
const val STATUS_FR = 1L shl 26
const val STATUS_CU1 = 1L shl 29
const val STATUS_CU2 = 1L shl 30

const val COP0_CAUSE_IP2 = 1L shl 10
const val COP0_CAUSE_IP7 = 1L shl 15
const val CAUSE_BD = 1L shl 31
const val CAUSE_IP_MASK = 0xFF00L
const val CAUSE_EXCCODE_MASK = 0x7CL

const val EXC_MOD = 1L shl 2
const val EXC_TLBL = 2L shl 2
const val EXC_TLBS = 3L shl 2
const val EXC_ADEL = 4L shl 2
const val EXC_ADES = 5L shl 2
const val EXC_SYS = 8L shl 2
const val EXC_BP = 9L shl 2
const val EXC_RI = 10L shl 2
const val EXC_CPU = 11L shl 2
const val EXC_OV = 12L shl 2
const val EXC_TR = 13L shl 2
const val EXC_FPE = 15L shl 2

const val FP_INEXACT = 1
const val FP_UNDERFLOW = 2
const val FP_OVERFLOW = 4
const val FP_DIVZERO = 8
const val FP_INVALID = 16

const val QUIET_SINGLE = 0x7FBFFFFF
const val QUIET_DOUBLE = 0x7FF7FFFFFFFFFFFFL

const val SMALLEST_NORMAL_SINGLE = 1.1754943508222875E-38

const val STATE_STEP = 0
const val STATE_TAKE = 1
const val STATE_NOT_TAKEN = 2
const val STATE_DELAY_TAKEN = 3
const val STATE_DELAY_NOT_TAKEN = 4
const val STATE_DISCARD = 5
const val STATE_EXCEPTION = 6

class CPU(private val n64: N64) {
    val gpr = LongArray(32)
    val cop0 = LongArray(32)
    val fgr = LongArray(32)

    var pc = 0L
    var lo = 0L
    var hi = 0L
    var llbit = false
    var fcr31 = 0
    var count = 0L
    private var cop2Latch = 0L

    internal var branchState = STATE_STEP
    internal var branchTarget = 0L
    private var fr = false
    private val codePages = n64.codePages

    private val cop0WriteMask = LongArray(32)

    private val tlbMask = LongArray(32)
    private val tlbVpn2 = LongArray(32)
    private val tlbRegion = IntArray(32)
    private val tlbGlobal = IntArray(32)
    private val tlbAsid = IntArray(32)
    private val tlbPfn = Array(2) { LongArray(32) }
    private val tlbCache = Array(2) { IntArray(32) }
    private val tlbDirty = Array(2) { IntArray(32) }
    private val tlbValid = Array(2) { IntArray(32) }
    private val tlbStart = Array(2) { LongArray(32) }
    private val tlbEnd = Array(2) { LongArray(32) }
    private val tlbPhys = Array(2) { LongArray(32) }

    internal val tlbLutR = IntArray(0x100000)
    internal val tlbLutW = IntArray(0x100000)

    private var exception = false

    var instructions = 0L
        internal set

    var debugProfile = false
    val debugSamples = HashMap<Int, Int>()
    var debugBreakPc = 0L

    private fun debugBreakHit() {
        val text = StringBuilder()
        val base = gpr[4].toInt()
        for (o in 0 until 96) {
            val b = n64.ramRead8((base and 0x7FFFFFFF) + o)
            if (b == 0) break
            text.append(if (b in 32..126) b.toChar() else '.')
        }
        println(
            "[break] f=${n64.frameCount} a0=0x${gpr[4].toUInt().toString(16)} '$text'" +
                " a1=0x${gpr[5].toUInt().toString(16)} a2=0x${gpr[6].toUInt().toString(16)}" +
                " ra=0x${gpr[31].toUInt().toString(16)}",
        )
    }

    fun reset() {
        gpr.fill(0)
        cop0.fill(0)
        fgr.fill(0)
        tlbLutR.fill(0)
        tlbLutW.fill(0)
        for (half in 0 until 2) {
            tlbPfn[half].fill(0)
            tlbCache[half].fill(0)
            tlbDirty[half].fill(0)
            tlbValid[half].fill(0)
            tlbStart[half].fill(0)
            tlbEnd[half].fill(0)
            tlbPhys[half].fill(0)
        }
        tlbMask.fill(0)
        tlbVpn2.fill(0)
        tlbRegion.fill(0)
        tlbGlobal.fill(0)
        tlbAsid.fill(0)

        pc = 0
        lo = 0
        hi = 0
        llbit = false
        fcr31 = 0
        count = 0
        cop2Latch = 0
        branchState = STATE_STEP
        branchTarget = 0
        fr = false
        exception = false
        instructions = 0

        setWriteMasks()
    }

    private fun setWriteMasks() {
        cop0WriteMask[COP0_INDEX] = 0x8000003FL
        cop0WriteMask[COP0_RANDOM] = 0
        cop0WriteMask[COP0_ENTRYLO0] = 0x3FFFFFFFL
        cop0WriteMask[COP0_ENTRYLO1] = 0x3FFFFFFFL
        cop0WriteMask[COP0_CONTEXT] = -0x800000L
        cop0WriteMask[COP0_PAGEMASK] = 0x01FFE000L
        cop0WriteMask[COP0_WIRED] = 0x3FL
        cop0WriteMask[COP0_BADVADDR] = 0
        cop0WriteMask[COP0_COUNT] = 0xFFFFFFFFL
        cop0WriteMask[COP0_ENTRYHI] = (3L shl 62) or (0x7FFFFFFL shl 13) or 0xFFL
        cop0WriteMask[COP0_COMPARE] = 0xFFFFFFFFL
        cop0WriteMask[COP0_STATUS] = 0xFF57FFFFL
        cop0WriteMask[COP0_CAUSE] = 0x300L
        cop0WriteMask[COP0_EPC] = -1L
        cop0WriteMask[COP0_PRID] = 0
        cop0WriteMask[COP0_CONFIG] = 0x0F00800FL
        cop0WriteMask[COP0_LLADDR] = 0xFFFFFFFFL
        cop0WriteMask[18] = 0xFFFFFFFBL
        cop0WriteMask[19] = 0xFL
        cop0WriteMask[COP0_XCONTEXT] = -0x10000000000000L
        cop0WriteMask[COP0_TAGLO] = 0x0FFFFFC0L
        cop0WriteMask[COP0_ERROREPC] = -1L
        cop0WriteMask[COP0_PARITYERROR] = 0xFFL
        cop0WriteMask[7] = -1L
        for (reg in 21..25) cop0WriteMask[reg] = -1L
        cop0WriteMask[31] = -1L
    }

    fun setFpuRegisterMode() {
        fr = cop0[COP0_STATUS] and STATUS_FR != 0L
    }

    fun addCycles(cycles: Long) {
        count += cycles
    }

    private fun readWord(physical: Int): Int =
        if (physical.toUInt() < RDRAM_SIZE.toUInt()) n64.rdram[physical ushr 2] else n64.read32(physical)

    private fun writeWord(physical: Int, value: Int, mask: Int) {
        if (physical.toUInt() < RDRAM_SIZE.toUInt() && !n64.mi.initMode) {
            val index = physical ushr 2
            n64.rdram[index] = (n64.rdram[index] and mask.inv()) or (value and mask)
            if (codePages[physical ushr CPU_PAGE_SHIFT].toInt() != 0) {
                n64.dynarec?.invalidatePage(physical ushr CPU_PAGE_SHIFT)
            }
            return
        }
        n64.write32(physical, value, mask)
    }

    private fun resolveAddress(wide: Long, alignment: Int, write: Boolean): Int {
        if (wide and (alignment - 1).toLong() != 0L) {
            addressException64(wide, write)
            return BAD_ADDRESS
        }
        if (wide == wide.toInt().toLong()) return translate(wide.toInt(), write)
        if (!addressing64()) {
            addressException64(wide, write)
            return BAD_ADDRESS
        }
        return translate64(wide, write)
    }

    var nextEventCount = Long.MAX_VALUE
    var frameDone = false
    var frameDeadline = Long.MAX_VALUE

    fun run() {
        val dynarec = n64.dynarec
        if (dynarec != null) {
            dynarec.run()
            return
        }
        while (!frameDone && count < frameDeadline) {
            step()
        }
    }

    fun step() {
        gpr[0] = 0

        if (debugBreakPc != 0L && pc.toInt() == debugBreakPc.toInt()) debugBreakHit()

        if (pc and 3L != 0L) {
            addressException64(pc, false)
            afterInstruction()
            return
        }

        val physical: Int
        if (pc == pc.toInt().toLong()) {
            physical = translate(pc.toInt(), false)
        } else if (addressing64()) {
            physical = translate64(pc, false)
        } else {
            addressException64(pc, false)
            afterInstruction()
            return
        }
        if (physical == BAD_ADDRESS) {
            afterInstruction()
            return
        }

        val opcode = readWord(physical)
        execute(opcode)
        afterInstruction()
    }

    private fun afterInstruction() {
        when (branchState) {
            STATE_STEP -> pc += 4
            STATE_TAKE -> {
                pc += 4
                branchState = STATE_DELAY_TAKEN
            }

            STATE_NOT_TAKEN -> {
                pc += 4
                branchState = STATE_DELAY_NOT_TAKEN
            }

            STATE_DELAY_TAKEN -> {
                pc = branchTarget
                branchState = STATE_STEP
            }

            STATE_DELAY_NOT_TAKEN -> {
                pc += 4
                branchState = STATE_STEP
            }

            STATE_DISCARD -> {
                pc += 8
                branchState = STATE_STEP
            }

            STATE_EXCEPTION -> branchState = STATE_STEP
        }

        count++
        instructions++

        if (debugProfile && (instructions and 0xFF) == 0L) {
            val key = pc.toInt()
            debugSamples[key] = (debugSamples[key] ?: 0) + 1
        }

        if (!suppressAutoEvents && count > nextEventCount) n64.triggerEvent()
    }

    var suppressAutoEvents = false

    private fun inDelaySlot(): Boolean =
        branchState == STATE_DELAY_TAKEN || branchState == STATE_DELAY_NOT_TAKEN

    private fun branch(condition: Boolean, offset: Int) {
        if (condition) {
            branchTarget = pc + 4 + (offset.toLong() shl 2)
            branchState = STATE_TAKE
        } else {
            branchState = STATE_NOT_TAKEN
        }
    }

    private fun branchLikely(condition: Boolean, offset: Int) {
        if (condition) {
            branchTarget = pc + 4 + (offset.toLong() shl 2)
            branchState = STATE_TAKE
        } else {
            branchState = STATE_DISCARD
        }
    }

    private fun execute(op: Int) {
        val rs = (op ushr 21) and 0x1F
        val rt = (op ushr 16) and 0x1F
        val rd = (op ushr 11) and 0x1F
        val sa = (op ushr 6) and 0x1F
        val imm = op.toShort().toInt()
        val uimm = op and 0xFFFF

        when (op ushr 26) {
            0 -> special(op, rs, rt, rd, sa)
            1 -> regimm(op, rs, rt, imm)

            2 -> {
                branchTarget = (pc + 4) and 0xFFFFFFFFF0000000uL.toLong() or ((op and 0x3FFFFFF).toLong() shl 2)
                branchState = STATE_TAKE
            }

            3 -> {
                gpr[31] = pc + 8
                branchTarget = (pc + 4) and 0xFFFFFFFFF0000000uL.toLong() or ((op and 0x3FFFFFF).toLong() shl 2)
                branchState = STATE_TAKE
            }

            4 -> branch(gpr[rs] == gpr[rt], imm)
            5 -> branch(gpr[rs] != gpr[rt], imm)
            6 -> branch(gpr[rs] <= 0, imm)
            7 -> branch(gpr[rs] > 0, imm)

            8 -> {
                val a = gpr[rs].toInt()
                val result = a + imm
                if (overflowAdd(a, imm, result)) overflowException() else gpr[rt] = result.toLong()
            }

            9 -> gpr[rt] = (gpr[rs].toInt() + imm).toLong()
            10 -> gpr[rt] = if (gpr[rs] < imm.toLong()) 1 else 0
            11 -> gpr[rt] = if (gpr[rs].toULong() < imm.toLong().toULong()) 1 else 0
            12 -> gpr[rt] = gpr[rs] and uimm.toLong()
            13 -> gpr[rt] = gpr[rs] or uimm.toLong()
            14 -> gpr[rt] = gpr[rs] xor uimm.toLong()
            15 -> gpr[rt] = (uimm shl 16).toLong()

            16 -> cop0Op(op, rs, rt, rd)
            17 -> cop1Op(op, rs, rt, rd, sa, imm)
            18 -> cop2Op(op, rs, rt)

            20 -> branchLikely(gpr[rs] == gpr[rt], imm)
            21 -> branchLikely(gpr[rs] != gpr[rt], imm)
            22 -> branchLikely(gpr[rs] <= 0, imm)
            23 -> branchLikely(gpr[rs] > 0, imm)

            24 -> {
                val result = gpr[rs] + imm.toLong()
                if (overflowAdd64(gpr[rs], imm.toLong(), result)) overflowException() else gpr[rt] = result
            }

            25 -> gpr[rt] = gpr[rs] + imm.toLong()
            26 -> ldl(rs, rt, imm)
            27 -> ldr(rs, rt, imm)

            32 -> load8(rs, rt, imm, true)
            33 -> load16(rs, rt, imm, true)
            34 -> lwl(rs, rt, imm)
            35 -> load32(rs, rt, imm, true)
            36 -> load8(rs, rt, imm, false)
            37 -> load16(rs, rt, imm, false)
            38 -> lwr(rs, rt, imm)
            39 -> load32(rs, rt, imm, false)

            40 -> store8(rs, rt, imm)
            41 -> store16(rs, rt, imm)
            42 -> swl(rs, rt, imm)
            43 -> store32(rs, rt, imm)
            44 -> sdl(rs, rt, imm)
            45 -> sdr(rs, rt, imm)
            46 -> swr(rs, rt, imm)
            47 -> {}

            48 -> {
                val physical = resolveAddress(gpr[rs] + imm.toLong(), 4, false)
                if (physical != BAD_ADDRESS) {
                    gpr[rt] = readWord(physical).toLong()
                    cop0[COP0_LLADDR] = physical.toLong() ushr 4
                    llbit = true
                }
            }

            49 -> lwc1(rs, rt, imm)
            52 -> {
                val physical = resolveAddress(gpr[rs] + imm.toLong(), 8, false)
                if (physical != BAD_ADDRESS) {
                    val high = readWord(physical).toLong() shl 32
                    gpr[rt] = high or (readWord(physical + 4).toLong() and 0xFFFFFFFFL)
                    cop0[COP0_LLADDR] = physical.toLong() ushr 4
                    llbit = true
                }
            }

            53 -> ldc1(rs, rt, imm)
            55 -> load64(rs, rt, imm)

            56 -> {
                if (llbit) {
                    store32(rs, rt, imm)
                    gpr[rt] = 1
                } else {
                    gpr[rt] = 0
                }
            }

            57 -> swc1(rs, rt, imm)
            60 -> {
                if (llbit) {
                    store64(rs, rt, imm)
                    gpr[rt] = 1
                } else {
                    gpr[rt] = 0
                }
            }

            61 -> sdc1(rs, rt, imm)
            63 -> store64(rs, rt, imm)

            else -> reservedException()
        }
    }

    private fun special(op: Int, rs: Int, rt: Int, rd: Int, sa: Int) {
        when (op and 0x3F) {
            0 -> gpr[rd] = (gpr[rt].toInt() shl sa).toLong()
            2 -> gpr[rd] = (gpr[rt].toInt() ushr sa).toLong()
            3 -> gpr[rd] = (gpr[rt] shr sa).toInt().toLong()
            4 -> gpr[rd] = (gpr[rt].toInt() shl (gpr[rs].toInt() and 0x1F)).toLong()
            6 -> gpr[rd] = (gpr[rt].toInt() ushr (gpr[rs].toInt() and 0x1F)).toLong()
            7 -> gpr[rd] = (gpr[rt] shr (gpr[rs].toInt() and 0x1F)).toInt().toLong()

            8 -> {
                branchTarget = gpr[rs]
                branchState = STATE_TAKE
            }

            9 -> {
                branchTarget = gpr[rs]
                gpr[rd] = pc + 8
                branchState = STATE_TAKE
            }

            12 -> syscallException()
            13 -> breakException()
            15 -> {}

            16 -> gpr[rd] = hi
            17 -> hi = gpr[rs]
            18 -> gpr[rd] = lo
            19 -> lo = gpr[rs]

            20 -> gpr[rd] = gpr[rt] shl (gpr[rs].toInt() and 0x3F)
            22 -> gpr[rd] = gpr[rt] ushr (gpr[rs].toInt() and 0x3F)
            23 -> gpr[rd] = gpr[rt] shr (gpr[rs].toInt() and 0x3F)

            24 -> {
                val result = gpr[rs].toInt().toLong() * gpr[rt].toInt().toLong()
                lo = result.toInt().toLong()
                hi = (result shr 32).toInt().toLong()
                addCycles(4)
            }

            25 -> {
                val result = (gpr[rs].toInt().toLong() and 0xFFFFFFFFL) * (gpr[rt].toInt().toLong() and 0xFFFFFFFFL)
                lo = result.toInt().toLong()
                hi = (result shr 32).toInt().toLong()
                addCycles(4)
            }

            26 -> {
                val dividend = gpr[rs].toInt()
                val divisor = gpr[rt].toInt()
                if (divisor == 0) {
                    lo = if (dividend < 0) 1L else -1L
                    hi = dividend.toLong()
                } else if (dividend == Int.MIN_VALUE && divisor == -1) {
                    lo = Int.MIN_VALUE.toLong()
                    hi = 0
                } else {
                    lo = (dividend / divisor).toLong()
                    hi = (dividend % divisor).toLong()
                }
                addCycles(36)
            }

            27 -> {
                val dividend = gpr[rs].toInt().toLong() and 0xFFFFFFFFL
                val divisor = gpr[rt].toInt().toLong() and 0xFFFFFFFFL
                if (divisor == 0L) {
                    lo = -1L
                    hi = dividend.toInt().toLong()
                } else {
                    lo = (dividend / divisor).toInt().toLong()
                    hi = (dividend % divisor).toInt().toLong()
                }
                addCycles(36)
            }

            28 -> {
                val a = gpr[rs]
                val b = gpr[rt]
                lo = a * b
                hi = multiplyHigh(a, b)
                addCycles(7)
            }

            29 -> {
                val a = gpr[rs]
                val b = gpr[rt]
                lo = a * b
                hi = multiplyHighUnsigned(a, b)
                addCycles(7)
            }

            30 -> {
                val dividend = gpr[rs]
                val divisor = gpr[rt]
                if (divisor == 0L) {
                    lo = if (dividend < 0) 1L else -1L
                    hi = dividend
                } else if (dividend == Long.MIN_VALUE && divisor == -1L) {
                    lo = Long.MIN_VALUE
                    hi = 0
                } else {
                    lo = dividend / divisor
                    hi = dividend % divisor
                }
                addCycles(68)
            }

            31 -> {
                val dividend = gpr[rs].toULong()
                val divisor = gpr[rt].toULong()
                if (divisor == 0uL) {
                    lo = -1L
                    hi = dividend.toLong()
                } else {
                    lo = (dividend / divisor).toLong()
                    hi = (dividend % divisor).toLong()
                }
                addCycles(68)
            }

            32 -> {
                val a = gpr[rs].toInt()
                val b = gpr[rt].toInt()
                val result = a + b
                if (overflowAdd(a, b, result)) overflowException() else gpr[rd] = result.toLong()
            }

            33 -> gpr[rd] = (gpr[rs].toInt() + gpr[rt].toInt()).toLong()

            34 -> {
                val a = gpr[rs].toInt()
                val b = gpr[rt].toInt()
                val result = a - b
                if (overflowSub(a, b, result)) overflowException() else gpr[rd] = result.toLong()
            }

            35 -> gpr[rd] = (gpr[rs].toInt() - gpr[rt].toInt()).toLong()
            36 -> gpr[rd] = gpr[rs] and gpr[rt]
            37 -> gpr[rd] = gpr[rs] or gpr[rt]
            38 -> gpr[rd] = gpr[rs] xor gpr[rt]
            39 -> gpr[rd] = (gpr[rs] or gpr[rt]).inv()
            42 -> gpr[rd] = if (gpr[rs] < gpr[rt]) 1 else 0
            43 -> gpr[rd] = if (gpr[rs].toULong() < gpr[rt].toULong()) 1 else 0

            44 -> {
                val result = gpr[rs] + gpr[rt]
                if (overflowAdd64(gpr[rs], gpr[rt], result)) overflowException() else gpr[rd] = result
            }

            45 -> gpr[rd] = gpr[rs] + gpr[rt]

            46 -> {
                val result = gpr[rs] - gpr[rt]
                if (overflowSub64(gpr[rs], gpr[rt], result)) overflowException() else gpr[rd] = result
            }

            47 -> gpr[rd] = gpr[rs] - gpr[rt]

            48 -> if (gpr[rs] >= gpr[rt]) trapException()
            49 -> if (gpr[rs].toULong() >= gpr[rt].toULong()) trapException()
            50 -> if (gpr[rs] < gpr[rt]) trapException()
            51 -> if (gpr[rs].toULong() < gpr[rt].toULong()) trapException()
            52 -> if (gpr[rs] == gpr[rt]) trapException()
            54 -> if (gpr[rs] != gpr[rt]) trapException()

            56 -> gpr[rd] = gpr[rt] shl sa
            58 -> gpr[rd] = gpr[rt] ushr sa
            59 -> gpr[rd] = gpr[rt] shr sa
            60 -> gpr[rd] = gpr[rt] shl (sa + 32)
            62 -> gpr[rd] = gpr[rt] ushr (sa + 32)
            63 -> gpr[rd] = gpr[rt] shr (sa + 32)

            else -> reservedException()
        }
    }

    private fun regimm(op: Int, rs: Int, rt: Int, imm: Int) {
        when (rt) {
            0 -> branch(gpr[rs] < 0, imm)
            1 -> branch(gpr[rs] >= 0, imm)
            2 -> branchLikely(gpr[rs] < 0, imm)
            3 -> branchLikely(gpr[rs] >= 0, imm)
            8 -> if (gpr[rs] >= imm.toLong()) trapException()
            9 -> if (gpr[rs].toULong() >= imm.toLong().toULong()) trapException()
            10 -> if (gpr[rs] < imm.toLong()) trapException()
            11 -> if (gpr[rs].toULong() < imm.toLong().toULong()) trapException()
            12 -> if (gpr[rs] == imm.toLong()) trapException()
            14 -> if (gpr[rs] != imm.toLong()) trapException()

            16 -> {
                val condition = gpr[rs] < 0
                gpr[31] = pc + 8
                branch(condition, imm)
            }

            17 -> {
                val condition = gpr[rs] >= 0
                gpr[31] = pc + 8
                branch(condition, imm)
            }

            18 -> {
                val condition = gpr[rs] < 0
                gpr[31] = pc + 8
                branchLikely(condition, imm)
            }

            19 -> {
                val condition = gpr[rs] >= 0
                gpr[31] = pc + 8
                branchLikely(condition, imm)
            }

            else -> reservedException()
        }
    }

    private fun multiplyHigh(a: Long, b: Long): Long {
        val aLow = a and 0xFFFFFFFFL
        val aHigh = a shr 32
        val bLow = b and 0xFFFFFFFFL
        val bHigh = b shr 32

        val lowLow = aLow * bLow
        val middle1 = aHigh * bLow + (lowLow ushr 32)
        val middle2 = aLow * bHigh + (middle1 and 0xFFFFFFFFL)

        return aHigh * bHigh + (middle1 shr 32) + (middle2 shr 32)
    }

    private fun multiplyHighUnsigned(a: Long, b: Long): Long {
        val aLow = a and 0xFFFFFFFFL
        val aHigh = a ushr 32
        val bLow = b and 0xFFFFFFFFL
        val bHigh = b ushr 32

        val lowLow = aLow * bLow
        val middle1 = aHigh * bLow + (lowLow ushr 32)
        val middle2 = aLow * bHigh + (middle1 and 0xFFFFFFFFL)

        return aHigh * bHigh + (middle1 ushr 32) + (middle2 ushr 32)
    }

    private fun overflowAdd(a: Int, b: Int, result: Int): Boolean =
        ((a xor result) and (b xor result)) < 0

    private fun overflowSub(a: Int, b: Int, result: Int): Boolean =
        ((a xor b) and (a xor result)) < 0

    private fun overflowAdd64(a: Long, b: Long, result: Long): Boolean =
        ((a xor result) and (b xor result)) < 0

    private fun overflowSub64(a: Long, b: Long, result: Long): Boolean =
        ((a xor b) and (a xor result)) < 0

    private fun load8(rs: Int, rt: Int, imm: Int, signed: Boolean) {
        val physical = resolveAddress(gpr[rs] + imm.toLong(), 1, false)
        if (physical == BAD_ADDRESS) return
        val word = readWord(physical and 3.inv())
        val shift = 24 - ((physical and 3) shl 3)
        val value = (word ushr shift) and 0xFF
        gpr[rt] = if (signed) value.toByte().toLong() else value.toLong()
    }

    private fun load16(rs: Int, rt: Int, imm: Int, signed: Boolean) {
        val physical = resolveAddress(gpr[rs] + imm.toLong(), 2, false)
        if (physical == BAD_ADDRESS) return
        val word = readWord(physical and 3.inv())
        val shift = 16 - ((physical and 2) shl 3)
        val value = (word ushr shift) and 0xFFFF
        gpr[rt] = if (signed) value.toShort().toLong() else value.toLong()
    }

    private fun load32(rs: Int, rt: Int, imm: Int, signed: Boolean) {
        val physical = resolveAddress(gpr[rs] + imm.toLong(), 4, false)
        if (physical == BAD_ADDRESS) return
        val value = readWord(physical)
        gpr[rt] = if (signed) value.toLong() else value.toLong() and 0xFFFFFFFFL
    }

    private fun load64(rs: Int, rt: Int, imm: Int) {
        val physical = resolveAddress(gpr[rs] + imm.toLong(), 8, false)
        if (physical == BAD_ADDRESS) return
        val high = readWord(physical).toLong() shl 32
        val low = readWord(physical + 4).toLong() and 0xFFFFFFFFL
        gpr[rt] = high or low
    }

    private fun store8(rs: Int, rt: Int, imm: Int) {
        val physical = resolveAddress(gpr[rs] + imm.toLong(), 1, true)
        if (physical == BAD_ADDRESS) return
        val shift = 24 - ((physical and 3) shl 3)
        writeWord(physical and 3.inv(), (gpr[rt].toInt() and 0xFF) shl shift, 0xFF shl shift)
    }

    private fun store16(rs: Int, rt: Int, imm: Int) {
        val physical = resolveAddress(gpr[rs] + imm.toLong(), 2, true)
        if (physical == BAD_ADDRESS) return
        val shift = 16 - ((physical and 2) shl 3)
        writeWord(physical and 3.inv(), (gpr[rt].toInt() and 0xFFFF) shl shift, 0xFFFF shl shift)
    }

    private fun store32(rs: Int, rt: Int, imm: Int) {
        val physical = resolveAddress(gpr[rs] + imm.toLong(), 4, true)
        if (physical == BAD_ADDRESS) return
        writeWord(physical, gpr[rt].toInt(), -1)
    }

    private fun store64(rs: Int, rt: Int, imm: Int) {
        val physical = resolveAddress(gpr[rs] + imm.toLong(), 8, true)
        if (physical == BAD_ADDRESS) return
        writeWord(physical, (gpr[rt] ushr 32).toInt(), -1)
        writeWord(physical + 4, gpr[rt].toInt(), -1)
    }

    private fun lwl(rs: Int, rt: Int, imm: Int) {
        val physical = resolveAddress(gpr[rs] + imm.toLong(), 1, false)
        if (physical == BAD_ADDRESS) return
        val word = readWord(physical and 3.inv())
        val shift = (physical and 3) shl 3
        val keep = if (shift == 0) 0 else (1 shl shift) - 1
        gpr[rt] = ((word shl shift) or (gpr[rt].toInt() and keep)).toLong()
    }

    private fun lwr(rs: Int, rt: Int, imm: Int) {
        val physical = resolveAddress(gpr[rs] + imm.toLong(), 1, false)
        if (physical == BAD_ADDRESS) return
        val word = readWord(physical and 3.inv())
        val shift = 24 - ((physical and 3) shl 3)
        val value = if (shift == 0) word else (gpr[rt].toInt() and (-1 shl (32 - shift))) or (word ushr shift)
        gpr[rt] = value.toLong()
    }

    private fun swl(rs: Int, rt: Int, imm: Int) {
        val physical = resolveAddress(gpr[rs] + imm.toLong(), 1, true)
        if (physical == BAD_ADDRESS) return
        val shift = (physical and 3) shl 3
        val mask = -1 ushr shift
        writeWord(physical and 3.inv(), gpr[rt].toInt() ushr shift, mask)
    }

    private fun swr(rs: Int, rt: Int, imm: Int) {
        val physical = resolveAddress(gpr[rs] + imm.toLong(), 1, true)
        if (physical == BAD_ADDRESS) return
        val shift = 24 - ((physical and 3) shl 3)
        val mask = if (shift == 0) -1 else (-1 shl shift)
        writeWord(physical and 3.inv(), gpr[rt].toInt() shl shift, mask)
    }

    private fun ldl(rs: Int, rt: Int, imm: Int) {
        val physical = resolveAddress(gpr[rs] + imm.toLong(), 1, false)
        if (physical == BAD_ADDRESS) return
        val base = physical and 7.inv()
        val value = (readWord(base).toLong() shl 32) or (readWord(base + 4).toLong() and 0xFFFFFFFFL)
        val shift = (physical and 7) shl 3
        val mask = if (shift == 0) 0L else (-1L ushr (64 - shift))
        gpr[rt] = (gpr[rt] and mask) or (value shl shift)
    }

    private fun ldr(rs: Int, rt: Int, imm: Int) {
        val physical = resolveAddress(gpr[rs] + imm.toLong(), 1, false)
        if (physical == BAD_ADDRESS) return
        val base = physical and 7.inv()
        val value = (readWord(base).toLong() shl 32) or (readWord(base + 4).toLong() and 0xFFFFFFFFL)
        val shift = 56 - ((physical and 7) shl 3)
        val mask = if (shift == 0) 0L else (-1L shl (64 - shift))
        gpr[rt] = (gpr[rt] and mask) or (value ushr shift)
    }

    private fun sdl(rs: Int, rt: Int, imm: Int) {
        val physical = resolveAddress(gpr[rs] + imm.toLong(), 1, true)
        if (physical == BAD_ADDRESS) return
        val base = physical and 7.inv()
        val shift = (physical and 7) shl 3
        val value = gpr[rt] ushr shift
        val mask = if (shift == 0) -1L else (-1L ushr shift)
        writeDouble(base, value, mask)
    }

    private fun sdr(rs: Int, rt: Int, imm: Int) {
        val physical = resolveAddress(gpr[rs] + imm.toLong(), 1, true)
        if (physical == BAD_ADDRESS) return
        val base = physical and 7.inv()
        val shift = 56 - ((physical and 7) shl 3)
        val value = gpr[rt] shl shift
        val mask = if (shift == 0) -1L else (-1L shl shift)
        writeDouble(base, value, mask)
    }

    private fun writeDouble(physical: Int, value: Long, mask: Long) {
        writeWord(physical, (value ushr 32).toInt(), (mask ushr 32).toInt())
        writeWord(physical + 4, value.toInt(), mask.toInt())
    }

    private fun cop0Op(op: Int, rs: Int, rt: Int, rd: Int) {
        when (rs) {
            0 -> gpr[rt] = readCop0(rd).toInt().toLong()
            1 -> gpr[rt] = readCop0(rd)
            4 -> writeCop0(rd, gpr[rt].toInt().toLong())
            5 -> writeCop0(rd, gpr[rt])
            in 16..31 -> when (op and 0x3F) {
                1 -> tlbRead(cop0[COP0_INDEX].toInt() and 0x3F)
                2 -> tlbWrite(cop0[COP0_INDEX].toInt() and 0x3F)
                6 -> tlbWrite(randomRegister().toInt())
                8 -> tlbProbe()
                24 -> eret()
                0x20 -> {
                    val target = (op ushr 20) and 0x1F
                    if (target != 0) gpr[target] = 0
                }

                0x25, 0x2C -> {}
                else -> reservedException()
            }

            else -> reservedException()
        }
    }

    private fun readCop0(reg: Int): Long = when (reg) {
        COP0_COUNT -> count ushr 1
        COP0_RANDOM -> randomRegister()
        else -> cop0[reg]
    }

    private fun writeCop0(reg: Int, value: Long) {
        when (reg) {
            COP0_COUNT -> {
                val updated = (value and 0xFFFFFFFFL) shl 1
                n64.translateEvents(count, updated)
                count = updated
                return
            }

            COP0_WIRED -> cop0[COP0_RANDOM] = 31

            COP0_COMPARE -> {
                val target = value and 0xFFFFFFFFL
                val current = (count ushr 1) and 0xFFFFFFFFL
                var difference = (target - current) and 0xFFFFFFFFL
                if (difference == 0L) difference = 0xFFFFFFFFL
                n64.createEvent(EVENT_COMPARE, difference shl 1)
                cop0[COP0_CAUSE] = cop0[COP0_CAUSE] and COP0_CAUSE_IP7.inv()
            }

            COP0_STATUS -> {
                val previous = cop0[COP0_STATUS]
                cop0[COP0_STATUS] = (previous and cop0WriteMask[COP0_STATUS].inv()) or
                    (value and cop0WriteMask[COP0_STATUS])
                if ((previous xor cop0[COP0_STATUS]) and STATUS_FR != 0L) setFpuRegisterMode()
                checkPendingInterrupts()
                return
            }
        }

        cop0[reg] = (cop0[reg] and cop0WriteMask[reg].inv()) or (value and cop0WriteMask[reg])
        checkPendingInterrupts()
    }

    private fun randomRegister(): Long {
        val wired = cop0[COP0_WIRED] and 0x3F
        if (wired > 31) return (-count) and 0x3F
        val span = 32 - wired
        return ((-count) % span + span) % span + wired
    }

    fun compareEvent() {
        cop0[COP0_CAUSE] = cop0[COP0_CAUSE] or COP0_CAUSE_IP7
        cop0[COP0_CAUSE] = cop0[COP0_CAUSE] and CAUSE_EXCCODE_MASK.inv()
        n64.createEventAt(EVENT_COMPARE, nextEventCount + 0xFFFFFFFFL)
        checkPendingInterrupts()
    }

    private fun eret() {
        if (cop0[COP0_STATUS] and STATUS_ERL != 0L) {
            pc = cop0[COP0_ERROREPC]
            cop0[COP0_STATUS] = cop0[COP0_STATUS] and STATUS_ERL.inv()
        } else {
            pc = cop0[COP0_EPC]
            cop0[COP0_STATUS] = cop0[COP0_STATUS] and STATUS_EXL.inv()
        }
        branchState = STATE_EXCEPTION
        llbit = false
        checkPendingInterrupts()
    }

    fun checkPendingInterrupts() {
        val status = cop0[COP0_STATUS]
        if (status and (STATUS_IE or STATUS_EXL or STATUS_ERL) != STATUS_IE) return

        if (n64.mi.regs[MI_INTR] and n64.mi.regs[MI_INTR_MASK] != 0) {
            cop0[COP0_CAUSE] = cop0[COP0_CAUSE] or COP0_CAUSE_IP2
            cop0[COP0_CAUSE] = cop0[COP0_CAUSE] and CAUSE_EXCCODE_MASK.inv()
        }

        if (status and cop0[COP0_CAUSE] and CAUSE_IP_MASK == 0L) return

        if (n64.inEvent) {
            interruptException()
        } else {
            n64.createEvent(EVENT_INT, 0)
        }
    }

    fun interruptException() {
        generalException(0x180)
    }

    private fun syscallException() {
        cop0[COP0_CAUSE] = EXC_SYS
        generalException(0x180)
    }

    private fun breakException() {
        cop0[COP0_CAUSE] = EXC_BP
        generalException(0x180)
    }

    private fun trapException() {
        cop0[COP0_CAUSE] = EXC_TR
        generalException(0x180)
    }

    private fun overflowException() {
        cop0[COP0_CAUSE] = EXC_OV
        generalException(0x180)
    }

    private fun reservedException() {
        cop0[COP0_CAUSE] = EXC_RI
        generalException(0x180)
    }

    var debugCopStatus = 0L
        private set
    var debugCopPc = 0L
        private set

    var debugCopFrame = -1
        private set

    private fun coprocessorException(unit: Long) {
        debugCopStatus = cop0[COP0_STATUS]
        debugCopPc = pc
        if (debugCopFrame < 0) debugCopFrame = n64.frameCount
        cop0[COP0_CAUSE] = EXC_CPU or (unit shl 28)
        generalException(0x180)
    }

    private fun floatingPointException() {
        cop0[COP0_CAUSE] = EXC_FPE
        generalException(0x180)
    }

    private fun addressException(address: Int, write: Boolean) = addressException64(address.toLong(), write)

    private fun addressException64(address: Long, write: Boolean) {
        cop0[COP0_CAUSE] = if (write) EXC_ADES else EXC_ADEL
        setBadAddress(address)
        generalException(0x180)
    }

    private fun setBadAddress(address: Long) {
        cop0[COP0_BADVADDR] = address

        val virtual = address and 0xFFFFFFFFL
        cop0[COP0_CONTEXT] = (cop0[COP0_CONTEXT] and 0x7FFFF0L.inv()) or ((virtual ushr 9) and 0x7FFFF0L)

        val region = (address ushr 62) and 3L
        val badVpn2 = (address ushr 13) and 0x7FFFFFFL
        cop0[COP0_XCONTEXT] =
            (cop0[COP0_XCONTEXT] and 0x1FFFFFFF0L.inv()) or (region shl 31) or (badVpn2 shl 4)
    }

    private fun tlbMissException(address: Long, write: Boolean) {
        cop0[COP0_CAUSE] = if (write) EXC_TLBS else EXC_TLBL

        val virtual = address and 0xFFFFFFFFL
        setBadAddress(address)
        setEntryHi(address)

        var offset = 0x180
        var valid = true

        for (i in 0 until if (address == address.toInt().toLong()) 32 else 0) {
            val aligned = virtual and 3L.inv()
            if (aligned >= tlbStart[0][i] && aligned <= tlbEnd[0][i] && tlbEnd[0][i] != 0L) {
                valid = tlbValid[0][i] != 0
                if (valid && write && tlbDirty[0][i] == 0) {
                    cop0[COP0_CAUSE] = EXC_MOD
                    valid = false
                }
                break
            }
            if (aligned >= tlbStart[1][i] && aligned <= tlbEnd[1][i] && tlbEnd[1][i] != 0L) {
                valid = tlbValid[1][i] != 0
                if (valid && write && tlbDirty[1][i] == 0) {
                    cop0[COP0_CAUSE] = EXC_MOD
                    valid = false
                }
                break
            }
        }

        if (cop0[COP0_STATUS] and STATUS_EXL == 0L && valid) {
            offset = if (addressing64()) 0x080 else 0
        }

        generalException(offset)
    }

    val debugExceptions = IntArray(32)
    var debugLastEpc = 0L
        private set
    var debugLastBad = 0L
        private set

    private fun generalException(offset: Int) {
        val code = ((cop0[COP0_CAUSE] and CAUSE_EXCCODE_MASK) ushr 2).toInt()
        debugExceptions[code and 31]++
        if (code != 0) {
            debugLastEpc = pc
            debugLastBad = cop0[COP0_BADVADDR]
        }

        if (cop0[COP0_STATUS] and STATUS_EXL == 0L) {
            cop0[COP0_EPC] = pc
            if (inDelaySlot()) {
                cop0[COP0_CAUSE] = cop0[COP0_CAUSE] or CAUSE_BD
                cop0[COP0_EPC] = cop0[COP0_EPC] - 4
            } else {
                cop0[COP0_CAUSE] = cop0[COP0_CAUSE] and CAUSE_BD.inv()
            }
        }

        cop0[COP0_STATUS] = cop0[COP0_STATUS] or STATUS_EXL

        pc = if (cop0[COP0_STATUS] and STATUS_BEV == 0L) {
            (0x80000000 + offset).toInt().toLong()
        } else {
            (0xBFC00200.toInt() + offset).toLong()
        }

        branchState = STATE_EXCEPTION
        addCycles(2)
    }

    fun translate(address: Int, write: Boolean): Int {
        if ((address and 0xC0000000.toInt()) == 0x80000000.toInt()) {
            return address and 0x1FFFFFFF
        }
        return translateTlb(address, write)
    }

    private fun translateTlb(address: Int, write: Boolean): Int {
        val page = (address ushr 12) and 0xFFFFF
        val entry = if (write) tlbLutW[page] else tlbLutR[page]
        if (entry != 0) {
            return (entry and 0x1FFFF000) or (address and 0xFFF)
        }
        tlbMissException(address.toLong(), write)
        return BAD_ADDRESS
    }

    private fun addressing64(): Boolean {
        val status = cop0[COP0_STATUS]
        val kernel = status and (STATUS_EXL or STATUS_ERL) != 0L || (status ushr 3) and 3L == 0L
        return when {
            kernel -> status and STATUS_KX != 0L
            (status ushr 3) and 3L == 1L -> status and STATUS_SX != 0L
            else -> status and STATUS_UX != 0L
        }
    }

    private fun translate64(address: Long, write: Boolean): Int {
        val region = ((address ushr 62) and 3).toInt()
        if (region == 2) {
            if (address and 0x07FF_FFFF_0000_0000L != 0L) {
                addressException64(address, write)
                return BAD_ADDRESS
            }
            return address.toInt()
        }

        val inRange = when (region) {
            0 -> address <= 0x0000_00FF_FFFF_FFFFL
            1 -> address and 0x3FFF_FF00_0000_0000L == 0L
            else -> address and 0x3FFF_FF00_0000_0000L == 0L && (address and 0xFF_FFFF_FFFFL) <= 0xFF_7FFF_FFFFL
        }
        if (!inRange) {
            addressException64(address, write)
            return BAD_ADDRESS
        }

        return tlbWalk64(address, write)
    }

    private fun tlbWalk64(address: Long, write: Boolean): Int {
        val target = (address ushr 13) and 0x7FFFFFFL
        val region = ((address ushr 62) and 3).toInt()
        val asid = (cop0[COP0_ENTRYHI] and 0xFF).toInt()

        for (i in 0 until 32) {
            val mask = tlbMask[i]
            if ((target and mask.inv()) != tlbVpn2[i]) continue
            if (tlbRegion[i] != region) continue
            if (tlbGlobal[i] == 0 && tlbAsid[i] != asid) continue

            val pageBits = (mask shl 12) or 0xFFFL
            val half = if (address and (pageBits + 1) != 0L) 1 else 0

            if (tlbValid[half][i] == 0) {
                cop0[COP0_CAUSE] = if (write) EXC_TLBS else EXC_TLBL
                setBadAddress(address)
                setEntryHi(address)
                generalException(0x180)
                return BAD_ADDRESS
            }
            if (write && tlbDirty[half][i] == 0) {
                cop0[COP0_CAUSE] = EXC_MOD
                setBadAddress(address)
                setEntryHi(address)
                generalException(0x180)
                return BAD_ADDRESS
            }
            return ((tlbPfn[half][i] shl 12) or (address and pageBits)).toInt()
        }

        tlbMissException(address, write)
        return BAD_ADDRESS
    }

    private fun setEntryHi(address: Long) {
        cop0[COP0_ENTRYHI] = (cop0[COP0_ENTRYHI] and 0xFFL) or
            ((address ushr 62) shl 62) or (address and 0xFF_FFFF_E000L)
    }

    private fun tlbRead(index: Int) {
        if (index > 31) return
        cop0[COP0_PAGEMASK] = tlbMask[index] shl 13
        cop0[COP0_ENTRYHI] = ((tlbRegion[index].toLong()) shl 62) or (tlbVpn2[index] shl 13) or tlbAsid[index].toLong()
        cop0[COP0_ENTRYLO0] = (tlbPfn[0][index] shl 6) or (tlbCache[0][index].toLong() shl 3) or
            (tlbDirty[0][index].toLong() shl 2) or (tlbValid[0][index].toLong() shl 1) or tlbGlobal[index].toLong()
        cop0[COP0_ENTRYLO1] = (tlbPfn[1][index] shl 6) or (tlbCache[1][index].toLong() shl 3) or
            (tlbDirty[1][index].toLong() shl 2) or (tlbValid[1][index].toLong() shl 1) or tlbGlobal[index].toLong()
    }

    private fun tlbWrite(index: Int) {
        if (index > 31) return
        tlbUnmap(index)

        tlbGlobal[index] = (cop0[COP0_ENTRYLO0] and cop0[COP0_ENTRYLO1] and 1L).toInt()
        for (half in 0 until 2) {
            val entryLo = cop0[if (half == 0) COP0_ENTRYLO0 else COP0_ENTRYLO1]
            tlbPfn[half][index] = (entryLo ushr 6) and 0xFFFFF
            tlbCache[half][index] = ((entryLo ushr 3) and 7).toInt()
            tlbDirty[half][index] = ((entryLo ushr 2) and 1).toInt()
            tlbValid[half][index] = ((entryLo ushr 1) and 1).toInt()
        }
        tlbAsid[index] = (cop0[COP0_ENTRYHI] and 0xFF).toInt()

        var mask = (cop0[COP0_PAGEMASK] ushr 13) and 0xFFF
        mask = mask and 0b101010101010L
        mask = mask or (mask ushr 1)
        tlbMask[index] = mask

        tlbVpn2[index] = ((cop0[COP0_ENTRYHI] ushr 13) and 0x7FFFFFF) and mask.inv()
        tlbRegion[index] = ((cop0[COP0_ENTRYHI] ushr 62) and 3).toInt()

        tlbStart[0][index] = (tlbVpn2[index] shl 13) and 0xFFFFFFFFL
        tlbEnd[0][index] = tlbStart[0][index] + (mask shl 12) + 0xFFF
        tlbPhys[0][index] = tlbPfn[0][index] shl 12

        tlbStart[1][index] = tlbEnd[0][index] + 1
        tlbEnd[1][index] = tlbStart[1][index] + (mask shl 12) + 0xFFF
        tlbPhys[1][index] = tlbPfn[1][index] shl 12

        tlbMap(index)
    }

    private fun tlbUnmap(index: Int) {
        for (half in 0 until 2) {
            if (tlbValid[half][index] == 0) continue
            var at = tlbStart[half][index]
            while (at < tlbEnd[half][index]) {
                val page = ((at ushr 12) and 0xFFFFF).toInt()
                tlbLutR[page] = 0
                tlbLutW[page] = 0
                at += 0x1000
            }
        }
    }

    private fun tlbMap(index: Int) {
        for (half in 0 until 2) {
            if (tlbValid[half][index] == 0) continue
            val start = tlbStart[half][index]
            val end = tlbEnd[half][index]
            if (start >= end) continue
            if (start >= 0x80000000L && end < 0xC0000000L) continue
            if (tlbPhys[half][index] >= 0x20000000L) continue

            var at = start
            while (at < end) {
                val page = ((at ushr 12) and 0xFFFFF).toInt()
                val physical = (tlbPhys[half][index] + (at - start)).toInt()
                tlbLutR[page] = physical or 0x40000000
                if (tlbDirty[half][index] != 0) tlbLutW[page] = physical or 0x40000000
                at += 0x1000
            }
        }
    }

    fun debugTlbDump(): List<String> {
        val lines = ArrayList<String>()
        for (i in 0 until 32) {
            for (half in 0 until 2) {
                if (tlbValid[half][i] == 0) continue
                lines.add(
                    "tlb[$i.$half] virt=0x${tlbStart[half][i].toString(16)}-0x${tlbEnd[half][i].toString(16)}" +
                        " phys=0x${tlbPhys[half][i].toString(16)} dirty=${tlbDirty[half][i]} global=${tlbGlobal[i]} asid=${tlbAsid[i]}",
                )
            }
        }
        return lines
    }

    private fun tlbProbe() {
        cop0[COP0_INDEX] = 0x80000000L
        val target = (cop0[COP0_ENTRYHI] ushr 13) and 0x7FFFFFF
        val region = ((cop0[COP0_ENTRYHI] ushr 62) and 3).toInt()
        val asid = (cop0[COP0_ENTRYHI] and 0xFF).toInt()

        for (i in 0 until 32) {
            val mask = tlbMask[i]
            if ((tlbVpn2[i] and mask.inv()) != (target and mask.inv())) continue
            if (tlbRegion[i] != region) continue
            if (tlbGlobal[i] == 0 && tlbAsid[i] != asid) continue
            cop0[COP0_INDEX] = i.toLong()
            break
        }
    }

    private fun checkCop1(): Boolean {
        if (cop0[COP0_STATUS] and STATUS_CU1 == 0L) {
            coprocessorException(1)
            return false
        }
        return true
    }

    private fun cop2Op(op: Int, rs: Int, rt: Int) {
        if (cop0[COP0_STATUS] and STATUS_CU2 == 0L) {
            coprocessorException(2)
            return
        }

        when (rs) {
            0, 2 -> gpr[rt] = cop2Latch.toInt().toLong()
            1 -> gpr[rt] = cop2Latch
            4, 5, 6 -> cop2Latch = gpr[rt]
            else -> {
                cop0[COP0_CAUSE] = EXC_RI or (2L shl 28)
                generalException(0x180)
            }
        }
    }

    private fun singleFrom(reg: Int): Float {
        val bits = if (fr || (reg and 1) == 0) fgr[reg].toInt() else (fgr[reg and 1.inv()] ushr 32).toInt()
        return Float.fromBits(bits)
    }

    private fun singleTo(reg: Int, value: Float) = resultTo(reg, value.toRawBits())

    private fun convertTo(reg: Int, bits: Long) {
        fpuExceptions(0)
        doubleTo(reg, bits)
    }

    private fun convertWordTo(reg: Int, value: Int) {
        fpuExceptions(0)
        resultTo(reg, value)
    }

    private fun resultTo(reg: Int, bits: Int) {
        val target = if (fr) reg else reg and 1.inv()
        fgr[target] = bits.toLong() and 0xFFFFFFFFL
    }

    private fun wordFrom(reg: Int): Int =
        if (fr || (reg and 1) == 0) fgr[reg].toInt() else (fgr[reg and 1.inv()] ushr 32).toInt()

    private fun wordTo(reg: Int, value: Int) {
        if (fr || (reg and 1) == 0) {
            fgr[reg] = (fgr[reg] and -0x100000000L) or (value.toLong() and 0xFFFFFFFFL)
        } else {
            val pair = reg and 1.inv()
            fgr[pair] = (fgr[pair] and 0xFFFFFFFFL) or (value.toLong() shl 32)
        }
    }

    private fun doubleBits(reg: Int): Long = if (fr) fgr[reg] else fgr[reg and 1.inv()]

    private fun doubleTo(reg: Int, bits: Long) {
        if (fr) fgr[reg] = bits else fgr[reg and 1.inv()] = bits
    }

    private fun cop1Op(op: Int, rs: Int, rt: Int, rd: Int, sa: Int, imm: Int) {
        if (!checkCop1()) return

        when (rs) {
            0 -> gpr[rt] = wordFrom(rd).toLong()
            1 -> gpr[rt] = doubleBits(rd)
            2 -> gpr[rt] = readFcr(rd).toLong()
            4 -> wordTo(rd, gpr[rt].toInt())
            5 -> doubleTo(rd, gpr[rt])
            6 -> writeFcr(rd, gpr[rt].toInt())

            8 -> {
                val condition = fcr31 and (1 shl 23) != 0
                when (rt and 3) {
                    0 -> branch(!condition, imm)
                    1 -> branch(condition, imm)
                    2 -> branchLikely(!condition, imm)
                    3 -> branchLikely(condition, imm)
                }
            }

            16 -> singleOp(op and 0x3F, rd, rt, sa)
            17 -> doubleOp(op and 0x3F, rd, rt, sa)
            20 -> wordOp(op and 0x3F, rd, sa)
            21 -> longOp(op and 0x3F, rd, sa)

            else -> reservedException()
        }
    }

    private fun readFcr(reg: Int): Int = when (reg) {
        0 -> 0x00000A00
        31 -> fcr31
        else -> 0
    }

    private fun writeFcr(reg: Int, value: Int) {
        if (reg == 31) {
            fcr31 = value and 0x0183FFFF
            val cause = (fcr31 ushr 12) and 0x1F
            val enable = (fcr31 ushr 7) and 0x1F
            if (cause and enable != 0) {
                floatingPointException()
            }
        }
    }

    private fun setCondition(value: Boolean) {
        fcr31 = if (value) fcr31 or (1 shl 23) else fcr31 and (1 shl 23).inv()
    }

    private fun fpuExceptions(mask: Int): Boolean {
        fcr31 = fcr31 and (0x3F shl 12).inv()
        if (mask == 0) return false

        fcr31 = fcr31 or (mask shl 12)

        val enable = (fcr31 ushr 7) and 0x1F
        if (mask and enable != 0) {
            floatingPointException()
            return true
        }

        fcr31 = fcr31 or (mask shl 2)
        return false
    }

    private fun signalingSingle(value: Float): Boolean {
        val bits = value.toRawBits()
        return value.isNaN() && (bits and 0x00400000) == 0
    }

    private fun signalingDouble(value: Double): Boolean {
        val bits = value.toRawBits()
        return value.isNaN() && (bits and 0x0008000000000000L) == 0L
    }

    private fun arithmetic(a: Double, b: Double, exact: Double, result: Double, function: Int, wide: Boolean): Int {
        val binary = function <= 3
        var mask = 0

        if (result.isNaN() && !a.isNaN() && !(binary && b.isNaN())) mask = mask or FP_INVALID

        if (function == 3 && b == 0.0 && !a.isNaN() && a != 0.0 && !a.isInfinite()) mask = mask or FP_DIVZERO

        if (result.isInfinite() && !a.isInfinite() && !(binary && b.isInfinite())) {
            mask = mask or FP_OVERFLOW or FP_INEXACT
        }

        if (wide && mask == 0 && !result.isNaN() && !result.isInfinite()) {
            if (inexactSingle(a, b, result.toFloat(), function)) mask = mask or FP_INEXACT

            if (result != 0.0 && kotlin.math.abs(result) < SMALLEST_NORMAL_SINGLE) {
                mask = mask or FP_UNDERFLOW or FP_INEXACT
            }
        }

        return mask
    }

    private fun roundSingle(exact: Double): Float {
        val nearest = exact.toFloat()
        if (nearest.isNaN() || nearest.toDouble() == exact) return nearest

        return when (fcr31 and 3) {
            1 -> if (kotlin.math.abs(nearest.toDouble()) > kotlin.math.abs(exact)) toward(nearest, exact) else nearest
            2 -> if (nearest.toDouble() < exact) nextUp(nearest) else nearest
            3 -> if (nearest.toDouble() > exact) nextDown(nearest) else nearest
            else -> nearest
        }
    }

    private fun toward(value: Float, exact: Double): Float =
        if (exact > 0.0) nextDown(value) else nextUp(value)

    private fun nextUp(value: Float): Float {
        if (value.isInfinite()) return value
        val bits = value.toRawBits()
        return Float.fromBits(if (bits >= 0) bits + 1 else if (bits == Int.MIN_VALUE) 1 else bits - 1)
    }

    private fun nextDown(value: Float): Float {
        if (value.isInfinite()) return value
        val bits = value.toRawBits()
        return Float.fromBits(if (bits > 0) bits - 1 else if (bits == 0) Int.MIN_VALUE or 1 else bits + 1)
    }

    private fun inexactSingle(a: Double, b: Double, result: Float, function: Int): Boolean {
        val wide = result.toDouble()

        return when (function) {
            0, 1 -> {
                val addend = if (function == 0) b else -b
                val sum = a + addend
                val error = (a - (sum - (sum - a))) + (addend - (sum - a))
                error != 0.0 || sum != wide
            }

            2 -> a * b != wide
            3 -> wide * b != a
            else -> wide * wide != a
        }
    }

    private fun singleOp(function: Int, fs: Int, ft: Int, fd: Int) {
        val a = singleFrom(fs)
        val b = singleFrom(ft)

        when (function) {
            in 0..4 -> {
                if (signalingSingle(a) || signalingSingle(b)) {
                    if (!fpuExceptions(FP_INVALID)) singleTo(fd, Float.fromBits(QUIET_SINGLE))
                    return
                }

                val wa = a.toDouble()
                val wb = b.toDouble()
                val exact = when (function) {
                    0 -> wa + wb
                    1 -> wa - wb
                    2 -> wa * wb
                    3 -> wa / wb
                    else -> kotlin.math.sqrt(wa)
                }
                val result = roundSingle(exact)
                val mask = arithmetic(wa, wb, exact, result.toDouble(), function, true)

                if (!fpuExceptions(mask)) {
                    singleTo(fd, if (result.isNaN()) Float.fromBits(QUIET_SINGLE) else result)
                }
            }

            5 -> {
                fpuExceptions(0)
                singleTo(fd, kotlin.math.abs(a))
            }

            6 -> singleTo(fd, a)

            7 -> {
                fpuExceptions(0)
                singleTo(fd, -a)
            }

            8 -> convertTo(fd, toLong(roundEven(a.toDouble())))
            9 -> convertTo(fd, toLong(truncate(a.toDouble())))
            10 -> convertTo(fd, toLong(kotlin.math.ceil(a.toDouble())))
            11 -> convertTo(fd, toLong(kotlin.math.floor(a.toDouble())))
            12 -> convertWordTo(fd, toWord(roundEven(a.toDouble())))
            13 -> convertWordTo(fd, toWord(truncate(a.toDouble())))
            14 -> convertWordTo(fd, toWord(kotlin.math.ceil(a.toDouble())))
            15 -> convertWordTo(fd, toWord(kotlin.math.floor(a.toDouble())))
            33 -> convertTo(fd, a.toDouble().toRawBits())
            36 -> convertWordTo(fd, convertWord(a.toDouble()))
            37 -> convertTo(fd, convertLong(a.toDouble()))
            in 48..63 -> compare(a.toDouble(), b.toDouble(), function and 0xF, signalingSingle(a) || signalingSingle(b))
            else -> reservedException()
        }
    }

    private fun doubleOp(function: Int, fs: Int, ft: Int, fd: Int) {
        val a = Double.fromBits(doubleBits(fs))
        val b = Double.fromBits(doubleBits(ft))

        when (function) {
            in 0..4 -> {
                if (signalingDouble(a) || signalingDouble(b)) {
                    if (!fpuExceptions(FP_INVALID)) doubleTo(fd, QUIET_DOUBLE)
                    return
                }

                val result = when (function) {
                    0 -> a + b
                    1 -> a - b
                    2 -> a * b
                    3 -> a / b
                    else -> kotlin.math.sqrt(a)
                }
                val mask = arithmetic(a, b, result, result, function, false)

                if (!fpuExceptions(mask)) {
                    doubleTo(fd, if (result.isNaN()) QUIET_DOUBLE else result.toRawBits())
                }
            }

            5 -> {
                fpuExceptions(0)
                doubleTo(fd, kotlin.math.abs(a).toRawBits())
            }

            6 -> doubleTo(fd, a.toRawBits())

            7 -> {
                fpuExceptions(0)
                doubleTo(fd, (-a).toRawBits())
            }

            8 -> convertTo(fd, toLong(roundEven(a)))
            9 -> convertTo(fd, toLong(truncate(a)))
            10 -> convertTo(fd, toLong(kotlin.math.ceil(a)))
            11 -> convertTo(fd, toLong(kotlin.math.floor(a)))
            12 -> convertWordTo(fd, toWord(roundEven(a)))
            13 -> convertWordTo(fd, toWord(truncate(a)))
            14 -> convertWordTo(fd, toWord(kotlin.math.ceil(a)))
            15 -> convertWordTo(fd, toWord(kotlin.math.floor(a)))
            32 -> {
                fpuExceptions(0)
                singleTo(fd, a.toFloat())
            }
            36 -> convertWordTo(fd, convertWord(a))
            37 -> convertTo(fd, convertLong(a))
            in 48..63 -> compare(a, b, function and 0xF, signalingDouble(a) || signalingDouble(b))
            else -> reservedException()
        }
    }

    private fun wordOp(function: Int, fs: Int, fd: Int) {
        val value = wordFrom(fs)
        when (function) {
            32 -> singleTo(fd, value.toFloat())
            33 -> doubleTo(fd, value.toDouble().toRawBits())
            else -> reservedException()
        }
    }

    private fun longOp(function: Int, fs: Int, fd: Int) {
        val value = doubleBits(fs)
        when (function) {
            32 -> singleTo(fd, value.toFloat())
            33 -> doubleTo(fd, value.toDouble().toRawBits())
            else -> reservedException()
        }
    }

    private fun compare(a: Double, b: Double, condition: Int, signaling: Boolean) {
        val unordered = a.isNaN() || b.isNaN()

        if (unordered) {
            if (fpuExceptions(FP_INVALID)) return
        } else {
            fpuExceptions(0)
        }

        val less = !unordered && a < b
        val equal = !unordered && a == b

        var result = false
        if (condition and 1 != 0 && unordered) result = true
        if (condition and 2 != 0 && equal) result = true
        if (condition and 4 != 0 && less) result = true

        setCondition(result)
    }

    private fun toWord(value: Double): Int {
        if (value.isNaN() || value > 2147483647.0 || value < -2147483648.0) return 0x7FFFFFFF
        return value.toInt()
    }

    private fun toLong(value: Double): Long {
        if (value.isNaN() || value >= 9.223372036854776E18 || value < -9.223372036854776E18) {
            return 0x7FFFFFFFFFFFFFFFL
        }
        return value.toLong()
    }

    private fun convertWord(value: Double): Int {
        if (value.isNaN() || value > Int.MAX_VALUE.toDouble() || value < Int.MIN_VALUE.toDouble()) return 0x7FFFFFFF
        return when ((fcr31 and 3)) {
            0 -> roundEven(value).toInt()
            1 -> truncate(value).toInt()
            2 -> kotlin.math.ceil(value).toInt()
            else -> kotlin.math.floor(value).toInt()
        }
    }

    private fun convertLong(value: Double): Long {
        if (value.isNaN()) return 0x7FFFFFFFFFFFFFFFL
        return when ((fcr31 and 3)) {
            0 -> roundEven(value).toLong()
            1 -> truncate(value).toLong()
            2 -> kotlin.math.ceil(value).toLong()
            else -> kotlin.math.floor(value).toLong()
        }
    }

    private fun roundEven(value: Double): Double = kotlin.math.round(value).let {
        if (kotlin.math.abs(value - truncate(value)) == 0.5 && (it.toLong() % 2L) != 0L) it - kotlin.math.sign(value) else it
    }

    private fun truncate(value: Double): Double = if (value < 0) kotlin.math.ceil(value) else kotlin.math.floor(value)

    private fun lwc1(rs: Int, ft: Int, imm: Int) {
        if (!checkCop1()) return
        val physical = resolveAddress(gpr[rs] + imm.toLong(), 4, false)
        if (physical == BAD_ADDRESS) return
        wordTo(ft, readWord(physical))
    }

    private fun ldc1(rs: Int, ft: Int, imm: Int) {
        if (!checkCop1()) return
        val physical = resolveAddress(gpr[rs] + imm.toLong(), 8, false)
        if (physical == BAD_ADDRESS) return
        val value = (readWord(physical).toLong() shl 32) or (readWord(physical + 4).toLong() and 0xFFFFFFFFL)
        doubleTo(ft, value)
    }

    private fun swc1(rs: Int, ft: Int, imm: Int) {
        if (!checkCop1()) return
        val physical = resolveAddress(gpr[rs] + imm.toLong(), 4, true)
        if (physical == BAD_ADDRESS) return
        writeWord(physical, wordFrom(ft), -1)
    }

    private fun sdc1(rs: Int, ft: Int, imm: Int) {
        if (!checkCop1()) return
        val physical = resolveAddress(gpr[rs] + imm.toLong(), 8, true)
        if (physical == BAD_ADDRESS) return
        val value = doubleBits(ft)
        writeWord(physical, (value ushr 32).toInt(), -1)
        writeWord(physical + 4, value.toInt(), -1)
    }


    companion object {
        const val BAD_ADDRESS = -1
        const val RSP_SLICE = 64
    }
}
