package snes.core

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.SEEK_END
import platform.posix.fclose
import platform.posix.fwrite
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.getenv
import kotlin.test.Test

class GameDiagTest {
    @OptIn(ExperimentalForeignApi::class)
    private fun load(): ByteArray? {
        val path = getenv("KURTOS_GAME")?.toKString() ?: return null
        val handle = fopen(path, "rb") ?: return null

        try {
            fseek(handle, 0, SEEK_END)
            val size = ftell(handle).toInt()
            fseek(handle, 0, 0)

            val data = ByteArray(size)
            data.usePinned { fread(it.addressOf(0), 1u, size.toULong(), handle) }
            return data
        } finally {
            fclose(handle)
        }
    }

    @Test
    fun report() {
        val image = load() ?: return

        val console = SNES(image)
        val cart = console.cartridge

        println("[diag] title='${cart.title}' supported=${cart.supported} hiRom=${cart.hiRom} fastRom=${cart.fastRom}")
        println("[diag] rom=${cart.rom.size} sram=${cart.sram.size} chip=${cart.chip}")

        var lastMode = -1

        for (frame in 0 until 5000) {
            val racing = console.ppu.debugMode == 7
            val phase = (frame / 24) % 4
            val buttons = when {
                racing -> 0
                phase == 0 -> kapi.emu.Button.START
                phase == 2 -> kapi.emu.Button.A
                else -> 0
            }

            console.debugTrace = false

            console.setButtons(buttons)
            console.runFrame()

            val ppu = console.ppu

            if (ppu.debugMode != lastMode) {
                lastMode = ppu.debugMode
                println(
                    "[diag] frame=$frame MODE -> ${ppu.debugMode} tm=${hex(ppu.debugMain)}" +
                        " hdma=${hex(console.regs.hdmaEnabled)}",
                )
            }

            if (frame in intArrayOf(300, 1500, 2100, 2500, 3000, 4000)) dump(console, "smk-$frame")

            if (frame == 2400) {
                println(
                    "[chan] overscan=${console.ppu.debugOverscan}" +
                        " wram2E=${hex(console.bus.wram[0x2E].toInt() and 0xFF)}" +
                        " wram172=${hex(console.bus.wram[0x172].toInt() and 0xFF)}" +
                        " wramD0=${hex(console.bus.wram[0xD0].toInt() and 0xFF)}",
                )
                for (c in 0 until 8) {
                    println(
                        "[chan] $c ctrl=${hex(console.dma.debugControl[c])}" +
                            " b=${hex(console.dma.debugBAddress[c])}" +
                            " a=${hex(console.dma.debugABank[c])}:${hex(console.dma.debugAAddress[c])}" +
                            " ind=${hex(console.dma.debugIndirectBank[c])}:${hex(console.dma.debugIndirect[c])}",
                    )
                }

                val table = StringBuilder()
                for (i in 0x640 until 0x64D) table.append(" ${hex(console.bus.wram[i].toInt() and 0xFF)}")
                println("[chan] table@0640:$table")
            }

            if (frame % 1000 == 999 || (ppu.debugMode == 7 && frame % 100 == 0)) {
                println(
                    "[diag] frame=$frame mode=${ppu.debugMode} tm=${hex(ppu.debugMain)}" +
                        " m7abcd=[${ppu.debugM7.take(4).joinToString(",") { hex(it) }}]" +
                        " m7xy=[${ppu.debugM7.drop(4).joinToString(",") { hex(it) }}]" +
                        " hofs=${hex(ppu.debugHofs)} vofs=${hex(ppu.debugVofs)}" +
                        " hdma=${hex(console.regs.hdmaEnabled)} colours=${colours(console)}",
                )
            }
        }

        println(
            "[diag] m7Writes=${console.ppu.debugM7Writes} hdmaInits=${console.dma.debugInits}" +
                " hdmaRuns=${console.dma.debugRuns} hdmaUnits=${console.dma.debugUnits}",
        )

        println(
            "[diag] hdmaWrites=${console.regs.debugHdmaWrites} nonZero=${console.regs.debugHdmaNonZero}" +
                " lastHdma=${hex(console.regs.debugLastHdma)} gpdmaWrites=${console.regs.debugGpdmaWrites}",
        )

        println(
            "[diag] irqs=${console.debugIrqs} m7Lines=${console.ppu.debugM7Lines}" +
                " m7DistinctMatrices=${console.ppu.debugM7Distinct}",
        )

        println(
            "[diag] nmitimen=${hex(console.regs.debugNmitimen)} irqMode=${console.regs.irqMode}" +
                " htime=${console.regs.htime} vtime=${console.regs.vtime} hdma=${hex(console.regs.hdmaEnabled)}",
        )

        val chip = cart.coProcessor as? Dsp1
        if (chip != null) {
            println("[diag] dsp1 reads=${chip.debugReads} writes=${chip.debugWrites} commands=${chip.debugCommands} raster=${chip.debugRaster}")
            val hist = StringBuilder()
            for (i in 0 until 64) if (chip.debugHistogram[i] > 0) hist.append(" ${hex(i)}=${chip.debugHistogram[i]}")
            println("[diag] dsp1 commands used:$hist")
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun dump(console: SNES, name: String) {
        val out = getenv("KURTOS_DUMP")?.toKString() ?: return
        val handle = fopen("$out/$name.ppm", "wb") ?: return

        val header = "P6\n${Ppu.WIDTH} ${Ppu.HEIGHT}\n255\n"
        val bytes = ByteArray(header.length + Ppu.WIDTH * Ppu.HEIGHT * 3)

        for (i in header.indices) bytes[i] = header[i].code.toByte()

        var at = header.length
        for (p in console.frame) {
            val v = p.toInt() and 0xFFFF
            bytes[at++] = (((v and 0x1F) * 255 / 31)).toByte()
            bytes[at++] = ((((v shr 5) and 0x1F) * 255 / 31)).toByte()
            bytes[at++] = ((((v shr 10) and 0x1F) * 255 / 31)).toByte()
        }

        bytes.usePinned { fwrite(it.addressOf(0), 1u, bytes.size.toULong(), handle) }
        fclose(handle)
    }

    private fun colours(console: SNES): Int {
        val seen = HashSet<Int>()
        for (p in console.frame) {
            seen.add(p.toInt() and 0xFFFF)
            if (seen.size > 80) break
        }
        return seen.size
    }

    private fun hex(value: Int) = "0x${value.toString(16)}"
}
