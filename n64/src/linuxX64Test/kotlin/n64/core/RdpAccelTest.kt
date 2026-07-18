package n64.core

import kapi.gpu.Gpu
import kapi.gpu.GpuBackend
import kapi.gpu.GpuBuffer
import kapi.gpu.GpuKernel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class FakeBuffer(override val words: Int, override val gpuAddress: Long) : GpuBuffer {
    val data = IntArray(words)
    override fun writeWord(word: Int, value: Int) {
        data[word] = value
    }

    override fun readWord(word: Int): Int = data[word]

    override fun write(dstWord: Int, src: IntArray, srcWord: Int, count: Int) {
        src.copyInto(data, dstWord, srcWord, srcWord + count)
    }

    override fun read(srcWord: Int, dst: IntArray, dstWord: Int, count: Int) {
        data.copyInto(dst, dstWord, srcWord, srcWord + count)
    }

    override fun zero() = data.fill(0)
}

private class FakeKernel(override val name: String) : GpuKernel

private class FakeBackend(private val failDispatch: Boolean = false) : GpuBackend {
    override val name = "fake"
    private val buffers = ArrayList<FakeBuffer>()
    private var nextAddr = 0x100000L
    var dispatches = 0
        private set

    override fun available() = true
    override fun kernel(name: String): GpuKernel? = if (name == "rdp") FakeKernel(name) else null

    override fun alloc(words: Int): GpuBuffer {
        val b = FakeBuffer(words, nextAddr)
        nextAddr += words.toLong() * 4L + 0x1000L
        buffers.add(b)
        return b
    }

    override fun free(buffer: GpuBuffer) {}

    override fun dispatch(kernel: GpuKernel, kernargs: GpuBuffer, groups: Int, threadsPerGroup: Int): Boolean {
        if (failDispatch) return false
        dispatches++
        val ka = kernargs as FakeBuffer
        val rd = find(addr(ka, 0, 1))
        val hd = find(addr(ka, 2, 3))
        val pr = find(addr(ka, 4, 5))
        val primCount = ka.data[6]
        val bx = ka.data[7]
        val by = ka.data[8]
        val bw = ka.data[9]
        val bh = ka.data[10]
        for (gid in 0 until groups * threadsPerGroup) core(gid, rd, hd, pr, primCount, bx, by, bw, bh)
        return true
    }

    private fun addr(ka: FakeBuffer, lo: Int, hi: Int): Long =
        (ka.data[lo].toLong() and 0xFFFFFFFFL) or (ka.data[hi].toLong() shl 32)

    private fun find(a: Long): FakeBuffer = buffers.first { it.gpuAddress == a }

    private fun core(gid: Int, rd: FakeBuffer, hd: FakeBuffer, pr: FakeBuffer, count: Int, bx: Int, by: Int, bw: Int, bh: Int) {
        if (gid >= bw * bh) return
        val px = bx + gid % bw
        val py = by + gid / bw
        for (p in 0 until count) {
            val base = p * 12
            if (px < pr.data[base + 1] || px > pr.data[base + 3]) continue
            if (py < pr.data[base + 2] || py > pr.data[base + 4]) continue
            val width = pr.data[base + 5]
            val image = pr.data[base + 6]
            val fc = pr.data[base + 7]
            if (pr.data[base] == RDP_KIND_FILL16) {
                val value = if (px and 1 != 0) fc and 0xFFFF else (fc ushr 16) and 0xFFFF
                store16(rd, hd, image + (py * width + px) * 2, value)
            } else {
                store32(rd, image + (py * width + px) * 4, fc)
            }
        }
    }

    private fun store16(rd: FakeBuffer, hd: FakeBuffer, byteAddr: Int, value: Int) {
        val masked = byteAddr and RDRAM_MASK
        val index = masked ushr 2
        val shift = 16 - ((masked and 2) shl 3)
        rd.data[index] = (rd.data[index] and (0xFFFF shl shift).inv()) or ((value and 0xFFFF) shl shift)
        val hByte = masked ushr 1
        val hb = if (value and 1 != 0) 3 else 0
        val hw = hByte ushr 2
        val lane = (hByte and 3) shl 3
        hd.data[hw] = (hd.data[hw] and (0xFF shl lane).inv()) or (hb shl lane)
    }

    private fun store32(rd: FakeBuffer, byteAddr: Int, value: Int) {
        rd.data[(byteAddr and RDRAM_MASK) ushr 2] = value
    }
}

private class Scene {
    val rdram = IntArray(RDRAM_SIZE / 4)
    val hidden = ByteArray(RDRAM_SIZE / 2)
}

private fun referenceFill(s: Scene, kind: Int, x0: Int, y0: Int, x1: Int, y1: Int, width: Int, image: Int, fc: Int) {
    for (y in y0..y1) {
        for (x in x0..x1) {
            if (kind == RDP_KIND_FILL16) {
                val value = if (x and 1 == 0) (fc ushr 16) and 0xFFFF else fc and 0xFFFF
                val at = (image + (y * width + x) * 2) and RDRAM_MASK
                val index = at ushr 2
                val shift = 16 - ((at and 2) shl 3)
                s.rdram[index] = (s.rdram[index] and (0xFFFF shl shift).inv()) or ((value and 0xFFFF) shl shift)
                s.hidden[at ushr 1] = (if (value and 1 != 0) 3 else 0).toByte()
            } else {
                s.rdram[((image + (y * width + x) * 4) and RDRAM_MASK) ushr 2] = fc
            }
        }
    }
}

