package n64.core

import kotlin.test.Test

class GameDiagTest {
    @Test
    fun report() {
        val image = game() ?: return

        val console = N64(image)
        val rom = console.rom

        console.onSerial = { print(it) }

        println("[diag] ${console.describe()}")
        println("[diag] entry=${hex(rom.entry)} size=${rom.bytes.size} country=${rom.country.toChar()}")

        val frames = environment("KURTOS_FRAMES")?.toIntOrNull() ?: 120
        val dumpAt = environment("KURTOS_DUMP_FRAMES")?.split(",")?.mapNotNull { it.toIntOrNull() }
            ?: listOf(1, 10, 30, 60, 120, 240, 600)

        if (environment("KURTOS_FPE_DEBUG") != null) console.cpu.debugFpe = true

        val traceFrom = environment("KURTOS_TRACE")?.toIntOrNull() ?: -1
        environment("KURTOS_BREAK")?.let { console.cpu.debugBreakPc = it.removePrefix("0x").toLong(16) }

        for (frame in 0 until frames) {
            val pulse = (frame / 25) % 2 == 0
            val buttons = when {
                frame in 200..700 && pulse -> if ((frame / 50) % 2 == 0) 0x1000 else 0x8000
                frame in 701..1000 && pulse -> 0x8000
                else -> 0
            }
            console.setButtons(buttons)

            if (traceFrom >= 0 && frame == traceFrom) console.debugTrace = true

            console.runFrame()

            if (frame in dumpAt) dumpFrame(console.vi.frame, VI.WIDTH, VI.HEIGHT, "n64-$frame")

            if (frame % 30 == 0 || frame == frames - 1) {
                println(
                    "[diag] frame=$frame pc=${hex(console.cpu.pc.toInt())}" +
                        " instructions=${console.cpu.instructions}" +
                        " vi=${hex(console.vi.regs[VI_ORIGIN])}/${console.vi.regs[VI_WIDTH]}" +
                        " rspTasks=${console.rsp.tasks} gfx=${console.rsp.gfxTasks} audio=${console.rsp.audioTasks}" +
                        " dpEnd=${hex(console.rdp.regsDpc[DPC_END])} dpCmds=${console.rdp.commandsSeen}" +
                        " tris=${console.rdp.triangles} rects=${console.rdp.rectangles}" +
                        " pixels=${console.rdp.pixels}" +
                        " colours=${colours(console)}",
                )
            }
        }

        if (environment("KURTOS_PROFILE") != null) {
            console.cpu.debugSamples.clear()
            console.cpu.debugProfile = true
            for (frame in 0 until 20) console.runFrame()
            console.cpu.debugProfile = false

            val hot = console.cpu.debugSamples.entries.sortedByDescending { it.value }.take(12)
            val total = console.cpu.debugSamples.values.sum()
            for (entry in hot) {
                println("[hot] ${hex(entry.key)} ${entry.value * 100 / total}%")
            }

            val top = hot.firstOrNull()?.key ?: 0
            if (top != 0) {
                val base = (top and 0x7FFFFFFF) - 0x100
                for (o in 0 until 0x240 step 16) {
                    val words = (0 until 4).joinToString(" ") {
                        console.ramRead32(base + o + it * 4).toUInt().toString(16).padStart(8, '0')
                    }
                    println("[mem] ${hex(0x80000000.toInt() or (base + o))}: $words")
                }
            }
        }

        println(
            "[diag] mi=${hex(console.mi.regs[MI_INTR])}/${hex(console.mi.regs[MI_INTR_MASK])}" +
                " sp=${hex(console.rsp.regs[SP_STATUS])} dp=${hex(console.rdp.regsDpc[DPC_STATUS])}" +
                " ai=${hex(console.ai.regs[AI_STATUS])} audioFrames=${console.ai.frames}",
        )

        println(
            "[diag] interrupts sp=${console.mi.debugRaised[0]} si=${console.mi.debugRaised[1]}" +
                " ai=${console.mi.debugRaised[2]} vi=${console.mi.debugRaised[3]}" +
                " pi=${console.mi.debugRaised[4]} dp=${console.mi.debugRaised[5]}",
        )

        println(
            "[diag] rsp gfxCycles=${console.rsp.gfxCycles} perTask=" +
                "${if (console.rsp.gfxTasks > 0) console.rsp.gfxCycles / console.rsp.gfxTasks else 0}" +
                " overruns=${console.rsp.overruns} yields=${console.rsp.yields} broke=${console.rsp.debugBroke} halted=${console.rsp.debugHalted} overlap=${console.rsp.debugOverlap}" +
                " yieldStruct=${hex(console.rsp.debugYieldStruct)} yieldFlags=${hex(console.rsp.debugYieldFlags)}" +
                " yieldStatus=${hex(console.rsp.debugYieldStatus)}",
        )

        if (console.debugTrace) {
            for (line in console.debugLog) println("[trace] $line")
        }

        val gfx = console.rsp.debugGfxStruct
        println(
            "[diag] gfxTask=${hex(gfx)} type=${hex(console.ramRead32(gfx))}" +
                " flags=${hex(console.ramRead32(gfx + 4))}" +
                " spPc=${hex(console.rsp.pc)} spStatus=${hex(console.rsp.regs[SP_STATUS])}",
        )

        val exceptions = StringBuilder()
        for (i in 0 until 32) {
            if (console.cpu.debugExceptions[i] > 0) exceptions.append(" $i=${console.cpu.debugExceptions[i]}")
        }
        println(
            "[diag] exceptions:$exceptions lastEpc=${hex(console.cpu.debugLastEpc.toInt())}" +
                " lastBad=${hex(console.cpu.debugLastBad.toInt())}" +
                " copPc=${hex(console.cpu.debugCopPc.toInt())} copStatus=${hex(console.cpu.debugCopStatus.toInt())} copFrame=${console.cpu.debugCopFrame}",
        )

        val opcodes = StringBuilder()
        for (i in 0 until 64) if (console.rdp.debugOpcodes[i] > 0) opcodes.append(" ${hex(i)}=${console.rdp.debugOpcodes[i]}")
        println("[diag] rdp opcodes:$opcodes")

        val used = StringBuilder()
        for (i in 0 until 64) if (console.rsp.debugVector[i] > 0) used.append(" $i=${console.rsp.debugVector[i]}")
        println("[diag] rsp vu:$used")

        println(
            "[diag] pif reads=${console.pif.debugReads} status=${console.pif.debugCommands[0]}" +
                " buttons=${console.pif.debugCommands[1]} eepromRead=${console.pif.debugCommands[4]}" +
                " eepromWrite=${console.pif.debugCommands[5]}",
        )

        environment("KURTOS_DUMP_PC")?.let {
            val base = (it.removePrefix("0x").toLong(16).toInt() and 0x7FFFFFFF) - 0x40
            for (o in 0 until 0xA0 step 4) {
                val op = console.ramRead32(base + o)
                println(
                    "[code] ${hex(0x80000000.toInt() or (base + o))}: ${op.toUInt().toString(16).padStart(8, '0')}" +
                        " op=${op ushr 26} rs=${(op ushr 21) and 0x1F} rt=${(op ushr 16) and 0x1F}" +
                        " rd=${(op ushr 11) and 0x1F} sa=${(op ushr 6) and 0x1F} f=${op and 0x3F} imm=${op.toShort()}",
                )
            }
        }

        dumpThreads(console)

        for (line in console.cpu.debugTlbDump()) println("[tlb] $line")
    }

