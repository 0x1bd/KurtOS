@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package n64.core

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
    val fn: JitFn?,
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

    val ctx: CPointer<LongVar> = nativeHeap.allocArray(16)

    private val compiler = JitCompiler(jitCallbacks())
    private val ops = IntArray(JIT_MAX_BLOCK)

    var lookups = 0L
        private set
    var compiles = 0L
        private set
    var invalidations = 0L
        private set
    var bails = 0L
        private set

    init {
        ctx[CTX_GPR / 8] = gprPin.addressOf(0).toLong()
        ctx[CTX_RDRAM / 8] = rdramPin.addressOf(0).toLong()
        ctx[CTX_LUTR / 8] = lutRPin.addressOf(0).toLong()
        ctx[CTX_LUTW / 8] = lutWPin.addressOf(0).toLong()
        ctx[CTX_PAGES / 8] = pagesPin.addressOf(0).toLong()
    }

    fun flush() {
        table.fill(null)
        for (list in pageBlocks) list.clear()
        pageFlags.fill(0)
        arena.reset()
    }

    fun invalidatePage(page: Int) {
        val list = pageBlocks[page]
        for (block in list) {
            val index = block.physStart ushr 2
            if (table[index] === block) table[index] = null
        }
        list.clear()
        pageFlags[page] = 0
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

    fun run() {
        jitHostRef = n64
        val cpu = this.cpu
        val ctx = this.ctx
        while (!cpu.frameDone && cpu.count < cpu.frameDeadline) {
            if (cpu.branchState != STATE_STEP) {
                cpu.step()
                continue
            }
            val wide = cpu.pc
            val pc = wide.toInt()
            if (wide != pc.toLong() || pc and 3 != 0 ||
                (pc and 0xC0000000.toInt()) != 0x80000000.toInt()
            ) {
                cpu.step()
                continue
            }
            val phys = pc and 0x1FFFFFFF
            if (phys >= RDRAM_SIZE) {
                cpu.step()
                continue
            }
            lookups++
            var block = table[phys ushr 2]
            if (block == null || block.vbase != pc) block = compile(pc, phys)
            val fn = block.fn
            if (fn == null) {
                cpu.step()
                continue
            }
            ctx[1] = cpu.count
            ctx[2] = cpu.hi
            ctx[3] = cpu.lo
            ctx[6] = if (n64.mi.initMode) 1L else 0L
            cpu.gpr[0] = 0
            val exit = fn(ctx)
            cpu.pc = ctx[0]
            cpu.count = ctx[1]
            cpu.hi = ctx[2]
            cpu.lo = ctx[3]
            cpu.instructions += block.length
            if (cpu.count > cpu.nextEventCount) n64.triggerEvent()
            if (exit != 0) {
                bails++
                cpu.branchState = ctx[4].toInt()
                cpu.branchTarget = ctx[5]
                if (!cpu.frameDone) cpu.step()
            }
        }
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

        var fn: JitFn? = null
        if (n > 0) {
            val len = compiler.compile(vbase, ops, n, endsWithBranch)
            var code = arena.add(compiler.asm.buf, len)
            if (code == 0L) {
                flush()
                code = arena.add(compiler.asm.buf, len)
            }
            if (code != 0L) fn = code.toCPointer()
        }

        val block = JitBlock(phys, vbase, n, fn)
        table[phys ushr 2] = block
        val page = phys ushr JIT_PAGE_SHIFT
        pageBlocks[page].add(block)
        pageFlags[page] = 1
        return block
    }

    private fun classify(op: Int): Int = when (op ushr 26) {
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
            val flag = platform.posix.getenv("KURTOS_JIT")?.toKString()
            if (flag != "1") return null
            if (platform.posix.getenv("KURTOS_BREAK") != null) return null
            val arena = JitArena()
            if (!arena.init()) return null
            return Jit(n64, arena)
        }
    }
}
