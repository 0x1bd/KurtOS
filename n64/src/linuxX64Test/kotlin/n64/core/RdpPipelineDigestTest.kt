package n64.core

import kurtos.testkit.TestLog
import kotlin.test.Test
import kotlin.test.assertTrue

private const val CMD_BASE = 0x300000
private const val COLOR_BASE = 0x100000
private const val Z_BASE = 0x180000
private const val TEX_BASE = 0x200000
private const val FB_WIDTH = 64
private const val FB_HEIGHT = 48
private const val PIPELINE_DIGEST = 0x501f2f7353e06566L

private class Random(private var state: Long) {

    fun next(): Int {
        state = state * 6364136223846793005L + 1442695040888963407L
        return (state ushr 33).toInt()
    }

    fun below(bound: Int): Int = if (bound <= 0) 0 else ((next() and 0x7FFFFFFF) % bound)

    fun bool(): Boolean = next() and 1 != 0
}

private class CommandList {

    private val words = ArrayList<Int>()

    fun add(vararg value: Int) {
        for (v in value) words.add(v)
    }

    fun command(opcode: Int, w0: Int, w1: Int) {
        words.add((opcode shl 24) or (w0 and 0xFFFFFF))
        words.add(w1)
    }

    fun writeTo(console: N64, at: Int): Int {
        for ((index, word) in words.withIndex()) console.ramWrite32(at + index * 4, word)
        return at + words.size * 4
    }

}

class RdpPipelineDigestTest {

    @Test
    fun digest() {
        val console = N64(ByteArray(0x101000))
        val rdp = console.rdp

        var pixels = 0L
        var triangles = 0
        var rectangles = 0
        var digest = 0x811C9DC5UL.toLong()

        for (scene in 0 until 240) {
            val random = Random(scene.toLong() * 6364136223846793005L + 12345L)
            seedMemory(console, random)

            val list = CommandList()
            buildScene(list, random)
            val end = list.writeTo(console, CMD_BASE)

            rdp.writeDpc(DPC_START, CMD_BASE, -1)
            rdp.writeDpc(DPC_END, end, -1)
            rdp.flushDisplay()

            digest = mix(digest, console, random)
        }

        pixels = rdp.pixels
        triangles = rdp.triangles
        rectangles = rdp.rectangles

        TestLog.report(
            "rdpdigest",
            listOf(
                "digest=${digest.toULong().toString(16)}",
                "triangles=$triangles rectangles=$rectangles pixels=$pixels",
            ),
        )

        assertTrue(triangles > 0 && rectangles > 0, "the scene generator drew nothing")
        assertTrue(pixels > 0, "no pixel work reached the rasterizer")
        assertTrue(
            digest == PIPELINE_DIGEST,
            "the RDP pipeline no longer produces the same framebuffer for these scenes: got " +
                digest.toULong().toString(16) + ", expected " + PIPELINE_DIGEST.toULong().toString(16) +
                ". This number came from rendering the same scenes through the CPU rasterizer that " +
                "shaders/rdpspan.spz replaced. Change it only when you meant to change what the RDP draws.",
        )
    }

    private fun seedMemory(console: N64, random: Random) {
        for (word in 0 until FB_WIDTH * FB_HEIGHT) {
            console.ramWrite32(COLOR_BASE + word * 4, random.next())
            console.ramWrite32(Z_BASE + word * 4, random.next())
        }
        for (word in 0 until 0x400) console.ramWrite32(TEX_BASE + word * 4, random.next())
        for (index in 0 until FB_WIDTH * FB_HEIGHT * 4) {
            console.hidden[(COLOR_BASE ushr 1) + index] = random.next().toByte()
            console.hidden[(Z_BASE ushr 1) + index] = random.next().toByte()
        }
    }

    private fun buildScene(list: CommandList, random: Random) {
        val colorSize = if (random.bool()) 2 else 3
        val cycleType = random.below(4)

        list.command(0x2D, (0 shl 12) or 0, (FB_WIDTH shl 14) or (FB_HEIGHT shl 2))
        list.command(0x3F, (0 shl 21) or (colorSize shl 19) or (FB_WIDTH - 1), COLOR_BASE)
        list.command(0x3E, 0, Z_BASE)
        list.command(0x37, 0, random.next())
        list.command(0x38, 0, random.next())
        list.command(0x39, 0, random.next())
        list.command(0x3A, random.below(0x100), random.next())
        list.command(0x3B, 0, random.next())
        list.command(0x2E, (random.below(0x8000) shl 16) or random.below(0x10000), 0)

        val format = random.below(4)
        val size = random.below(4)
        list.command(0x3D, (format shl 21) or (size shl 19) or 31, TEX_BASE)
        setTile(list, random, format, size)
        list.command(0x34, (0 shl 12) or 0, (0 shl 24) or (31 shl 12) or 31)
        if (random.bool()) list.command(0x30, 0, (0 shl 24) or (15 shl 14))

        list.command(
            0x2F,
            (cycleType shl 20) or (random.below(2) shl 19) or (random.below(2) shl 13) or
                (random.below(2) shl 12) or (random.below(2) shl 11),
            random.next() and 0x3FFFFFF,
        )
        list.command(0x3C, random.next() and 0xFFFFFF, random.next())

        for (draw in 0 until 6) {
            when (random.below(3)) {
                0 -> fillRect(list, random)
                1 -> texRect(list, random)
                else -> triangle(list, random)
            }
        }
    }

