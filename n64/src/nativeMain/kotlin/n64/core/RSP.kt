package n64.core

const val SP_MEM_ADDR = 0
const val SP_DRAM_ADDR = 1
const val SP_RD_LEN = 2
const val SP_WR_LEN = 3
const val SP_STATUS = 4
const val SP_DMA_FULL = 5
const val SP_DMA_BUSY = 6
const val SP_SEMAPHORE = 7

const val SP_STATUS_HALT = 1 shl 0
const val SP_STATUS_BROKE = 1 shl 1
const val SP_STATUS_DMA_BUSY_BIT = 1 shl 2
const val SP_STATUS_DMA_FULL_BIT = 1 shl 3
const val SP_STATUS_SSTEP = 1 shl 5
const val SP_STATUS_INTR_BREAK = 1 shl 6

class RSP(private val n64: N64) {
    val mem = ByteArray(0x2000)
    val regs = IntArray(8)

    var pc = 0
    private var nextPc = 0

    val gpr = IntArray(32)
    val vpr = IntArray(32 * 8)

    private val accl = IntArray(8)
    private val accm = IntArray(8)
    private val acch = IntArray(8)

    private val sourceLane = IntArray(8)
    private val targetLane = IntArray(8)

    private val vcol = IntArray(8)
    private val vcoh = IntArray(8)
    private val vccl = IntArray(8)
    private val vcch = IntArray(8)
    private val vce = IntArray(8)

    private var divdp = false
    private var divin = 0
    private var divout = 0

    private val reciprocals = IntArray(512)
    private val inverseSquareRoots = IntArray(512)

    private var branchState = STATE_STEP
    private var branchTarget = 0
    private var broke = false
    private var halted = false
    private var cycles = 0L

    var tasks = 0
        private set
    var gfxTasks = 0
        private set
    var audioTasks = 0
        private set
    var lastTaskCycles = 0L
        private set
    var gfxCycles = 0L
        private set
    var overruns = 0
        private set
    val debugVector = IntArray(64)
    val debugUnknown = HashMap<Int, Int>()

    val dmem: DmemView = DmemView(this)

    class DmemView(private val rsp: RSP) {
        operator fun set(index: Int, value: Int) {
            val at = index * 4
            rsp.mem[at] = (value ushr 24).toByte()
            rsp.mem[at + 1] = (value ushr 16).toByte()
            rsp.mem[at + 2] = (value ushr 8).toByte()
            rsp.mem[at + 3] = value.toByte()
        }

        operator fun get(index: Int): Int = rsp.readMem(index * 4)
    }

    init {
        buildTables()
    }

    private fun buildTables() {
        reciprocals[0] = 0xFFFF
        for (index in 1 until 512) {
            val a = (index + 512).toLong()
            val b = (1L shl 34) / a
            reciprocals[index] = (((b + 1) shr 8) and 0xFFFF).toInt()
        }

        for (index in 0 until 512) {
            val shift = if (index % 2 == 1) 1 else 0
            val a = ((index + 512) shr shift).toLong()
            var b = 1L shl 17
            while (a * (b + 1) * (b + 1) < (1L shl 44)) b++
            inverseSquareRoots[index] = ((b shr 1) and 0xFFFF).toInt()
        }
    }

    fun reset() {
        mem.fill(0)
        imemWords.fill(0)
        regs.fill(0)
        gpr.fill(0)
        vpr.fill(0)
        accl.fill(0)
        accm.fill(0)
        acch.fill(0)
        vcol.fill(0)
        vcoh.fill(0)
        vccl.fill(0)
        vcch.fill(0)
        vce.fill(0)

        regs[SP_STATUS] = SP_STATUS_HALT
        pc = 0
        nextPc = 0
        branchState = STATE_STEP
        broke = false
        halted = false
        divdp = false
        divin = 0
        divout = 0
        cycles = 0
        tasks = 0
    }

    fun readMem(addr: Int): Int {
        val at = addr and 0x1FFF
        return ((mem[at].toInt() and 0xFF) shl 24) or
            ((mem[(at + 1) and 0x1FFF].toInt() and 0xFF) shl 16) or
            ((mem[(at + 2) and 0x1FFF].toInt() and 0xFF) shl 8) or
            (mem[(at + 3) and 0x1FFF].toInt() and 0xFF)
    }

    private val imemWords = IntArray(1024)

    fun writeMem(addr: Int, value: Int, mask: Int) {
        val at = addr and 0x1FFF
        val current = readMem(at)
        val updated = (current and mask.inv()) or (value and mask)
        mem[at] = (updated ushr 24).toByte()
        mem[(at + 1) and 0x1FFF] = (updated ushr 16).toByte()
        mem[(at + 2) and 0x1FFF] = (updated ushr 8).toByte()
        mem[(at + 3) and 0x1FFF] = updated.toByte()
        if (at + 3 >= 0x1000) {
            syncImemWord(at)
            syncImemWord(at + 3)
        }
    }

    private fun syncImemWord(addr: Int) {
        val base = addr and 0x1FFC
        if (base < 0x1000) return
        imemWords[(base and 0xFFF) ushr 2] =
            ((mem[base].toInt() and 0xFF) shl 24) or
                ((mem[base + 1].toInt() and 0xFF) shl 16) or
                ((mem[base + 2].toInt() and 0xFF) shl 8) or
                (mem[base + 3].toInt() and 0xFF)
    }

    fun syncImem() {
        for (i in 0 until 1024) syncImemWord(0x1000 + i * 4)
    }

    fun readReg(reg: Int): Int {
        n64.cpu.addCycles(20)
        return when (reg) {
            SP_SEMAPHORE -> {
                val value = regs[SP_SEMAPHORE]
                regs[SP_SEMAPHORE] = 1
                value
            }

            else -> if (reg < 8) regs[reg] else 0
        }
    }

    fun writeReg(reg: Int, value: Int, mask: Int) {
        when (reg) {
            SP_STATUS -> updateStatus(value)

            SP_RD_LEN -> {
                regs[reg] = (regs[reg] and mask.inv()) or (value and mask)
                dma(false)
            }

            SP_WR_LEN -> {
                regs[reg] = (regs[reg] and mask.inv()) or (value and mask)
                dma(true)
            }

            SP_SEMAPHORE -> regs[SP_SEMAPHORE] = 0

            else -> if (reg < 8) regs[reg] = (regs[reg] and mask.inv()) or (value and mask)
        }
    }

    fun readPcReg(reg: Int): Int {
        n64.cpu.addCycles(20)
        return if (reg == 0) pc else 0
    }

