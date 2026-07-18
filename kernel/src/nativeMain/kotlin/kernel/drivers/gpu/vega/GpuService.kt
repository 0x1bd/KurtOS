package kernel.drivers.gpu.vega

import kernel.KLog
import kernel.drivers.gpu.GpuPool

import hal.BootInfo
import hal.Clock
import hal.Cpu
import hal.RawMemory
import kernel.drivers.PciDevice
import kernel.graphics.DisplayContext
import kernel.memory.MemType
import kernel.memory.Mmio

object GpuService {
    var device: PciDevice? = null
        private set

    var chipName: String = ""
        private set

    var bars: VegaBars? = null
        private set

    var regs: VegaRegs? = null
        private set

    var smu: VegaSmu? = null
        private set

    var smuReady: Boolean = false
        private set

    var sdma: VegaSdma? = null
        private set

    var vramWindow: ULong = 0UL
        private set

    var vramWindowBytes: ULong = 0UL
        private set

    var carveoutBase: ULong = 0UL
        private set

    var carveoutBytes: ULong = 0UL
        private set

    var carveoutSysBase: ULong = 0UL
        private set

    var scanoutOffset: ULong = ULong.MAX_VALUE
        private set

    var doorbellWindow: ULong = 0UL
        private set

    var sdmaRing: VramAlloc? = null
        private set

    var fenceMemory: VramAlloc? = null
        private set

    var rptrWriteback: VramAlloc? = null
        private set

    var psp: VegaPsp? = null
        private set

    var compute: VegaCompute? = null
        private set

    var scanoutTarget: VramAlloc? = null
        private set

    fun initialize() {
        val found = VegaProbe.find()
        if (found == null) {
            KLog.info("gpu", "absent, software path")
            return
        }

        val (candidate, name) = found
        chipName = name

        KLog.info(
            "gpu",
            "found ${KLog.hex(candidate.vendorId.toUInt(), 4)}:${KLog.hex(candidate.deviceId.toUInt(), 4)} " +
                "$name rev ${KLog.hex(candidate.revision.toUInt(), 2)} " +
                "at ${candidate.bus}:${candidate.slot}.${candidate.function}"
        )

        val decoded = VegaProbe.bars(candidate)
        if (decoded == null) {
            KLog.step("gpu", "bar decode", false, "expected VRAM + register apertures")
            return
        }

        if (!GpuPool.initialize()) {
            KLog.step("gpu", "dma pool", false, "boot carve missing")
            return
        }

        device = candidate
        bars = decoded

        if (!bringUp(candidate, decoded)) {
            regs = null
            smu = null
            return
        }

        if (!memoryReady(decoded)) {
            regs = null
            smu = null
            return
        }

        startSdma()
    }

    fun describe(): List<String> {
        if (device == null) return listOf("gpu absent")
        if (regs == null) return listOf("$chipName found, bring-up failed (see bootlog)")

        val lines = mutableListOf<String>()
        val at = device?.let { "${it.bus}:${it.slot}.${it.function}" } ?: "?"
        lines.add("$chipName at $at")
        lines.add("carveout ${KLog.mib(carveoutBytes)} at ${KLog.hex(carveoutBase)}, bar window ${KLog.mib(vramWindowBytes)}")
        lines.add("sdma ${if (sdma != null) "ready" else "down"}, compute ${if (compute != null) "ready" else "down"}")
        lines.add(
            when {
                scanoutOffset == ULong.MAX_VALUE -> "scanout outside vram bar"
                scanoutTarget != null -> "scanout at vram +${KLog.hex(scanoutOffset)}, demo frame prerendered"
                else -> "scanout at vram +${KLog.hex(scanoutOffset)}"
            }
        )
        return lines
    }

    private fun startSdma() {
        val mapped = regs ?: return
        val ring = sdmaRing ?: return
        val fence = fenceMemory ?: return
        val rptr = rptrWriteback ?: return

        VegaVm.enableMmhub(mapped)

        val mailbox = smu
        if (mailbox != null) {
            val up = mailbox.send(VegaReg.SMU_MSG_POWER_UP_SDMA)
            KLog.step("gpu", "smu sdma-up", up == VegaReg.SMU_RESULT_OK, "resp=${up?.let { KLog.hex(it) } ?: "timeout"}")
        }

        val loader = VegaPsp(mapped)
        if (!loader.initialize()) {
            KLog.step("gpu", "psp", false, "ring/tmr setup failed")
            return
        }
        psp = loader

        val loaded = loader.loadFirmware("picasso_sdma.bin", VegaReg.GFX_FW_TYPE_SDMA0)
        if (!loaded) return

        val engine = VegaSdma(mapped, ring, fence, rptr, doorbellWindow)
        if (!engine.start()) return
        if (!engine.ringTest()) return
        if (!engine.copyTest(0x10000UL)) return

        sdma = engine

        startCompute(mapped, loader)
    }

