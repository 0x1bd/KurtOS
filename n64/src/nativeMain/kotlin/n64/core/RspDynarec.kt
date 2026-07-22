@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package n64.core

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.invoke
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.pin
import kotlinx.cinterop.set
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.toLong

const val CHAIN_CHUNK = 65536

class RspBlock(val pc: Int, val length: Int, val entry: Long)

private class RspUniverse {
    val table = arrayOfNulls<RspBlock>(1024)
}

class RspDynarec(private val rsp: RSP, private val arena: CodeArena) {
    private val universes = HashMap<Long, RspUniverse>()
    private var current: RspUniverse? = null
    private var currentHash = 0L
    private var table = arrayOfNulls<RspBlock>(1024)
    private var imemDirty = true

    var universeCount = 0
        private set
    var universeSwitches = 0L
        private set

    private val gprPin = rsp.gpr.pin()
    private val memPin = rsp.mem.pin()
    private val vprPin = rsp.vpr.pin()
    private val acclPin = rsp.accl.pin()
    private val accmPin = rsp.accm.pin()
    private val acchPin = rsp.acch.pin()
    private val vcolPin = rsp.vcol.pin()
    private val vcohPin = rsp.vcoh.pin()
    private val vcclPin = rsp.vccl.pin()
    private val vcchPin = rsp.vcch.pin()
    private val vcePin = rsp.vce.pin()
    private val swizzle = buildSwizzle()
    private val swizzlePin = swizzle.pin()

    val ctx: CPointer<LongVar> = nativeHeap.allocArray(24)

    private val compiler = RspCompiler()
    private val ops = IntArray(RSP_MAX_BLOCK + 2)

    private var trampoline: CPointer<CFunction<(CPointer<LongVar>?) -> Int>>? = null

    private var generation = 0
    private val linkSlots = ArrayList<Long>()
    private val linkOrig = ArrayList<Int>()
    private val nochain = !N64Tuning.rspChaining

    init {
        ctx[RSP_GPR / 8] = gprPin.addressOf(0).toLong()
        ctx[RSP_DMEM / 8] = memPin.addressOf(0).toLong()
        ctx[RSP_EXEC / 8] = rspExecPtr()
        ctx[RSP_VPR / 8] = vprPin.addressOf(0).toLong()
        ctx[RSP_ACCL / 8] = acclPin.addressOf(0).toLong()
        ctx[RSP_ACCM / 8] = accmPin.addressOf(0).toLong()
        ctx[RSP_ACCH / 8] = acchPin.addressOf(0).toLong()
        ctx[RSP_VCOL / 8] = vcolPin.addressOf(0).toLong()
        ctx[RSP_VCOH / 8] = vcohPin.addressOf(0).toLong()
        ctx[RSP_VCCL / 8] = vcclPin.addressOf(0).toLong()
        ctx[RSP_VCCH / 8] = vcchPin.addressOf(0).toLong()
        ctx[RSP_VCE / 8] = vcePin.addressOf(0).toLong()
        ctx[RSP_SWIZZLE / 8] = swizzlePin.addressOf(0).toLong()
        installTrampoline()
    }

    private fun buildSwizzle(): ByteArray {
        val table = ByteArray(16 * 16)
        for (element in 0 until 16) {
            for (lane in 0 until 8) {
                val src = when {
                    element < 2 -> lane
                    element < 4 -> (lane and 6) or (element and 1)
                    element < 8 -> (lane and 4) or (element and 3)
                    else -> element - 8
                }
                table[element * 16 + lane * 2] = (src * 2).toByte()
                table[element * 16 + lane * 2 + 1] = (src * 2 + 1).toByte()
            }
        }
        return table
    }

    private fun installTrampoline() {
        val ret = compiler.buildTrampoline()
        val code = arena.add(compiler.asm.buf, compiler.asm.len)
        trampoline = code.toCPointer()
        ctx[RSP_RETURN / 8] = code + ret
    }

    fun flush() {
        universes.clear()
        universeCount = 0
        current = null
        imemDirty = true
        arena.reset()
        linkSlots.clear()
        linkOrig.clear()
        installTrampoline()
        generation++
    }

    fun imemWritten() {
        imemDirty = true
        ctx[RSP_LIMIT / 8] = 0
    }

    private fun selectUniverse(): Boolean {
        if (universes.size > 512) flush()
        imemDirty = false
        val hash = rsp.imemHash()
        if (current != null && hash == currentHash) return false
        val universe = universes.getOrPut(hash) {
            universeCount++
            RspUniverse()
        }
        currentHash = hash
        if (universe === current) return false
        current = universe
        table = universe.table
        universeSwitches++
        return true
    }

