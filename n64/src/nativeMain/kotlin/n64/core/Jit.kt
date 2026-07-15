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
import kotlinx.cinterop.toKString
import kotlinx.cinterop.toLong

const val JIT_PAGE_SHIFT = 12
const val JIT_PAGE_SIZE = 1 shl JIT_PAGE_SHIFT
const val JIT_MAX_BLOCK = 64

const val CLS_OP = 0
const val CLS_BRANCH = 1
const val CLS_END = 2

typealias JitFn = CPointer<CFunction<(CPointer<LongVar>?) -> Int>>

class JitBlock(
    val physStart: Int,
    val vbase: Int,
    val length: Int,
    val entry: Long,
)

class Jit(private val n64: N64, private val arena: JitArena) {
    private val cpu = n64.cpu
    private val table = arrayOfNulls<JitBlock>(RDRAM_SIZE ushr 2)
    private val pageBlocks = Array(RDRAM_SIZE ushr JIT_PAGE_SHIFT) { ArrayList<JitBlock>() }
    val pageFlags = n64.jitPages

    private val gprPin = cpu.gpr.pin()
    private val rdramPin = n64.rdram.pin()
    private val lutRPin = cpu.tlbLutR.pin()
    private val lutWPin = cpu.tlbLutW.pin()
    private val pagesPin = pageFlags.pin()

    val ctx: CPointer<LongVar> = nativeHeap.allocArray(20)

    private val compiler = JitCompiler(jitCallbacks())
    private val ops = IntArray(JIT_MAX_BLOCK)

    private var trampoline: JitFn? = null
    private var trampolineEnd = 0
    private var generation = 0
    private val linkSlots = ArrayList<Long>()
    private val linkOrig = ArrayList<Int>()
    private val nochain = platform.posix.getenv("KURTOS_JIT_NOCHAIN") != null

    var lookups = 0L
        private set
    var compiles = 0L
        private set
    var invalidations = 0L
        private set
    var bails = 0L
        private set
    var blockInstrs = 0L
        private set
    var stepInstrs = 0L
        private set

    private val skip = platform.posix.getenv("KURTOS_JIT_SKIP")?.toKString()?.split(",")?.toHashSet() ?: HashSet()
    private val skipBranch = "branch" in skip
    private val skipLoad = "load" in skip
    private val skipStore = "store" in skip
    private val skipMul = "mul" in skip
    private val skipShift = "shift" in skip
    private val skipAlu = "alu" in skip

    init {
        ctx[CTX_GPR / 8] = gprPin.addressOf(0).toLong()
        ctx[CTX_RDRAM / 8] = rdramPin.addressOf(0).toLong()
        ctx[CTX_LUTR / 8] = lutRPin.addressOf(0).toLong()
        ctx[CTX_LUTW / 8] = lutWPin.addressOf(0).toLong()
        ctx[CTX_PAGES / 8] = pagesPin.addressOf(0).toLong()
        installTrampoline()
    }

    private fun installTrampoline() {
        val ret = compiler.buildTrampoline()
        val code = arena.add(compiler.asm.buf, compiler.asm.len)
        trampoline = code.toCPointer()
        ctx[CTX_RETURN / 8] = code + ret
        trampolineEnd = arena.offset
    }