    private fun startCompute(mapped: VegaRegs, loader: VegaPsp) {
        val gfx = VegaGfx(mapped, smu)
        if (!gfx.disableGfxOff()) return

        if (!loader.loadFirmware("picasso_rlc.bin", VegaReg.GFX_FW_TYPE_RLC_G)) return
        if (!loader.loadFirmware("picasso_mec.bin", VegaReg.GFX_FW_TYPE_CP_MEC)) return
        loader.loadMecJt(VegaReg.GFX_FW_TYPE_CP_MEC_ME1)

        VegaVm.enableGfxhub(mapped)

        if (!gfx.startRlc()) return
        gfx.disableClockAndPowerGating()
        if (!gfx.enableMec()) return

        val engine = VegaCompute(mapped, gfx, doorbellWindow)
        if (!engine.bringUp()) return
        if (!engine.ringTest()) return

        val fill = VegaShaderLoader.load("fill")
        if (fill != null && !engine.runKernel(fill)) return

        compute = engine

        val backend = VegaBackend(engine, mapped)
        if (backend.selfTest()) {
            kapi.gpu.Gpu.register(backend)
            KLog.step("gpu", "rdp backend", kapi.gpu.Gpu.available(), kapi.gpu.Gpu.name ?: "none")
        } else {
            KLog.step("gpu", "rdp backend", false, "selftest failed, rdp stays in software")
        }

        val gradient = VegaShaderLoader.load("gradient")
        val fb = BootInfo.framebuffer
        if (gradient != null && fb != null) prerenderScanout(engine, gradient, fb)
    }

    private fun prerenderScanout(engine: VegaCompute, shader: Shader, fb: hal.FramebufferInfo) {
        val pitchPx = fb.pitch / 4u
        val bytes = fb.pitch.toULong() * fb.height.toULong()
        val target = VegaVram.allocate(bytes) ?: return

        val pxByte = (50UL * pitchPx.toULong() + 100UL) * 4UL
        val ok = engine.drawFramebuffer(shader, target.gpuAddress, fb.width, fb.height, pitchPx)
        regs?.write(VegaReg.HDP_READ_CACHE_INVALIDATE, 1u)
        val px = target.readDword(pxByte)

        val good = ok && px != 0u
        if (good) scanoutTarget = target

        KLog.step("gpu", "scanout prerender", good, "${fb.width}x${fb.height} px ${KLog.hex(px)}")
    }

    fun drawScanoutDemo(): String {
        val blitter = sdma ?: return "sdma not up"
        val fb = BootInfo.framebuffer ?: return "no framebuffer"
        if (scanoutOffset == ULong.MAX_VALUE) return "scanout not in vram bar"
        val target = scanoutTarget ?: return "no prerendered frame at boot"

        val bytes = fb.pitch.toULong() * fb.height.toULong()

        DisplayContext.acquireGpu()
        if (!blitter.blit(target.gpuAddress, carveoutBase + scanoutOffset, bytes)) {
            DisplayContext.releaseGpu()
            return "sdma present failed"
        }
        return "gpu drew ${fb.width}x${fb.height} to scanout"
    }

    fun endScanoutDemo() {
        DisplayContext.releaseGpu()
    }

    private fun trySmu(mapped: VegaRegs): Boolean {
        val direct = mapped.read(VegaReg.MP1_C2PMSG_90)
        val indirect = mapped.readIndirect(VegaReg.MP1_C2PMSG_90)

        if (direct == 0xFFFFFFFFu && indirect == 0xFFFFFFFFu) {
            KLog.step("gpu", "smu", false, "mp1 dark direct+indirect, gfxoff left as firmware set it")
            return false
        }

        val mailbox = VegaSmu(mapped, indirect == 0xFFFFFFFFu)
        smu = mailbox

        val test = mailbox.send(VegaReg.SMU_MSG_TEST, 1u)
        if (test != VegaReg.SMU_RESULT_OK) {
            KLog.step("gpu", "smu", false, "test resp=${test?.let { KLog.hex(it) } ?: "timeout"}")
            smu = null
            return false
        }

        val version = mailbox.send(VegaReg.SMU_MSG_GET_SMU_VERSION)
        val gfxOff = mailbox.send(VegaReg.SMU_MSG_DISABLE_GFXOFF)
        settle(GFXOFF_SETTLE_MS)

        KLog.step(
            "gpu",
            "smu",
            gfxOff == VegaReg.SMU_RESULT_OK,
            "v${if (version == VegaReg.SMU_RESULT_OK) KLog.hex(mailbox.argument()) else "?"} gfxoff off",
        )
        return gfxOff == VegaReg.SMU_RESULT_OK
    }

    private fun settle(millis: ULong) {
        val deadline = Clock.uptimeMillis() + millis
        while (Clock.uptimeMillis() < deadline) {
        }
    }