    fun writePcReg(reg: Int, value: Int, mask: Int) {
        if (reg == 0) pc = (value and mask) and 0xFFC
    }

    private fun updateStatus(w: Int) {
        val wasHalted = regs[SP_STATUS] and SP_STATUS_HALT != 0

        if (w and (1 shl 0) != 0 && w and (1 shl 1) == 0) regs[SP_STATUS] = regs[SP_STATUS] and SP_STATUS_HALT.inv()
        if (w and (1 shl 1) != 0 && w and (1 shl 0) == 0) {
            n64.removeEvent(EVENT_SP)
            regs[SP_STATUS] = regs[SP_STATUS] or SP_STATUS_HALT
        }
        if (w and (1 shl 2) != 0) regs[SP_STATUS] = regs[SP_STATUS] and SP_STATUS_BROKE.inv()
        if (w and (1 shl 3) != 0 && w and (1 shl 4) == 0) {
            n64.mi.clearInterrupt(MI_INTR_SP)
            n64.cpu.checkPendingInterrupts()
        }
        if (w and (1 shl 4) != 0 && w and (1 shl 3) == 0) n64.mi.setInterrupt(MI_INTR_SP)
        if (w and (1 shl 5) != 0 && w and (1 shl 6) == 0) regs[SP_STATUS] = regs[SP_STATUS] and SP_STATUS_SSTEP.inv()
        if (w and (1 shl 6) != 0 && w and (1 shl 5) == 0) regs[SP_STATUS] = regs[SP_STATUS] or SP_STATUS_SSTEP
        if (w and (1 shl 7) != 0 && w and (1 shl 8) == 0) {
            regs[SP_STATUS] = regs[SP_STATUS] and SP_STATUS_INTR_BREAK.inv()
        }
        if (w and (1 shl 8) != 0 && w and (1 shl 7) == 0) regs[SP_STATUS] = regs[SP_STATUS] or SP_STATUS_INTR_BREAK

        for (signal in 0 until 8) {
            val clear = 1 shl (9 + signal * 2)
            val set = 1 shl (10 + signal * 2)
            val bit = 1 shl (7 + signal)
            if (w and clear != 0 && w and set == 0) regs[SP_STATUS] = regs[SP_STATUS] and bit.inv()
            if (w and set != 0 && w and clear == 0) regs[SP_STATUS] = regs[SP_STATUS] or bit
        }

        if (regs[SP_STATUS] and SP_STATUS_HALT == 0 && wasHalted) {
            broke = false
            halted = false
            startTask()
            runToCompletion()
        }
    }

    private var taskInFlight = false

    var debugOverlap = 0
        private set
    var debugYieldStruct = 0
        private set
    var debugYieldFlags = 0
        private set
    var debugYieldStatus = 0
        private set

    private fun startTask() {
        if (taskInFlight) debugOverlap++
        tasks++
        taskInFlight = true
        cycles = 0

        when (readMem(0xFC0)) {
            1 -> {
                gfxTasks++
                debugGfxStruct = debugTaskStruct
            }

            2 -> audioTasks++
        }

        if (n64.debugTrace) {
            n64.debugLog.add(
                "SP start type=${readMem(0xFC0)} struct=0x${debugTaskStruct.toUInt().toString(16)}" +
                    " flags=0x${readMem(0xFC4).toUInt().toString(16)}" +
                    " data=0x${readMem(0xFF0).toUInt().toString(16)}/${readMem(0xFF4)}" +
                    " yield=0x${readMem(0xFF8).toUInt().toString(16)}/${readMem(0xFFC)}" +
                    " status=0x${regs[SP_STATUS].toUInt().toString(16)} pc=0x${pc.toUInt().toString(16)} f=${n64.frameCount}",
            )
        }
    }

    fun advance(budget: Int) {
        if (!taskInFlight) return

        var remaining = budget
        while (remaining > 0) {
            gpr[0] = 0

            val opcode = fetch(pc)
            execute(opcode)
            step()

            cycles++
            remaining--

            if (broke || halted) {
                finish()
                return
            }

            if (cycles > 20_000_000L) {
                overruns++
                halted = true
                finish()
                return
            }
        }
    }

    fun runToCompletion() {
        while (taskInFlight) advance(1 shl 20)
    }

    var yields = 0
        private set
    var debugBroke = 0
        private set
    var debugHalted = 0
        private set
    var debugEndStatus = 0
        private set

    private fun finish() {
        taskInFlight = false
        lastTaskCycles = cycles
        gfxCycles += cycles

        debugEndStatus = regs[SP_STATUS]
        if (regs[SP_STATUS] and 0x100 != 0) {
            yields++
            debugYieldStruct = debugTaskStruct
            debugYieldFlags = n64.ramRead32(debugTaskStruct + 4)
            debugYieldStatus = regs[SP_STATUS] or SP_STATUS_HALT or SP_STATUS_BROKE
        }
        if (broke) debugBroke++ else debugHalted++

        if (n64.debugTrace) {
            n64.debugLog.add(
                "SP finish status=0x${regs[SP_STATUS].toUInt().toString(16)}" +
                    " pc=0x${pc.toUInt().toString(16)} cycles=$cycles broke=$broke halted=$halted" +
                    " struct=0x${debugTaskStruct.toUInt().toString(16)}" +
                    " flags=0x${n64.ramRead32(debugTaskStruct + 4).toUInt().toString(16)} f=${n64.frameCount}",
            )
        }

        if (broke) {
            regs[SP_STATUS] = regs[SP_STATUS] or SP_STATUS_HALT or SP_STATUS_BROKE
            if (regs[SP_STATUS] and SP_STATUS_INTR_BREAK != 0) n64.mi.setInterrupt(MI_INTR_SP)
        } else {
            regs[SP_STATUS] = regs[SP_STATUS] or SP_STATUS_HALT
        }

        n64.rdp.taskFinished(0)
    }

    private fun step() {
        when (branchState) {
            STATE_STEP -> pc = (pc + 4) and 0xFFC

            STATE_TAKE -> {
                pc = (pc + 4) and 0xFFC
                branchState = STATE_DELAY_TAKEN
            }

            STATE_NOT_TAKEN -> {
                pc = (pc + 4) and 0xFFC
                branchState = STATE_DELAY_NOT_TAKEN
            }

            STATE_DELAY_TAKEN -> {
                pc = branchTarget and 0xFFC
                branchState = STATE_STEP
            }

            STATE_DELAY_NOT_TAKEN -> {
                pc = (pc + 4) and 0xFFC
                branchState = STATE_STEP
            }
        }
    }

    fun event() {}

    val running: Boolean get() = taskInFlight