    fun flush() {
        table.fill(null)
        for (list in pageBlocks) list.clear()
        pageFlags.fill(0)
        linkSlots.clear()
        linkOrig.clear()
        arena.reset()
        installTrampoline()
        generation++
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

    private fun resetLinks() {
        for (i in linkSlots.indices) writeRel(linkSlots[i], linkOrig[i])
        linkSlots.clear()
        linkOrig.clear()
    }

    fun invalidatePage(page: Int) {
        val list = pageBlocks[page]
        for (block in list) {
            val index = block.physStart ushr 2
            if (table[index] === block) table[index] = null
        }
        list.clear()
        pageFlags[page] = 0
        if (linkSlots.isNotEmpty()) resetLinks()
        invalidations++
    }

    fun invalidateRange(start: Int, length: Int) {
        if (length <= 0) return
        var page = (start and RDRAM_MASK) ushr JIT_PAGE_SHIFT
        val last = ((start and RDRAM_MASK) + length - 1) ushr JIT_PAGE_SHIFT
        while (page <= last && page < pageFlags.size) {
            if (pageFlags[page].toInt() != 0) invalidatePage(page)
            page++
        }
    }

    var onUnit: ((Long, Int, Boolean) -> Unit)? = null

    fun run() {
        jitHostRef = n64
        val cpu = this.cpu
        val ctx = this.ctx
        val hook = onUnit
        val trampoline = this.trampoline!!
        val chaining = hook == null && !nochain
        var pendingLink = 0L
        while (!cpu.frameDone && cpu.count < cpu.frameDeadline) {
            if (cpu.branchState != STATE_STEP) {
                pendingLink = 0
                val at = cpu.pc
                cpu.step()
                hook?.invoke(at, 1, false)
                continue
            }
            val wide = cpu.pc
            val pc = wide.toInt()
            if (wide != pc.toLong() || pc and 3 != 0 ||
                (pc and 0xC0000000.toInt()) != 0x80000000.toInt()
            ) {
                pendingLink = 0
                cpu.step()
                hook?.invoke(wide, 1, false)
                continue
            }
            val phys = pc and 0x1FFFFFFF
            if (phys >= RDRAM_SIZE) {
                pendingLink = 0
                cpu.step()
                hook?.invoke(wide, 1, false)
                continue
            }
            lookups++
            var block = table[phys ushr 2]
            val gen = generation
            if (block == null || block.vbase != pc) block = compile(pc, phys)
            if (block.entry == 0L) {
                pendingLink = 0
                stepInstrs++
                cpu.step()
                hook?.invoke(wide, 1, false)
                continue
            }
            if (pendingLink != 0L) {
                if (gen == generation) patchLink(pendingLink, block.entry)
                pendingLink = 0
            }
            ctx[CTX_COUNT / 8] = cpu.count
            ctx[CTX_HI / 8] = cpu.hi
            ctx[CTX_LO / 8] = cpu.lo
            ctx[CTX_INITMODE / 8] = if (n64.mi.initMode) 1L else 0L
            ctx[CTX_LIMIT / 8] = if (chaining) chainLimit() else Long.MAX_VALUE
            ctx[CTX_DISPATCH / 8] = block.entry
            ctx[CTX_LINKSRC / 8] = 0
            ctx[CTX_INSTRS / 8] = 0
            cpu.gpr[0] = 0
            val exit = trampoline(ctx)
            cpu.pc = ctx[CTX_PC / 8]
            cpu.count = ctx[CTX_COUNT / 8]
            cpu.hi = ctx[CTX_HI / 8]
            cpu.lo = ctx[CTX_LO / 8]
            val ran = ctx[CTX_INSTRS / 8]
            cpu.instructions += ran
            blockInstrs += ran
            hook?.invoke(wide, block.length, true)
            if (exit != 0) {
                bails++
                pendingLink = 0
                cpu.branchState = ctx[CTX_STATE / 8].toInt()
                cpu.branchTarget = ctx[CTX_TARGET / 8]
                if (cpu.count > cpu.nextEventCount) n64.triggerEvent()
                if (!cpu.frameDone) {
                    val at = cpu.pc
                    cpu.step()
                    hook?.invoke(at, 1, false)
                }
            } else {
                pendingLink = if (chaining) ctx[CTX_LINKSRC / 8] else 0L
                if (cpu.count > cpu.nextEventCount) {
                    n64.triggerEvent()
                    pendingLink = 0
                }
            }
        }
    }

    private fun chainLimit(): Long {
        val deadline = cpu.frameDeadline - 1
        val next = cpu.nextEventCount
        return if (deadline < next) deadline else next
    }

    private fun compile(vbase: Int, phys: Int): JitBlock {
        compiles++
        var at = phys
        var n = 0
        var endsWithBranch = false
        val pageEnd = (phys and (JIT_PAGE_SIZE - 1).inv()) + JIT_PAGE_SIZE
        while (at + 4 <= pageEnd && n < JIT_MAX_BLOCK - 1) {
            val op = n64.rdram[at ushr 2]
            val cls = classify(op)
            if (cls == CLS_END) break
            if (cls == CLS_BRANCH) {
                if (at + 8 <= pageEnd && classify(n64.rdram[(at + 4) ushr 2]) == CLS_OP) {
                    ops[n] = op
                    ops[n + 1] = n64.rdram[(at + 4) ushr 2]
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
            val len = compiler.compile(vbase, ops, n, endsWithBranch)
            var code = arena.add(compiler.asm.buf, len)
            if (code == 0L) {
                flush()
                val len2 = compiler.compile(vbase, ops, n, endsWithBranch)
                code = arena.add(compiler.asm.buf, len2)
            }
            entry = code
        }

        val block = JitBlock(phys, vbase, n, entry)
        table[phys ushr 2] = block
        val page = phys ushr JIT_PAGE_SHIFT
        pageBlocks[page].add(block)
        pageFlags[page] = 1
        return block
    }

    private fun classify(op: Int): Int {
        val cls = classifyRaw(op)
        if (cls == CLS_BRANCH && skipBranch) return CLS_END
        if (cls == CLS_OP && skipClass(op)) return CLS_END
        return cls
    }

    private fun skipClass(op: Int): Boolean {
        val major = op ushr 26
        if (major in 32..39 || major == 55) return skipLoad
        if (major in 40..47 || major == 63) return skipStore
        if (major == 0) {
            val f = op and 0x3F
            if (f in 24..31) return skipMul
            if (f in 0..7) return skipShift
            if (f in 32..47) return skipAlu
            if (f in 56..63) return skipShift
        }
        if (major in 8..15 || major in 24..25) return skipAlu
        return false
    }

    private fun classifyRaw(op: Int): Int = when (op ushr 26) {
        0 -> when (op and 0x3F) {
            0, 2, 3, 4, 6, 7, 15, 16, 17, 18, 19, 20, 22, 23,
            24, 25, 26, 27, 28, 29, 30, 31,
            32, 33, 34, 35, 36, 37, 38, 39, 42, 43, 44, 45, 46, 47,
            56, 58, 59, 60, 62, 63,
            -> CLS_OP

            8, 9 -> CLS_BRANCH
            else -> CLS_END
        }

        1 -> when ((op ushr 16) and 0x1F) {
            0, 1, 2, 3, 16, 17, 18, 19 -> CLS_BRANCH
            else -> CLS_END
        }

        2, 3, 4, 5, 6, 7, 20, 21, 22, 23 -> CLS_BRANCH

        8, 9, 10, 11, 12, 13, 14, 15, 24, 25,
        32, 33, 34, 35, 36, 37, 38, 39,
        40, 41, 42, 43, 46, 47,
        55, 63,
        -> CLS_OP

        else -> CLS_END
    }

    companion object {
        fun create(n64: N64): Jit? {
            if (platform.posix.getenv("KURTOS_NOJIT") != null) return null
            if (platform.posix.getenv("KURTOS_BREAK") != null) return null
            val arena = JitArena()
            if (!arena.init()) return null
            return Jit(n64, arena)
        }
    }
}