    private fun memoryReady(decoded: VegaBars): Boolean {
        if (!VegaVram.initialize()) {
            KLog.step("gpu", "vram rgn", false)
            return false
        }

        if (decoded.doorbellBase != 0UL && decoded.doorbellSize != 0UL) {
            doorbellWindow = Mmio.map(decoded.doorbellBase, decoded.doorbellSize)
            if (doorbellWindow == 0UL) KLog.step("gpu", "doorbell", false)
        } else {
            KLog.info("gpu", "doorbell aperture missing, mmio wptr only")
        }

        val ring = VegaVram.allocate(RING_BYTES, RING_BYTES)
        val fence = VegaVram.allocate(0x1000UL)
        val rptr = VegaVram.allocate(0x1000UL)

        if (ring == null || fence == null || rptr == null) {
            KLog.step("gpu", "ring alloc", false, "carveout region exhausted")
            return false
        }

        ring.writeDword(0UL, 0x12345678u)
        ring.writeDword(RING_BYTES - 4UL, 0x9ABCDEF0u)
        fence.writeDword(0UL, 0u)
        Cpu.storeFence()

        val ok = ring.readDword(0UL) == 0x12345678u &&
            ring.readDword(RING_BYTES - 4UL) == 0x9ABCDEF0u &&
            fence.readDword(0UL) == 0u

        if (!ok) {
            KLog.step("gpu", "ring rw", false, "ring ${KLog.hex(ring.gpuAddress)}")
            return false
        }

        ring.zero()
        sdmaRing = ring
        fenceMemory = fence
        rptrWriteback = rptr
        return true
    }

    private fun bringUp(candidate: PciDevice, decoded: VegaBars): Boolean {
        candidate.enableBusMaster()

        val regBytes = if (decoded.registerSize < VegaReg.REGISTER_APERTURE_BYTES) decoded.registerSize else VegaReg.REGISTER_APERTURE_BYTES
        val regBase = Mmio.map(decoded.registerBase, regBytes)
        if (regBase == 0UL) {
            KLog.step("gpu", "regs", false)
            return false
        }

        val mapped = VegaRegs(regBase)
        regs = mapped

        mapped.write(VegaReg.REMAP_HDP_MEM_FLUSH_CNTL, VegaReg.HDP_FLUSH_BAR_OFFSET)

        smuReady = trySmu(mapped)

        return discoverCarveout(mapped, decoded)
    }

    private fun discoverCarveout(mapped: VegaRegs, decoded: VegaBars): Boolean {
        val fbBase = (mapped.read(VegaReg.MC_VM_FB_LOCATION_BASE) and 0xFFFFFFu).toULong() shl 24
        val fbTop = (mapped.read(VegaReg.MC_VM_FB_LOCATION_TOP) and 0xFFFFFFu).toULong() shl 24
        val fbOffset = (mapped.read(VegaReg.MC_VM_FB_OFFSET) and 0xFFFFFFu).toULong() shl 24

        if (fbTop <= fbBase) {
            KLog.step("gpu", "carveout", false, "fb base=${KLog.hex(fbBase)} top=${KLog.hex(fbTop)}")
            return false
        }

        carveoutBase = fbBase
        carveoutBytes = fbTop + 0x1000000UL - fbBase
        carveoutSysBase = fbOffset

        KLog.info("gpu", "carveout ${KLog.hex(fbBase)}..${KLog.hex(fbTop)} (${KLog.mib(carveoutBytes)}) sysoffset ${KLog.hex(fbOffset)}")

        val windowBytes = if (decoded.vramSize < MAX_VRAM_WINDOW) decoded.vramSize else MAX_VRAM_WINDOW
        val window = Mmio.map(decoded.vramBase, windowBytes, MemType.WriteCombining)
        if (window == 0UL) {
            KLog.step("gpu", "vram map", false)
            return false
        }

        vramWindow = window
        vramWindowBytes = windowBytes

        val probeAt = window + windowBytes - 0x100000UL
        val saved0 = RawMemory.read64(probeAt)
        val saved1 = RawMemory.read64(probeAt + 8UL)

        RawMemory.write64(probeAt, 0x4B75727452697073UL)
        RawMemory.write64(probeAt + 8UL, 0x1BD1BD1BD1BD1BDUL)
        Cpu.storeFence()

        val readBack0 = RawMemory.read64(probeAt)
        val readBack1 = RawMemory.read64(probeAt + 8UL)

        RawMemory.write64(probeAt, saved0)
        RawMemory.write64(probeAt + 8UL, saved1)
        Cpu.storeFence()

        val ok = readBack0 == 0x4B75727452697073UL && readBack1 == 0x1BD1BD1BD1BD1BDUL
        KLog.step("gpu", "vram rw", ok, "window ${KLog.hex(decoded.vramBase)} +${KLog.mib(windowBytes)}")

        val fb = BootInfo.framebuffer
        if (fb != null) {
            val scanoutPhys = BootInfo.toPhysical(fb.address)
            if (scanoutPhys >= decoded.vramBase && scanoutPhys < decoded.vramBase + decoded.vramSize) {
                scanoutOffset = scanoutPhys - decoded.vramBase
            }
            KLog.info(
                "gpu",
                "scanout ${KLog.hex(scanoutPhys)} ${fb.width}x${fb.height} " +
                    if (scanoutOffset == ULong.MAX_VALUE) "outside vram bar" else "at vram +${KLog.hex(scanoutOffset)}"
            )
        }

        return ok
    }

    private const val MAX_VRAM_WINDOW: ULong = 0x10000000UL
    private const val GFXOFF_SETTLE_MS: ULong = 10UL
    private const val RING_BYTES: ULong = 0x1000UL
}