    fun dmaPop() {}

    var debugTaskStruct = 0
        private set
    var debugGfxStruct = 0
        private set

    private fun dma(toRdram: Boolean) {
        val length = if (toRdram) regs[SP_WR_LEN] else regs[SP_RD_LEN]

        if (!toRdram && (regs[SP_MEM_ADDR] and 0xFFF) == 0xFC0) {
            debugTaskStruct = regs[SP_DRAM_ADDR] and 0xFFFFF8
        }

        val rowLength = ((length and 0xFFF) or 7) + 1
        val count = ((length ushr 12) and 0xFF) + 1
        val skip = (length ushr 20) and 0xFF8

        var memAddr = regs[SP_MEM_ADDR] and 0xFF8
        var dramAddr = regs[SP_DRAM_ADDR] and 0xFFFFF8
        val bank = regs[SP_MEM_ADDR] and 0x1000

        for (row in 0 until count) {
            var i = 0
            while (i < rowLength) {
                val at = bank or (memAddr and 0xFFF)
                if (toRdram) {
                    n64.ramWrite32(dramAddr, readMem(at))
                } else {
                    writeMem(at, n64.ramRead32(dramAddr), -1)
                }
                memAddr += 4
                dramAddr += 4
                i += 4
            }
            dramAddr += skip
        }

        regs[SP_MEM_ADDR] = (memAddr and 0xFFF) or bank
        regs[SP_DRAM_ADDR] = dramAddr
        regs[SP_RD_LEN] = 0xFF8
        regs[SP_WR_LEN] = 0xFF8
    }

    private fun fetch(at: Int): Int = imemWords[(at and 0xFFC) ushr 2]

    private fun byteAt(addr: Int): Int = mem[addr and 0xFFF].toInt() and 0xFF

    private fun writeByte(addr: Int, value: Int) {
        mem[addr and 0xFFF] = value.toByte()
    }

    private fun execute(op: Int) {
        val rs = (op ushr 21) and 0x1F
        val rt = (op ushr 16) and 0x1F
        val rd = (op ushr 11) and 0x1F
        val sa = (op ushr 6) and 0x1F
        val imm = op.toShort().toInt()

        when (op ushr 26) {
            0 -> special(op, rs, rt, rd, sa)
            1 -> when (rt) {
                0 -> branch(gpr[rs] < 0, imm)
                1 -> branch(gpr[rs] >= 0, imm)
                16 -> {
                    val condition = gpr[rs] < 0
                    gpr[31] = (pc + 8) and 0xFFF
                    branch(condition, imm)
                }

                17 -> {
                    val condition = gpr[rs] >= 0
                    gpr[31] = (pc + 8) and 0xFFF
                    branch(condition, imm)
                }
            }

            2 -> if (!inDelaySlotTaken()) {
                branchTarget = (op and 0x3FFFFFF) shl 2
                branchState = STATE_TAKE
            }

            3 -> {
                gpr[31] = if (inDelaySlotTaken()) (branchTarget + 4) and 0xFFF else (pc + 8) and 0xFFF
                if (!inDelaySlotTaken()) {
                    branchTarget = (op and 0x3FFFFFF) shl 2
                    branchState = STATE_TAKE
                }
            }

            4 -> branch(gpr[rs] == gpr[rt], imm)
            5 -> branch(gpr[rs] != gpr[rt], imm)
            6 -> branch(gpr[rs] <= 0, imm)
            7 -> branch(gpr[rs] > 0, imm)
            8, 9 -> gpr[rt] = gpr[rs] + imm
            10 -> gpr[rt] = if (gpr[rs] < imm) 1 else 0
            11 -> gpr[rt] = if (gpr[rs].toUInt() < imm.toUInt()) 1 else 0
            12 -> gpr[rt] = gpr[rs] and (op and 0xFFFF)
            13 -> gpr[rt] = gpr[rs] or (op and 0xFFFF)
            14 -> gpr[rt] = gpr[rs] xor (op and 0xFFFF)
            15 -> gpr[rt] = (op and 0xFFFF) shl 16

            16 -> when (rs) {
                0 -> gpr[rt] = readCop0(rd)
                4 -> writeCop0(rd, gpr[rt])
            }

            18 -> when (rs) {
                0 -> mfc2(op, rt, rd)
                2 -> cfc2(rt, rd)
                4 -> mtc2(op, rt, rd)
                6 -> ctc2(rt, rd)
                else -> vector(op)
            }

            32 -> gpr[rt] = byteAt(gpr[rs] + imm).toByte().toInt()
            33 -> gpr[rt] = loadHalf(gpr[rs] + imm).toShort().toInt()
            35, 39 -> gpr[rt] = loadWord(gpr[rs] + imm)
            36 -> gpr[rt] = byteAt(gpr[rs] + imm)
            37 -> gpr[rt] = loadHalf(gpr[rs] + imm)

            40 -> writeByte(gpr[rs] + imm, gpr[rt])
            41 -> storeHalf(gpr[rs] + imm, gpr[rt])
            43 -> storeWord(gpr[rs] + imm, gpr[rt])

            50 -> vectorLoad(op, rs, rt)
            58 -> vectorStore(op, rs, rt)
        }
    }

    private fun special(op: Int, rs: Int, rt: Int, rd: Int, sa: Int) {
        when (op and 0x3F) {
            0 -> gpr[rd] = gpr[rt] shl sa
            2 -> gpr[rd] = gpr[rt] ushr sa
            3 -> gpr[rd] = gpr[rt] shr sa
            4 -> gpr[rd] = gpr[rt] shl (gpr[rs] and 31)
            6 -> gpr[rd] = gpr[rt] ushr (gpr[rs] and 31)
            7 -> gpr[rd] = gpr[rt] shr (gpr[rs] and 31)

            8 -> if (!inDelaySlotTaken()) {
                branchTarget = gpr[rs] and 0xFFC
                branchState = STATE_TAKE
            }

            9 -> {
                val taken = inDelaySlotTaken()
                if (!taken) {
                    branchTarget = gpr[rs] and 0xFFC
                    branchState = STATE_TAKE
                }
                gpr[rd] = if (taken) (branchTarget + 4) and 0xFFF else (pc + 8) and 0xFFF
            }

            13 -> broke = true

            32, 33 -> gpr[rd] = gpr[rs] + gpr[rt]
            34, 35 -> gpr[rd] = gpr[rs] - gpr[rt]
            36 -> gpr[rd] = gpr[rs] and gpr[rt]
            37 -> gpr[rd] = gpr[rs] or gpr[rt]
            38 -> gpr[rd] = gpr[rs] xor gpr[rt]
            39 -> gpr[rd] = (gpr[rs] or gpr[rt]).inv()
            42 -> gpr[rd] = if (gpr[rs] < gpr[rt]) 1 else 0
            43 -> gpr[rd] = if (gpr[rs].toUInt() < gpr[rt].toUInt()) 1 else 0
        }
    }