    private fun readRel(slot: Long): Int {
        val p = (slot + 1).toCPointer<ByteVar>()!!
        return (p[0].toInt() and 0xFF) or ((p[1].toInt() and 0xFF) shl 8) or
            ((p[2].toInt() and 0xFF) shl 16) or ((p[3].toInt() and 0xFF) shl 24)
    }

    private fun writeRel(slot: Long, rel: Int) {
        val p = (slot + 1).toCPointer<ByteVar>()!!
        p[0] = rel.toByte()
        p[1] = (rel ushr 8).toByte()
        p[2] = (rel ushr 16).toByte()
        p[3] = (rel ushr 24).toByte()
    }

    private fun patchLink(slot: Long, target: Long) {
        linkSlots.add(slot)
        linkOrig.add(readRel(slot))
        writeRel(slot, (target - (slot + 5)).toInt())
    }

    private fun classify(op: Int): Int = when (op ushr 26) {
        0 -> when (op and 0x3F) {
            8, 9 -> CLS_BRANCH
            13 -> CLS_END
            else -> CLS_OP
        }

        1 -> when ((op ushr 16) and 0x1F) {
            0, 1, 16, 17 -> CLS_BRANCH
            else -> CLS_OP
        }

        2, 3, 4, 5, 6, 7 -> CLS_BRANCH
        16 -> CLS_END
        else -> CLS_OP
    }

    private fun compile(pc: Int): RspBlock {
        var at = pc
        var n = 0
        var endsWithBranch = false
        while (at < 0x1000 && n < RSP_MAX_BLOCK - 1) {
            val op = rsp.imemAt(at)
            val cls = classify(op)
            if (cls == CLS_END) break
            if (cls == CLS_BRANCH) {
                val slotAt = at + 4
                if (slotAt < 0x1000 && classify(rsp.imemAt(slotAt)) == CLS_OP) {
                    ops[n] = op
                    ops[n + 1] = rsp.imemAt(slotAt)
                    n += 2
                    endsWithBranch = true
                }
                break
            }
            ops[n] = op
            n++
            at += 4
        }

        var entry = 0L
        if (n > 0) {
            val len = compiler.compile(pc, ops, n, endsWithBranch)
            var code = arena.add(compiler.asm.buf, len)
            if (code == 0L) {
                flush()
                val len2 = compiler.compile(pc, ops, n, endsWithBranch)
                code = arena.add(compiler.asm.buf, len2)
            }
            entry = code
        }

        val block = RspBlock(pc, n, entry)
        table[pc ushr 2] = block
        return block
    }

    fun advance(budget: Int) {
        rspHostRef = rsp
        val ctx = this.ctx
        val chaining = !nochain
        var pendingLink = 0L
        var remaining = budget
        while (remaining > 0 && rsp.taskInFlight) {
            if (imemDirty && selectUniverse()) pendingLink = 0
            if (rsp.branchState != STATE_STEP) {
                pendingLink = 0
                if (rsp.interpretStep()) return
                remaining--
                continue
            }
            val pc = rsp.pc and 0xFFC
            val gen = generation
            var block = table[pc ushr 2]
            if (block == null) block = compile(pc)
            if (block.entry == 0L) {
                pendingLink = 0
                if (rsp.interpretStep()) return
                remaining--
                continue
            }
            if (pendingLink != 0L) {
                if (gen == generation) patchLink(pendingLink, block.entry)
                pendingLink = 0
            }
            ctx[RSP_INSTRS / 8] = 0
            ctx[RSP_LIMIT / 8] = if (remaining < CHAIN_CHUNK) remaining.toLong() else CHAIN_CHUNK.toLong()
            ctx[RSP_DISPATCH / 8] = block.entry
            ctx[RSP_LINKSRC / 8] = 0
            rsp.gpr[0] = 0
            trampoline!!(ctx)
            val ran = ctx[RSP_INSTRS / 8].toInt()
            rsp.pc = ctx[RSP_PC / 8].toInt() and 0xFFC
            rsp.branchState = ctx[RSP_STATE / 8].toInt()
            rsp.cycles += ran
            remaining -= ran
            pendingLink = if (chaining) ctx[RSP_LINKSRC / 8] else 0L
            if (rsp.cycles > 20_000_000L) {
                rsp.overrunFinish()
                return
            }
        }
    }

    companion object {
        fun create(rsp: RSP): RspDynarec? {
            if (!N64Tuning.rspJit) return null
            val arena = CodeArena()
            if (!arena.init()) return null
            return RspDynarec(rsp, arena)
        }
    }
}
