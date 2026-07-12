package snes.core

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.TimeSource

class BenchmarkTest {
    private fun busyRom(): ByteArray {
        val program = TestRom.Assembler()
            .clc()
            .xce()
            .rep(0x30)
            .ldaImmediate16(0x0000)
            .byte(0x1A)
            .byte(0x8F).byte(0x00).byte(0x00).byte(0x7E)
            .byte(0xEB)
            .byte(0x1A)
            .byte(0xEB)
            .byte(0x80).byte(0xF6)
            .build()

        return TestRom.build(program)
    }

    private fun heavyScene(console: SNES) {
        val ppu = console.ppu

        for (i in 0 until 0x8000) {
            ppu.vram[i] = ((i * 0x2465) and 0xFFFF).toShort()
        }

        for (i in 0 until 256) {
            ppu.cgram[i] = ((i * 0x0821) and 0x7FFF).toShort()
        }

        for (sprite in 0 until 128) {
            ppu.oam[sprite * 4] = ((sprite * 13) and 0xFF).toByte()
            ppu.oam[sprite * 4 + 1] = ((sprite * 7) and 0xFF).toByte()
            ppu.oam[sprite * 4 + 2] = (sprite and 0xFF).toByte()
            ppu.oam[sprite * 4 + 3] = 0x30
        }

        ppu.writeReg(0x2100, 0x0F)
        ppu.writeReg(0x2101, 0x62)
        ppu.writeReg(0x2105, 0x01)
        ppu.writeReg(0x2106, 0x00)
        ppu.writeReg(0x2107, 0x40)
        ppu.writeReg(0x2108, 0x48)
        ppu.writeReg(0x2109, 0x50)
        ppu.writeReg(0x210B, 0x11)
        ppu.writeReg(0x210C, 0x01)

        ppu.writeReg(0x212C, 0x17)
        ppu.writeReg(0x212D, 0x02)

        ppu.writeReg(0x2123, 0x22)
        ppu.writeReg(0x2126, 40)
        ppu.writeReg(0x2127, 200)
        ppu.writeReg(0x212E, 0x01)

        ppu.writeReg(0x2130, 0x02)
        ppu.writeReg(0x2131, 0x3F)
    }

    private fun measure(label: String, console: SNES, frames: Int): Double {
        for (i in 0 until 30) console.runFrame()

        val start = TimeSource.Monotonic.markNow()

        for (i in 0 until frames) {
            console.runFrame()
            console.apu.drain()
        }

        val elapsed = start.elapsedNow()
        val fps = frames / elapsed.inWholeMicroseconds.toDouble() * 1e6

        println("[bench] $label: ${fps.toInt()} fps (${elapsed.inWholeMicroseconds / frames} us/frame)")

        return fps
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun optimized(): Boolean = getenv("KURTOS_BENCH")?.toKString() != null

    private fun gate(fps: Double) {
        if (!optimized()) return
        assertTrue(fps >= 60.0, "only ${fps.toInt()} fps, need 60 for full speed")
    }

    @Test
    fun sustainsFullSpeedOnHeavyScene() {
        val console = SNES(busyRom())
        heavyScene(console)

        gate(measure("mode1 4-layer + 128 sprites + color math", console, 300))
    }

    @Test
    fun sustainsFullSpeedOnMode7() {
        val console = SNES(busyRom())
        heavyScene(console)

        console.ppu.writeReg(0x2105, 0x07)
        console.ppu.writeReg(0x211B, 0x00)
        console.ppu.writeReg(0x211B, 0x01)
        console.ppu.writeReg(0x211C, 0x40)
        console.ppu.writeReg(0x211C, 0x00)
        console.ppu.writeReg(0x211D, 0x40)
        console.ppu.writeReg(0x211D, 0x00)
        console.ppu.writeReg(0x211E, 0x00)
        console.ppu.writeReg(0x211E, 0x01)

        gate(measure("mode7 + sprites", console, 300))
    }
}