    private fun inDelaySlotTaken(): Boolean = branchState == STATE_DELAY_TAKEN

    private fun branch(condition: Boolean, offset: Int) {
        if (condition) {
            branchTarget = (pc + 4 + (offset shl 2)) and 0xFFC
            branchState = STATE_TAKE
        } else {
            branchState = STATE_NOT_TAKEN
        }
    }

    private fun loadHalf(address: Int): Int =
        (byteAt(address) shl 8) or byteAt(address + 1)

    private fun loadWord(address: Int): Int =
        (byteAt(address) shl 24) or (byteAt(address + 1) shl 16) or
            (byteAt(address + 2) shl 8) or byteAt(address + 3)

    private fun storeHalf(address: Int, value: Int) {
        writeByte(address, value ushr 8)
        writeByte(address + 1, value)
    }

    private fun storeWord(address: Int, value: Int) {
        writeByte(address, value ushr 24)
        writeByte(address + 1, value ushr 16)
        writeByte(address + 2, value ushr 8)
        writeByte(address + 3, value)
    }

    private fun readCop0(reg: Int): Int {
        cycles += 2
        return if (reg < 8) readReg(reg) else n64.rdp.readDpc(reg - 8)
    }

    private fun writeCop0(reg: Int, value: Int) {
        if (n64.debugTrace && reg == SP_STATUS) {
            n64.debugLog.add(
                "SP mtc0 status w=0x${value.toUInt().toString(16)} pc=0x${pc.toUInt().toString(16)} f=${n64.frameCount}",
            )
        }
        if (reg < 8) {
            writeReg(reg, value, -1)
            if (reg == SP_STATUS && value and (1 shl 1) != 0) {
                regs[SP_STATUS] = regs[SP_STATUS] and SP_STATUS_HALT.inv()
                halted = true
            }
        } else {
            n64.rdp.writeDpc(reg - 8, value, -1)
        }
    }

    private fun vpr8(reg: Int, element: Int): Int {
        val e = element and 15
        val value = vpr[reg * 8 + (e shr 1)]
        return if (e and 1 == 0) (value ushr 8) and 0xFF else value and 0xFF
    }

    private fun setVpr8(reg: Int, element: Int, value: Int) {
        val e = element and 15
        val index = reg * 8 + (e shr 1)
        val current = vpr[index]
        vpr[index] = if (e and 1 == 0) {
            (current and 0x00FF) or ((value and 0xFF) shl 8)
        } else {
            (current and 0xFF00) or (value and 0xFF)
        }
    }

    private fun mfc2(op: Int, rt: Int, rd: Int) {
        val element = (op ushr 7) and 0xF
        val value = (vpr8(rd, element) shl 8) or vpr8(rd, element + 1)
        gpr[rt] = value.toShort().toInt()
    }

    private fun mtc2(op: Int, rt: Int, rd: Int) {
        val element = (op ushr 7) and 0xF
        setVpr8(rd, element, gpr[rt] ushr 8)
        if (element != 15) setVpr8(rd, element + 1, gpr[rt])
    }

    private fun cfc2(rt: Int, rd: Int) {
        val high: IntArray
        val low: IntArray
        when (rd and 3) {
            0 -> {
                high = vcoh
                low = vcol
            }

            1 -> {
                high = vcch
                low = vccl
            }

            else -> {
                high = ZERO
                low = vce
            }
        }

        var value = 0
        for (e in 0 until 8) {
            if (low[e] != 0) value = value or (1 shl e)
            if (high[e] != 0) value = value or (1 shl (e + 8))
        }
        gpr[rt] = value.toShort().toInt()
    }

    private fun ctc2(rt: Int, rd: Int) {
        val high: IntArray
        val low: IntArray
        when (rd and 3) {
            0 -> {
                high = vcoh
                low = vcol
            }

            1 -> {
                high = vcch
                low = vccl
            }

            else -> {
                high = SCRATCH
                low = vce
            }
        }

        val value = gpr[rt]
        for (e in 0 until 8) {
            low[e] = if (value and (1 shl e) != 0) 0xFFFF else 0
            high[e] = if (value and (1 shl (e + 8)) != 0) 0xFFFF else 0
        }
    }

    private fun signExtend7(offset: Int, shift: Int): Int {
        val value = (((offset shl 1) and 0x80) or offset).toByte().toInt()
        return value shl shift
    }

    private fun vectorLoad(op: Int, rs: Int, rt: Int) {
        val kind = (op ushr 11) and 0x1F
        val element = (op ushr 7) and 0xF
        val offset = op and 0x7F

        when (kind) {
            0 -> setVpr8(rt, element, byteAt(gpr[rs] + signExtend7(offset, 0)))

            1 -> loadBytes(rt, element, gpr[rs] + signExtend7(offset, 1), 2)
            2 -> loadBytes(rt, element, gpr[rs] + signExtend7(offset, 2), 4)
            3 -> loadBytes(rt, element, gpr[rs] + signExtend7(offset, 3), 8)

            4 -> {
                var address = gpr[rs] + signExtend7(offset, 4)
                var e = element
                val end = minOf(16 + element - (address and 15), 16)
                while (e < end) {
                    setVpr8(rt, e, byteAt(address))
                    address++
                    e++
                }
            }

            5 -> {
                var address = gpr[rs] + signExtend7(offset, 4)
                var e = (16 - ((address and 15) - element)) and 0xFF
                address = address and 15.inv()
                while (e < 16) {
                    setVpr8(rt, e, byteAt(address))
                    address++
                    e++
                }
            }

            6, 7 -> {
                var address = gpr[rs] + signExtend7(offset, 3)
                val index = ((address and 7) - element) and 0xFF
                address = address and 7.inv()
                val shift = if (kind == 6) 8 else 7
                for (i in 0 until 8) {
                    val at = address + ((index + i) and 15)
                    vpr[rt * 8 + i] = (byteAt(at) shl shift) and 0xFFFF
                }
            }

            8 -> {
                var address = gpr[rs] + signExtend7(offset, 4)
                val index = ((address and 7) - element) and 0xFF
                address = address and 7.inv()
                for (i in 0 until 8) {
                    val at = address + ((index + i * 2) and 15)
                    vpr[rt * 8 + i] = (byteAt(at) shl 7) and 0xFFFF
                }
            }

            9 -> {
                var address = gpr[rs] + signExtend7(offset, 4)
                val index = ((address and 7) - element) and 0xFF
                address = address and 7.inv()
                val temp = IntArray(8)
                for (slot in 0 until 4) {
                    temp[slot] = (byteAt(address + ((index + slot * 4) and 15)) shl 7) and 0xFFFF
                    temp[slot + 4] = (byteAt(address + ((index + slot * 4 + 8) and 15)) shl 7) and 0xFFFF
                }
                val end = minOf(element + 8, 16)
                var e = element
                while (e < end) {
                    val value = if (e and 1 == 0) (temp[e shr 1] ushr 8) and 0xFF else temp[e shr 1] and 0xFF
                    setVpr8(rt, e, value)
                    e++
                }
            }

            11 -> {
                var address = gpr[rs] + signExtend7(offset, 4)
                val begin = address and 7.inv()
                address = begin + ((element + (address and 8)) and 15)
                val base = rt and 7.inv()
                var slot = element shr 1
                for (i in 0 until 8) {
                    setVpr8(base + slot, i * 2, byteAt(address))
                    address++
                    if (address == begin + 16) address = begin
                    setVpr8(base + slot, i * 2 + 1, byteAt(address))
                    address++
                    if (address == begin + 16) address = begin
                    slot = (slot + 1) and 7
                }
            }
        }
    }