    private fun dumpThreads(console: N64) {
        var at = 0
        while (at < RDRAM_SIZE - 0x200) {
            val state = console.ramRead32(at + 0x10) ushr 16
            val id = console.ramRead32(at + 0x14)
            val priority = console.ramRead32(at + 0x04)
            val next = console.ramRead32(at)
            val queue = console.ramRead32(at + 0x08)
            if ((state == 1 || state == 2 || state == 4 || state == 8) &&
                id in 1..20 && priority in 0..255 &&
                (next == 0 || (next.toLong() and 0xFF000000L) == 0x80000000L) &&
                (queue.toLong() and 0xFF000000L) == 0x80000000L
            ) {
                val ra = console.ramRead32(at + 0x20 + 28 * 8 + 4)
                val a0 = console.ramRead32(at + 0x20 + 3 * 8 + 4)
                val pc = console.ramRead32(at + 0x11C)
                val sp = console.ramRead32(at + 0x20 + 26 * 8 + 4)
                if ((pc.toLong() and 0xFF000000L) == 0x80000000L) {
                    val stack = StringBuilder()
                    if ((sp.toLong() and 0xFF000000L) == 0x80000000L) {
                        for (o in 0 until 0x60 step 4) {
                            val word = console.ramRead32((sp and 0x7FFFFFFF) + o)
                            if ((word.toLong() and 0xFFC00000L) == 0x80000000L && word != pc) {
                                stack.append(" 0x${word.toUInt().toString(16)}")
                            }
                        }
                    }
                    println(
                        "[thread] addr=0x${(at.toLong() or 0x80000000L).toULong().toString(16)} id=$id pri=$priority state=$state" +
                            " pc=0x${pc.toUInt().toString(16)} ra=0x${ra.toUInt().toString(16)}" +
                            " a0=0x${a0.toUInt().toString(16)} sp=0x${sp.toUInt().toString(16)}" +
                            " queue=0x${queue.toUInt().toString(16)} stack:$stack",
                    )
                }
            }
            at += 4
        }
    }

    private fun colours(console: N64): Int {
        val seen = HashSet<Int>()
        for (pixel in console.vi.frame) {
            seen.add(pixel.toInt() and 0xFFFF)
            if (seen.size > 64) break
        }
        return seen.size
    }

    private fun hex(value: Int) = "0x${value.toUInt().toString(16)}"
}