private class Fill(val kind: Int, val x0: Int, val y0: Int, val x1: Int, val y1: Int, val width: Int, val image: Int, val fc: Int)

private fun randomFills(seed: Long, count: Int): List<Fill> {
    var state = seed
    fun next(): Int {
        state = state * 6364136223846793005L + 1442695040888963407L
        return ((state ushr 33).toInt()) and 0x7FFFFFFF
    }
    val out = ArrayList<Fill>()
    val width = 320
    val image = 0x100000
    repeat(count) {
        val kind = if (next() and 1 == 0) RDP_KIND_FILL16 else RDP_KIND_FILL32
        val x0 = next() % width
        val x1 = x0 + next() % (width - x0)
        val y0 = next() % 240
        val y1 = y0 + next() % (240 - y0)
        val fc = next() or (next() shl 1)
        out.add(Fill(kind, x0, y0, x1, y1, width, image, fc))
    }
    return out
}

class RdpAccelTest {
    private fun runAccel(backend: GpuBackend?, fills: List<Fill>): Scene {
        val s = Scene()
        if (backend != null) Gpu.register(backend) else Gpu.unregister()
        try {
            val accel = RdpAccel(s.rdram, s.hidden) { _, _ -> }
            for (f in fills) {
                val accepted = accel.enqueueFill(f.kind, f.x0, f.y0, f.x1, f.y1, f.width, f.image, f.fc)
                if (!accepted) referenceFill(s, f.kind, f.x0, f.y0, f.x1, f.y1, f.width, f.image, f.fc)
            }
            accel.flush()
        } finally {
            Gpu.unregister()
        }
        return s
    }

    private fun runReference(fills: List<Fill>): Scene {
        val s = Scene()
        for (f in fills) referenceFill(s, f.kind, f.x0, f.y0, f.x1, f.y1, f.width, f.image, f.fc)
        return s
    }

    private fun assertSame(label: String, a: Scene, b: Scene) {
        for (i in a.rdram.indices) {
            if (a.rdram[i] != b.rdram[i]) {
                throw AssertionError("$label: rdram[$i] accel=${a.rdram[i].toString(16)} ref=${b.rdram[i].toString(16)}")
            }
        }
        assertTrue(a.hidden.contentEquals(b.hidden), "$label: hidden mismatch")
    }

    @Test
    fun gpuFillsMatchSoftware() {
        val fills = randomFills(0xC0FFEE, 200)
        assertSame("gpu", runAccel(FakeBackend(), fills), runReference(fills))
    }

    @Test
    fun dispatchFailureFallsBackBitExact() {
        val fills = randomFills(0x1234, 200)
        assertSame("dispatch-fail", runAccel(FakeBackend(failDispatch = true), fills), runReference(fills))
    }

    @Test
    fun noBackendFallsBackBitExact() {
        val fills = randomFills(0x99, 200)
        assertSame("no-backend", runAccel(null, fills), runReference(fills))
    }

    @Test
    fun batchesRatherThanDispatchingPerFill() {
        val backend = FakeBackend()
        val fills = randomFills(0x55, 300).map { Fill(RDP_KIND_FILL16, it.x0, it.y0, it.x1, it.y1, it.width, it.image, it.fc) }
        runAccel(backend, fills)
        assertEquals(1, backend.dispatches, "homogeneous fills should batch into one dispatch")
    }

    @Test
    fun depthChangeForcesFlush() {
        val backend = FakeBackend()
        val image = 0x100000
        val fills = listOf(
            Fill(RDP_KIND_FILL16, 0, 0, 10, 10, 320, image, 0x1111),
            Fill(RDP_KIND_FILL16, 5, 5, 20, 20, 320, image, 0x2222),
            Fill(RDP_KIND_FILL32, 0, 0, 10, 10, 320, image, 0x3333),
            Fill(RDP_KIND_FILL16, 0, 0, 10, 10, 320, image, 0x4444),
        )
        assertSame("depth-change", runAccel(backend, fills), runReference(fills))
        assertEquals(3, backend.dispatches, "each depth switch should start a new batch")
    }

    @Test
    fun overCapacityFlushesMidStream() {
        val backend = FakeBackend()
        val fills = randomFills(0xABCD, 5000)
        assertSame("over-capacity", runAccel(backend, fills), runReference(fills))
        assertTrue(backend.dispatches >= 2, "expected a capacity flush, got ${backend.dispatches}")
    }

    @Test
    fun emptyRectIsAccepted() {
        Gpu.register(FakeBackend())
        try {
            val s = Scene()
            val accel = RdpAccel(s.rdram, s.hidden) { _, _ -> }
            assertTrue(accel.enqueueFill(RDP_KIND_FILL16, 10, 10, 5, 5, 320, 0x100000, 0x1234))
            assertFalse(accel.pending)
        } finally {
            Gpu.unregister()
        }
    }
}