    private fun loadBytes(reg: Int, element: Int, start: Int, count: Int) {
        var address = start
        var e = element
        val end = minOf(element + count, 16)
        while (e < end) {
            setVpr8(reg, e, byteAt(address))
            address++
            e++
        }
    }

    private fun vectorStore(op: Int, rs: Int, rt: Int) {
        val kind = (op ushr 11) and 0x1F
        val element = (op ushr 7) and 0xF
        val offset = op and 0x7F

        when (kind) {
            0 -> writeByte(gpr[rs] + signExtend7(offset, 0), vpr8(rt, element))

            1 -> storeBytes(rt, element, gpr[rs] + signExtend7(offset, 1), 2)
            2 -> storeBytes(rt, element, gpr[rs] + signExtend7(offset, 2), 4)
            3 -> storeBytes(rt, element, gpr[rs] + signExtend7(offset, 3), 8)

            4 -> {
                var address = gpr[rs] + signExtend7(offset, 4)
                var e = element
                val end = element + (16 - (address and 15))
                while (e < end) {
                    writeByte(address, vpr8(rt, e))
                    address++
                    e++
                }
            }

            5 -> {
                var address = gpr[rs] + signExtend7(offset, 4)
                var e = element
                val end = element + (address and 15)
                val base = 16 - (address and 15)
                address = address and 15.inv()
                while (e < end) {
                    writeByte(address, vpr8(rt, e + base))
                    address++
                    e++
                }
            }

            6 -> {
                var address = gpr[rs] + signExtend7(offset, 3)
                var e = element
                val end = element + 8
                while (e < end) {
                    val value = if ((e and 15) < 8) {
                        vpr8(rt, (e and 7) shl 1)
                    } else {
                        (vpr[rt * 8 + (e and 7)] ushr 7) and 0xFF
                    }
                    writeByte(address, value)
                    address++
                    e++
                }
            }

            7 -> {
                var address = gpr[rs] + signExtend7(offset, 3)
                var e = element
                val end = element + 8
                while (e < end) {
                    val value = if ((e and 15) < 8) {
                        (vpr[rt * 8 + (e and 7)] ushr 7) and 0xFF
                    } else {
                        vpr8(rt, (e and 7) shl 1)
                    }
                    writeByte(address, value)
                    address++
                    e++
                }
            }

            8 -> {
                var address = gpr[rs] + signExtend7(offset, 4)
                val index = address and 7
                address = address and 7.inv()
                for (slot in 0 until 8) {
                    val byteIndex = element + slot * 2
                    val value = ((vpr8(rt, byteIndex) shl 1) or (vpr8(rt, byteIndex + 1) ushr 7)) and 0xFF
                    writeByte(address + ((index + slot * 2) and 15), value)
                }
            }

            9 -> {
                var address = gpr[rs] + signExtend7(offset, 4)
                val base = address and 7
                address = address and 7.inv()
                val elements = when (element) {
                    0, 15 -> intArrayOf(0, 1, 2, 3)
                    1 -> intArrayOf(6, 7, 4, 5)
                    4 -> intArrayOf(1, 2, 3, 0)
                    5 -> intArrayOf(7, 4, 5, 6)
                    8 -> intArrayOf(4, 5, 6, 7)
                    11 -> intArrayOf(3, 0, 1, 2)
                    12 -> intArrayOf(5, 6, 7, 4)
                    else -> {
                        for (slot in 0 until 4) writeByte(address + ((base + slot * 4) and 15), 0)
                        return
                    }
                }
                for (slot in 0 until 4) {
                    val value = (vpr[rt * 8 + elements[slot]] ushr 7) and 0xFF
                    writeByte(address + ((base + slot * 4) and 15), value)
                }
            }

            10 -> {
                var address = gpr[rs] + signExtend7(offset, 4)
                var base = address and 7
                address = address and 7.inv()
                var e = element
                val end = element + 16
                while (e < end) {
                    writeByte(address + (base and 15), vpr8(rt, e))
                    base++
                    e++
                }
            }

            11 -> {
                var address = gpr[rs] + signExtend7(offset, 4)
                val start = rt and 7.inv()
                var e = 16 - (element and 1.inv())
                var base = (address and 7) - (element and 1.inv())
                address = address and 7.inv()
                for (reg in start until start + 8) {
                    writeByte(address + (base and 15), vpr8(reg, e))
                    base++
                    e++
                    writeByte(address + (base and 15), vpr8(reg, e))
                    base++
                    e++
                }
            }
        }
    }

    private fun storeBytes(reg: Int, element: Int, start: Int, count: Int) {
        var address = start
        var e = element
        val end = element + count
        while (e < end) {
            writeByte(address, vpr8(reg, e and 15))
            address++
            e++
        }
    }

    private fun elementIndex(element: Int, lane: Int): Int = when {
        element < 2 -> lane
        element < 4 -> (lane and 6) or (element and 1)
        element < 8 -> (lane and 4) or (element and 3)
        else -> element - 8
    }

    private fun sx(value: Int): Int = value.toShort().toInt()

    private fun acc(lane: Int): Long {
        val raw = (acch[lane].toLong() shl 32) or (accm[lane].toLong() shl 16) or accl[lane].toLong()
        return (raw shl 16) shr 16
    }