    private fun setTile(list: CommandList, random: Random, format: Int, size: Int) {
        val line = 8
        val w0 = (format shl 21) or (size shl 19) or (line shl 9) or 0
        val w1 = (0 shl 24) or (random.below(16) shl 20) or
            (random.below(2) shl 19) or (random.below(2) shl 18) or (random.below(6) shl 14) or
            (random.below(2) shl 9) or (random.below(2) shl 8) or (random.below(6) shl 4)
        list.command(0x35, w0, w1)
        list.command(0x32, (0 shl 12) or 0, (0 shl 24) or (124 shl 12) or 124)
    }

    private fun fillRect(list: CommandList, random: Random) {
        val x0 = random.below(FB_WIDTH)
        val y0 = random.below(FB_HEIGHT)
        val x1 = x0 + random.below(FB_WIDTH - x0)
        val y1 = y0 + random.below(FB_HEIGHT - y0)
        list.command(0x36, ((x1 shl 2) shl 12) or (y1 shl 2), ((x0 shl 2) shl 12) or (y0 shl 2))
    }

    private fun texRect(list: CommandList, random: Random) {
        val x0 = random.below(FB_WIDTH)
        val y0 = random.below(FB_HEIGHT)
        val x1 = x0 + 1 + random.below(FB_WIDTH - x0)
        val y1 = y0 + 1 + random.below(FB_HEIGHT - y0)
        list.command(if (random.bool()) 0x24 else 0x25, ((x1 shl 2) shl 12) or (y1 shl 2), ((x0 shl 2) shl 12) or (y0 shl 2))
        list.add(
            (random.below(0x4000) shl 16) or random.below(0x4000),
            (random.below(0x800) shl 16) or random.below(0x800),
        )
    }

    private fun triangle(list: CommandList, random: Random) {
        val shade = random.bool()
        val texture = random.bool()
        val depth = random.bool()
        val opcode = 0x08 or (if (shade) 4 else 0) or (if (texture) 2 else 0) or (if (depth) 1 else 0)

        val yh = random.below(FB_HEIGHT) shl 2
        val ym = yh + (random.below(FB_HEIGHT) shl 2)
        val yl = ym + (random.below(FB_HEIGHT) shl 2)
        val flip = random.bool()

        list.add(
            (opcode shl 24) or (if (flip) 0x800000 else 0) or (random.below(8) shl 16) or (yl and 0x3FFF),
            ((ym and 0x3FFF) shl 16) or (yh and 0x3FFF),
            (random.below(FB_WIDTH) shl 16),
            slope(random),
            (random.below(FB_WIDTH) shl 16),
            slope(random),
            (random.below(FB_WIDTH) shl 16),
            slope(random),
        )
        if (shade) for (i in 0 until 16) list.add(random.next() and 0x03FF03FF)
        if (texture) for (i in 0 until 16) list.add(random.next() and 0x0FFF0FFF)
        if (depth) {
            list.add(random.below(0x10000) shl 15)
            for (i in 0 until 3) list.add(random.next() and 0x000FFFFF)
        }
    }

    private fun slope(random: Random): Int = (random.next() and 0x0003FFFF) - 0x00020000

    private fun mix(seed: Long, console: N64, random: Random): Long {
        var value = seed
        for (word in 0 until FB_WIDTH * FB_HEIGHT) {
            value = (value xor console.ramRead32(COLOR_BASE + word * 4).toLong()) * 0x100000001B3L
            value = (value xor console.ramRead32(Z_BASE + word * 4).toLong()) * 0x100000001B3L
        }
        for (index in 0 until FB_WIDTH * FB_HEIGHT * 4) {
            value = (value xor console.hidden[(COLOR_BASE ushr 1) + index].toLong()) * 0x100000001B3L
            value = (value xor console.hidden[(Z_BASE ushr 1) + index].toLong()) * 0x100000001B3L
        }
        return value xor random.next().toLong()
    }
}