    private fun setAcc(lane: Int, value: Long) {
        acch[lane] = ((value ushr 32) and 0xFFFF).toInt()
        accm[lane] = ((value ushr 16) and 0xFFFF).toInt()
        accl[lane] = (value and 0xFFFF).toInt()
    }

    private fun clampSigned(value: Long): Int {
        if (value > 32767) return 0x7FFF
        if (value < -32768) return 0x8000
        return value.toInt() and 0xFFFF
    }

    private fun clampUnsigned(value: Long): Int {
        if (value < 0) return 0
        if (value > 32767) return 0xFFFF
        return value.toInt() and 0xFFFF
    }

    private fun clampLow(lane: Int): Int {
        val high = if (acch[lane] and 0x8000 != 0) 0xFFFF else 0
        val mid = if (accm[lane] and 0x8000 != 0) 0xFFFF else 0
        if (acch[lane] == high && high == mid) return accl[lane]
        return if (high == 0) 0xFFFF else 0
    }

    private fun clampMid(lane: Int): Int = clampSigned(((acch[lane] shl 16) or accm[lane]).toLong())

    private fun clampMidUnsigned(lane: Int): Int =
        clampUnsigned(((acch[lane] shl 16) or accm[lane]).toLong())

    private fun sat16(value: Int): Int = when {
        value > 32767 -> 0x7FFF
        value < -32768 -> 0x8000
        else -> value and 0xFFFF
    }

    private fun sclip48(value: Long): Long = (value shl 16) shr 16

    private fun vector(op: Int) {
        val element = (op ushr 21) and 0xF
        val vt = (op ushr 16) and 0x1F
        val vs = (op ushr 11) and 0x1F
        val vd = (op ushr 6) and 0x1F
        val function = op and 0x3F

        if (n64.debugTrace) debugVector[function]++

        val vsBase = vs * 8
        val vtBase = vt * 8
        if (element == 0) {
            for (n in 0 until 8) {
                sourceLane[n] = vpr[vsBase + n]
                targetLane[n] = vpr[vtBase + n]
            }
        } else if (element >= 8) {
            val broadcast = vpr[vtBase + element - 8]
            for (n in 0 until 8) {
                sourceLane[n] = vpr[vsBase + n]
                targetLane[n] = broadcast
            }
        } else {
            for (n in 0 until 8) {
                sourceLane[n] = vpr[vsBase + n]
                targetLane[n] = vpr[vtBase + elementIndex(element, n)]
            }
        }

        when (function) {
            0 -> vmulf(vd, true)
            1 -> vmulf(vd, false)
            2 -> vrnd(vd, vs, true)
            3 -> vmulq(vd)
            4 -> vmudl(vd)
            5 -> vmudm(vd)
            6 -> vmudn(vd)
            7 -> vmudh(vd)
            8 -> vmacf(vd, true)
            9 -> vmacf(vd, false)
            10 -> vrnd(vd, vs, false)
            11 -> vmacq(vd)
            12 -> vmadl(vd)
            13 -> vmadm(vd)
            14 -> vmadn(vd)
            15 -> vmadh(vd)
            16 -> vadd(vd)
            17 -> vsub(vd)
            19 -> vabs(vd)
            20 -> vaddc(vd)
            21 -> vsubc(vd)
            29 -> vsar(vd, element)
            32 -> compareOp(vd, LT)
            33 -> compareOp(vd, EQ)
            34 -> compareOp(vd, NE)
            35 -> compareOp(vd, GE)
            36 -> vcl(vd)
            37 -> vch(vd)
            38 -> vcr(vd)
            39 -> vmrg(vd)
            40 -> logic(vd, 0)
            41 -> logic(vd, 1)
            42 -> logic(vd, 2)
            43 -> logic(vd, 3)
            44 -> logic(vd, 4)
            45 -> logic(vd, 5)
            48 -> reciprocal(vt, vs, vd, element, false, false)
            49 -> reciprocal(vt, vs, vd, element, true, false)
            50 -> reciprocalHigh(vt, vs, vd, element)
            51 -> vmov(vs, vd)
            52 -> reciprocal(vt, vs, vd, element, false, true)
            53 -> reciprocal(vt, vs, vd, element, true, true)
            54 -> reciprocalHigh(vt, vs, vd, element)
            55, 63 -> {}
            else -> vzero(vd)
        }
    }

    private fun vmulf(vd: Int, signedResult: Boolean) {
        for (n in 0 until 8) {
            val a = sourceLane[n]
            val b = targetLane[n]
            val product = sx(a) * sx(b)

            val low = product and 0xFFFF
            val high = (product shr 16) and 0xFFFF
            val sign1 = low ushr 15
            val doubled = (low + low) and 0xFFFF
            val sign2 = doubled ushr 15

            accl[n] = (0x8000 + doubled) and 0xFFFF
            accm[n] = ((high shl 1) + sign1 + sign2) and 0xFFFF

            val negative = if (accm[n] and 0x8000 != 0) 0xFFFF else 0
            val same = if (a == b) 0xFFFF else 0

            acch[n] = negative and same.inv() and 0xFFFF
            vpr[vd * 8 + n] = if (signedResult) {
                (accm[n] + (negative and same)) and 0xFFFF
            } else {
                (accm[n] or negative) and acch[n].inv() and 0xFFFF
            }
        }
    }

    private fun vrnd(vd: Int, vs: Int, positive: Boolean) {
        val shiftHigh = vs and 1 != 0
        for (n in 0 until 8) {
            var product = sx(targetLane[n])
            if (shiftHigh) product = product shl 16
            var accumulated = acc(n)
            if (if (positive) accumulated >= 0 else accumulated < 0) {
                accumulated = sclip48(accumulated + product.toLong())
            }
            setAcc(n, accumulated)
            vpr[vd * 8 + n] = clampSigned(accumulated shr 16)
        }
    }

    private fun vmulq(vd: Int) {
        for (n in 0 until 8) {
            var product = sx(sourceLane[n]) * sx(targetLane[n])
            if (product < 0) product += 31
            acch[n] = (product shr 16) and 0xFFFF
            accm[n] = product and 0xFFFF
            accl[n] = 0
            vpr[vd * 8 + n] = sat16(product shr 1) and 15.inv() and 0xFFFF
        }
    }

    private fun vmacq(vd: Int) {
        for (n in 0 until 8) {
            var product = (acch[n] shl 16) or accm[n]
            if (product < 0 && product and 32 == 0) {
                product += 32
            } else if (product >= 32 && product and 32 == 0) {
                product -= 32
            }
            acch[n] = (product shr 16) and 0xFFFF
            accm[n] = product and 0xFFFF
            vpr[vd * 8 + n] = sat16(product shr 1) and 15.inv() and 0xFFFF
        }
    }

    private fun vmudl(vd: Int) {
        for (n in 0 until 8) {
            accl[n] = (sourceLane[n] * targetLane[n]) ushr 16
            accm[n] = 0
            acch[n] = 0
            vpr[vd * 8 + n] = accl[n]
        }
    }

    private fun vmudm(vd: Int) {
        for (n in 0 until 8) {
            val product = sx(sourceLane[n]) * targetLane[n]
            accl[n] = product and 0xFFFF
            accm[n] = (product shr 16) and 0xFFFF
            acch[n] = if (accm[n] and 0x8000 != 0) 0xFFFF else 0
            vpr[vd * 8 + n] = accm[n]
        }
    }

    private fun vmudn(vd: Int) {
        for (n in 0 until 8) {
            val product = sourceLane[n] * sx(targetLane[n])
            accl[n] = product and 0xFFFF
            accm[n] = (product shr 16) and 0xFFFF
            acch[n] = if (accm[n] and 0x8000 != 0) 0xFFFF else 0
            vpr[vd * 8 + n] = accl[n]
        }
    }

    private fun vmudh(vd: Int) {
        for (n in 0 until 8) {
            val product = sx(sourceLane[n]) * sx(targetLane[n])
            accl[n] = 0
            accm[n] = product and 0xFFFF
            acch[n] = (product shr 16) and 0xFFFF
            vpr[vd * 8 + n] = clampMid(n)
        }
    }

    private fun vmacf(vd: Int, signedResult: Boolean) {
        for (n in 0 until 8) {
            val product = (sx(sourceLane[n]) * sx(targetLane[n])).toLong()
            setAcc(n, acc(n) + (product shl 1))
            vpr[vd * 8 + n] = if (signedResult) clampMid(n) else clampMidUnsigned(n)
        }
    }

    private fun vmadl(vd: Int) {
        for (n in 0 until 8) {
            val product = sourceLane[n] * targetLane[n]
            setAcc(n, acc(n) + (product ushr 16).toLong())
            vpr[vd * 8 + n] = clampLow(n)
        }
    }

    private fun vmadm(vd: Int) {
        for (n in 0 until 8) {
            val product = sx(sourceLane[n]).toLong() * targetLane[n].toLong()
            setAcc(n, acc(n) + product)
            vpr[vd * 8 + n] = clampMid(n)
        }
    }

    private fun vmadn(vd: Int) {
        for (n in 0 until 8) {
            val product = sourceLane[n].toLong() * sx(targetLane[n]).toLong()
            setAcc(n, acc(n) + product)
            vpr[vd * 8 + n] = clampLow(n)
        }
    }

    private fun vmadh(vd: Int) {
        for (n in 0 until 8) {
            val product = sx(sourceLane[n]).toLong() * sx(targetLane[n]).toLong()
            setAcc(n, acc(n) + (product shl 16))
            vpr[vd * 8 + n] = clampMid(n)
        }
    }

    private fun vadd(vd: Int) {
        for (n in 0 until 8) {
            val a = sx(sourceLane[n])
            val b = sx(targetLane[n])
            val carry = vcol[n] and 1
            accl[n] = (a + b + carry) and 0xFFFF
            val minAdjusted = sx(sat16(minOf(a, b) + carry))
            vpr[vd * 8 + n] = sat16(minAdjusted + maxOf(a, b))
        }
        vcol.fill(0)
        vcoh.fill(0)
    }

    private fun vsub(vd: Int) {
        for (n in 0 until 8) {
            val a = sourceLane[n]
            val b = targetLane[n]
            val carry = vcol[n] and 1
            val udiff = (b + carry) and 0xFFFF
            val sdiff = sat16(sx(b) + carry)
            accl[n] = (a - udiff) and 0xFFFF
            val overflowed = sx(sdiff) > sx(udiff)
            val result = sat16(sx(a) - sx(sdiff))
            vpr[vd * 8 + n] = if (overflowed) sat16(sx(result) - 1) else result
        }
        vcol.fill(0)
        vcoh.fill(0)
    }

    private fun vzero(vd: Int) {
        for (n in 0 until 8) {
            accl[n] = (sourceLane[n] + targetLane[n]) and 0xFFFF
            vpr[vd * 8 + n] = 0
        }
    }

    private fun vabs(vd: Int) {
        for (n in 0 until 8) {
            val a = sourceLane[n]
            val negative = sx(a) < 0
            val kept = if (a == 0) 0 else targetLane[n]
            val flipped = if (negative) kept xor 0xFFFF else kept
            accl[n] = if (negative) (flipped + 1) and 0xFFFF else flipped
            vpr[vd * 8 + n] = if (negative) sat16(sx(flipped) + 1) else flipped
        }
    }

    private fun vaddc(vd: Int) {
        for (n in 0 until 8) {
            val sum = sourceLane[n] + targetLane[n]
            accl[n] = sum and 0xFFFF
            vcol[n] = if (sum > 0xFFFF) 0xFFFF else 0
            vcoh[n] = 0
            vpr[vd * 8 + n] = accl[n]
        }
    }

    private fun vsubc(vd: Int) {
        for (n in 0 until 8) {
            val a = sourceLane[n]
            val b = targetLane[n]
            accl[n] = (a - b) and 0xFFFF
            vcoh[n] = if (a != b) 0xFFFF else 0
            vcol[n] = if (a < b) 0xFFFF else 0
            vpr[vd * 8 + n] = accl[n]
        }
    }

    private fun vsar(vd: Int, element: Int) {
        for (n in 0 until 8) {
            vpr[vd * 8 + n] = when (element) {
                8 -> acch[n]
                9 -> accm[n]
                10 -> accl[n]
                else -> 0
            }
        }
    }

    private fun compareOp(vd: Int, kind: Int) {
        for (n in 0 until 8) {
            val a = sourceLane[n]
            val b = targetLane[n]
            val equal = a == b
            val condition = when (kind) {
                LT -> sx(a) < sx(b) || (equal && vcoh[n] != 0 && vcol[n] != 0)
                EQ -> equal && vcoh[n] == 0
                NE -> !equal || vcoh[n] != 0
                else -> sx(a) > sx(b) || (equal && !(vcoh[n] != 0 && vcol[n] != 0))
            }
            vccl[n] = if (condition) 0xFFFF else 0
            accl[n] = if (condition) a else b
            vpr[vd * 8 + n] = accl[n]
            vcch[n] = 0
        }
        vcoh.fill(0)
        vcol.fill(0)
    }

    private fun vcl(vd: Int) {
        for (n in 0 until 8) {
            val a = sourceLane[n]
            val b = targetLane[n]
            val carry = vcol[n] != 0

            val negated = if (carry) (b.inv() + 1) and 0xFFFF else b
            val difference = (a - negated) and 0xFFFF
            val noCarry = difference == minOf(a + b, 0xFFFF)
            val zero = difference == 0

            val lessOrEqual = if (vce[n] != 0) (zero || noCarry) else (zero && noCarry)
            val greaterOrEqual = b <= a

            val low = if (carry && vcoh[n] == 0) lessOrEqual else vccl[n] != 0
            val high = if (carry || vcoh[n] != 0) vcch[n] != 0 else greaterOrEqual

            val select = if (carry) low else high
            accl[n] = if (select) negated else a
            vpr[vd * 8 + n] = accl[n]

            vcch[n] = if (high) 0xFFFF else 0
            vccl[n] = if (low) 0xFFFF else 0
        }
        vcoh.fill(0)
        vcol.fill(0)
        vce.fill(0)
    }

    private fun vch(vd: Int) {
        for (n in 0 until 8) {
            val a = sourceLane[n]
            val b = targetLane[n]

            val signsDiffer = (sx(a) xor sx(b)) < 0
            vcol[n] = if (signsDiffer) 0xFFFF else 0

            val negated = if (signsDiffer) (b.inv() + 1) and 0xFFFF else b
            val difference = (a - negated) and 0xFFFF
            val signedDifference = sx(difference)
            val zero = difference == 0
            val targetNegative = sx(b) < 0

            val high = if (signsDiffer) targetNegative else signedDifference >= 0
            val low = if (signsDiffer) signedDifference <= 0 else targetNegative
            vcch[n] = if (high) 0xFFFF else 0
            vccl[n] = if (low) 0xFFFF else 0

            val extension = signsDiffer && difference == 0xFFFF
            vce[n] = if (extension) 0xFFFF else 0
            vcoh[n] = if (zero || extension) 0 else 0xFFFF

            val select = if (signsDiffer) low else high
            accl[n] = if (select) negated else a
            vpr[vd * 8 + n] = accl[n]
        }
    }

    private fun vcr(vd: Int) {
        for (n in 0 until 8) {
            val a = sourceLane[n]
            val b = targetLane[n]

            val signsDiffer = (sx(a) xor sx(b)) < 0
            val sign = if (signsDiffer) 0xFFFF else 0

            val lowSum = ((a and sign) + b) and 0xFFFF
            val low = lowSum and 0x8000 != 0
            vccl[n] = if (low) 0xFFFF else 0

            val high = minOf(sx(a or sign), sx(b)) == sx(b)
            vcch[n] = if (high) 0xFFFF else 0

            val negated = (b xor sign) and 0xFFFF
            val select = if (signsDiffer) low else high
            accl[n] = if (select) negated else a
            vpr[vd * 8 + n] = accl[n]
        }
        vcol.fill(0)
        vcoh.fill(0)
        vce.fill(0)
    }

    private fun vmrg(vd: Int) {
        for (n in 0 until 8) {
            accl[n] = if (vccl[n] != 0) sourceLane[n] else targetLane[n]
            vpr[vd * 8 + n] = accl[n]
        }
        vcoh.fill(0)
        vcol.fill(0)
    }

    private fun vmov(vs: Int, vd: Int) {
        val destination = vs and 7
        val moved = targetLane[destination]
        for (n in 0 until 8) accl[n] = targetLane[n]
        vpr[vd * 8 + destination] = moved
    }

    private fun logic(vd: Int, kind: Int) {
        for (n in 0 until 8) {
            val a = sourceLane[n]
            val b = targetLane[n]
            val value = when (kind) {
                0 -> a and b
                1 -> (a and b).inv()
                2 -> a or b
                3 -> (a or b).inv()
                4 -> a xor b
                else -> (a xor b).inv()
            }
            accl[n] = value and 0xFFFF
            vpr[vd * 8 + n] = accl[n]
        }
    }

    private fun reciprocal(vt: Int, vs: Int, vd: Int, element: Int, long: Boolean, squareRoot: Boolean) {
        val destination = vs and 7
        val raw = vpr[vt * 8 + (element and 7)]

        val input = if (long && divdp) (divin shl 16) or raw else sx(raw)
        val result = if (squareRoot) computeInverseSqrt(input) else computeReciprocal(input)

        divdp = false
        divout = (result ushr 16) and 0xFFFF

        for (n in 0 until 8) accl[n] = targetLane[n]
        vpr[vd * 8 + destination] = result and 0xFFFF
    }

    private fun reciprocalHigh(vt: Int, vs: Int, vd: Int, element: Int) {
        val destination = vs and 7

        for (n in 0 until 8) accl[n] = targetLane[n]

        divdp = true
        divin = sx(vpr[vt * 8 + (element and 7)])
        vpr[vd * 8 + destination] = divout and 0xFFFF
    }

    private fun computeReciprocal(input: Int): Int {
        val mask = input shr 31
        var data = input xor mask
        if (input > -32768) data -= mask
        if (data == 0) return 0x7FFFFFFF
        if (input == -32768) return -0x10000

        val shift = countLeadingZeros(data)
        val index = (((data.toLong() shl shift) and 0x7FC00000L) ushr 22).toInt()
        var result = reciprocals[index and 511]
        result = (0x10000 or result) shl 14
        return (result ushr (31 - shift)) xor mask
    }

    private fun computeInverseSqrt(input: Int): Int {
        val mask = input shr 31
        var data = input xor mask
        if (input > -32768) data -= mask
        if (data == 0) return 0x7FFFFFFF
        if (input == -32768) return -0x10000

        val shift = countLeadingZeros(data)
        val index = (((data.toLong() shl shift) and 0x7FC00000L) ushr 22).toInt()
        var result = inverseSquareRoots[((index and 0x1FE) or (shift and 1)) and 511]
        result = (0x10000 or result) shl 14
        return (result ushr ((31 - shift) shr 1)) xor mask
    }

    private fun countLeadingZeros(value: Int): Int {
        if (value == 0) return 32
        var count = 0
        var v = value
        while (v and -0x80000000 == 0) {
            v = v shl 1
            count++
        }
        return count
    }

    companion object {
        private const val LT = 0
        private const val EQ = 1
        private const val NE = 2
        private const val GE = 3

        private val ZERO = IntArray(8)
        private val SCRATCH = IntArray(8)
    }
}
