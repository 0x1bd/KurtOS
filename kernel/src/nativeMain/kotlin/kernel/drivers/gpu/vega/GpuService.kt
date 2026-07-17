package kernel.drivers.gpu.vega

import kernel.drivers.gpu.GpuLog
import kernel.drivers.gpu.GpuPool

import hal.BootInfo
import hal.Clock
import hal.Cpu
import hal.PCIConfig
import hal.RawMemory
import kernel.drivers.PciDevice
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

    fun initialize() {
        val found = VegaProbe.find()
        if (found == null) {
            GpuLog.info("absent, software path")
            return
        }

        val (candidate, name) = found
        chipName = name

        GpuLog.info(
            "found ${GpuLog.hex(candidate.vendorId.toUInt(), 4)}:${GpuLog.hex(candidate.deviceId.toUInt(), 4)} " +
                "$name rev ${GpuLog.hex(candidate.revision.toUInt(), 2)} " +
                "at ${candidate.bus}:${candidate.slot}.${candidate.function}"
        )

        val decoded = VegaProbe.bars(candidate)
        if (decoded == null) {
            GpuLog.step("bar decode", false, "expected VRAM + register apertures")
            return
        }

        GpuLog.info(
            "bars vram ${GpuLog.hex(decoded.vramBase)}/${GpuLog.mib(decoded.vramSize)} " +
                "db ${GpuLog.hex(decoded.doorbellBase)}/${GpuLog.hex(decoded.doorbellSize)} " +
                "regs ${GpuLog.hex(decoded.registerBase)}/${GpuLog.hex(decoded.registerSize)}"
        )

        if (!GpuPool.initialize()) {
            GpuLog.step("dma pool", false, "boot carve missing")
            return
        }

        GpuLog.step("dma pool", true, GpuLog.mib(GpuPool.totalBytes))

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

        probeFirmware()
        startSdma()
    }

    var psp: VegaPsp? = null
        private set

    private fun startSdma() {
        val mapped = regs ?: return
        val ring = sdmaRing ?: return
        val fence = fenceMemory ?: return
        val rptr = rptrWriteback ?: return

        VegaVm.enableMmhub(mapped)

        val mailbox = smu
        if (mailbox != null) {
            val up = mailbox.send(VegaReg.SMU_MSG_POWER_UP_SDMA)
            GpuLog.step("smu sdma-up", up == VegaReg.SMU_RESULT_OK, "resp=${up?.let { GpuLog.hex(it) } ?: "timeout"}")
        }

        val loader = VegaPsp(mapped)
        if (!loader.initialize()) {
            GpuLog.step("psp", false, "ring/tmr setup failed")
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

    var compute: VegaCompute? = null
        private set

    private fun startCompute(mapped: VegaRegs, loader: VegaPsp) {
        val gfx = VegaGfx(mapped, smu)
        if (!gfx.disableGfxOff()) return

        if (!loader.loadFirmware("picasso_rlc.bin", VegaReg.GFX_FW_TYPE_RLC_G)) return
        if (!loader.loadFirmware("picasso_mec.bin", VegaReg.GFX_FW_TYPE_CP_MEC)) return
        loader.loadMecJt(VegaReg.GFX_FW_TYPE_CP_MEC_ME1)

        VegaVm.enableGfxhub(mapped)

        if (!gfx.startRlc()) return
        if (!gfx.enableMec()) return

        val engine = VegaCompute(mapped, gfx, doorbellWindow)
        if (!engine.bringUp()) return
        engine.ringTest()
        engine.dispatch()

        val shader = VegaShaderLoader.load("fill")
        if (shader != null) engine.runKernel(shader)

        compute = engine
    }

    private fun trySmu(mapped: VegaRegs): Boolean {
        val direct = mapped.read(VegaReg.MP1_C2PMSG_90)
        val indirect = mapped.readIndirect(VegaReg.MP1_C2PMSG_90)

        if (direct == 0xFFFFFFFFu && indirect == 0xFFFFFFFFu) {
            GpuLog.step("smu", false, "mp1 dark direct+indirect, gfxoff left as firmware set it")
            return false
        }

        val mailbox = VegaSmu(mapped, indirect == 0xFFFFFFFFu)
        smu = mailbox

        val test = mailbox.send(VegaReg.SMU_MSG_TEST, 1u)
        if (test != VegaReg.SMU_RESULT_OK) {
            GpuLog.step("smu", false, "test resp=${test?.let { GpuLog.hex(it) } ?: "timeout"}")
            smu = null
            return false
        }

        val version = mailbox.send(VegaReg.SMU_MSG_GET_SMU_VERSION)
        val gfxOff = mailbox.send(VegaReg.SMU_MSG_DISABLE_GFXOFF)
        settle(GFXOFF_SETTLE_MS)

        GpuLog.step(
            "smu",
            gfxOff == VegaReg.SMU_RESULT_OK,
            "v${if (version == VegaReg.SMU_RESULT_OK) GpuLog.hex(mailbox.argument()) else "?"} gfxoff off",
        )
        return gfxOff == VegaReg.SMU_RESULT_OK
    }

    fun probeGfx(): String {
        val mapped = regs ?: return "no register access"
        mapped.write(VegaReg.SCRATCH_REG0, 0xCAFEBABEu)
        val scratch = mapped.read(VegaReg.SCRATCH_REG0)
        return "scratch ${GpuLog.hex(scratch)} grbm ${GpuLog.hex(mapped.read(VegaReg.GRBM_STATUS))} " +
            "cp ${GpuLog.hex(mapped.read(VegaReg.CP_STAT))} rlc ${GpuLog.hex(mapped.read(VegaReg.RLC_GPM_STAT))} " +
            "gbcfg ${GpuLog.hex(mapped.read(VegaReg.GB_ADDR_CONFIG))}"
    }

    private fun probeSmn(mapped: VegaRegs) {
        val pcie = mapped.smnRead(0x11180070u)
        val fwFlags = mapped.smnRead(0x3010028u)
        val fwFlags2 = mapped.smnRead(0x3010024u)
        val mp1_c2p90 = mapped.smnRead(0x3B10000u + 0x29Au * 4u)

        GpuLog.step(
            "smn", pcie != 0xFFFFFFFFu,
            "pcie ${GpuLog.hex(pcie)} mp1fw ${GpuLog.hex(fwFlags)} fw2 ${GpuLog.hex(fwFlags2)} c2p90 ${GpuLog.hex(mp1_c2p90)}",
        )
    }

    private fun probePsp(mapped: VegaRegs) {
        val c64 = mapped.read(VegaReg.MP0_C2PMSG_64)
        val c67 = mapped.read(VegaReg.MP0_C2PMSG_67)
        val c81 = mapped.read(VegaReg.MP0_C2PMSG_81)
        val c35 = mapped.read(VegaReg.MP0_C2PMSG_35)
        val i64 = mapped.readIndirect(VegaReg.MP0_C2PMSG_64)

        val reachable = c64 != 0xFFFFFFFFu || i64 != 0xFFFFFFFFu
        GpuLog.step(
            "psp", reachable,
            "c64 ${GpuLog.hex(c64)} i64 ${GpuLog.hex(i64)} c67 ${GpuLog.hex(c67)} c81 ${GpuLog.hex(c81)} c35 ${GpuLog.hex(c35)}",
        )
    }

    private fun probeAperture(mapped: VegaRegs) {
        val command = device?.let { PCIConfig.read16(it.bus, it.slot, it.function, 0x04).toUInt() } ?: 0u
        GpuLog.info("pci command ${GpuLog.hex(command, 4)}")

        val builder = StringBuilder("decode")
        for (reg in APERTURE_PROBE) {
            builder.append(" +${GpuLog.hex(reg.toULong() shl 2)}=${GpuLog.hex(mapped.read(reg))}")
        }
        GpuLog.info(builder.toString())
    }

    private fun probeFirmware() {
        for (name in FIRMWARE) GpuFirmware.load(name)
    }

    private fun settle(millis: ULong) {
        val deadline = Clock.uptimeMillis() + millis
        while (Clock.uptimeMillis() < deadline) {
        }
    }

    private fun memoryReady(decoded: VegaBars): Boolean {
        if (!VegaVram.initialize()) {
            GpuLog.step("vram rgn", false)
            return false
        }

        if (decoded.doorbellBase != 0UL && decoded.doorbellSize != 0UL) {
            doorbellWindow = Mmio.map(decoded.doorbellBase, decoded.doorbellSize)
            GpuLog.step("doorbell", doorbellWindow != 0UL, "+${GpuLog.hex(decoded.doorbellSize)}")
        } else {
            GpuLog.info("doorbell aperture missing, mmio wptr only")
        }

        val ring = VegaVram.allocate(RING_BYTES, RING_BYTES)
        val fence = VegaVram.allocate(0x1000UL)
        val rptr = VegaVram.allocate(0x1000UL)

        if (ring == null || fence == null || rptr == null) {
            GpuLog.step("ring alloc", false, "carveout region exhausted")
            return false
        }

        ring.writeDword(0UL, 0x12345678u)
        ring.writeDword(RING_BYTES - 4UL, 0x9ABCDEF0u)
        fence.writeDword(0UL, 0u)
        Cpu.storeFence()

        val ok = ring.readDword(0UL) == 0x12345678u &&
            ring.readDword(RING_BYTES - 4UL) == 0x9ABCDEF0u &&
            fence.readDword(0UL) == 0u

        GpuLog.step("ring rw", ok, "ring ${GpuLog.hex(ring.gpuAddress)} fence ${GpuLog.hex(fence.gpuAddress)}")
        if (!ok) return false

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
            GpuLog.step("regs", false)
            return false
        }

        val mapped = VegaRegs(regBase)
        regs = mapped
        GpuLog.step("regs", true, "size ${decoded.registerSize / 1024UL} KiB, mapped ${regBytes / 1024UL} KiB")

        mapped.write(VegaReg.REMAP_HDP_MEM_FLUSH_CNTL, VegaReg.HDP_FLUSH_BAR_OFFSET)

        probeAperture(mapped)

        probeSmn(mapped)
        smuReady = trySmu(mapped)
        probePsp(mapped)

        return discoverCarveout(mapped, decoded)
    }

    private fun discoverCarveout(mapped: VegaRegs, decoded: VegaBars): Boolean {
        val fbBase = (mapped.read(VegaReg.MC_VM_FB_LOCATION_BASE) and 0xFFFFFFu).toULong() shl 24
        val fbTop = (mapped.read(VegaReg.MC_VM_FB_LOCATION_TOP) and 0xFFFFFFu).toULong() shl 24
        val fbOffset = (mapped.read(VegaReg.MC_VM_FB_OFFSET) and 0xFFFFFFu).toULong() shl 24

        if (fbTop <= fbBase) {
            GpuLog.step("carveout", false, "fb base=${GpuLog.hex(fbBase)} top=${GpuLog.hex(fbTop)}")
            return false
        }

        carveoutBase = fbBase
        carveoutBytes = fbTop + 0x1000000UL - fbBase

        GpuLog.info("carveout gpu ${GpuLog.hex(fbBase)}..${GpuLog.hex(fbTop)} (${GpuLog.mib(carveoutBytes)}) sysoffset ${GpuLog.hex(fbOffset)}")
        GpuLog.info(
            "sys aperture ${GpuLog.hex(mapped.read(VegaReg.MC_VM_SYSTEM_APERTURE_LOW_ADDR).toULong() shl 18)}.." +
                "${GpuLog.hex(mapped.read(VegaReg.MC_VM_SYSTEM_APERTURE_HIGH_ADDR).toULong() shl 18)} " +
                "agp ${GpuLog.hex(mapped.read(VegaReg.MC_VM_AGP_BOT).toULong() shl 24)}.." +
                GpuLog.hex(mapped.read(VegaReg.MC_VM_AGP_TOP).toULong() shl 24)
        )

        val windowBytes = if (decoded.vramSize < MAX_VRAM_WINDOW) decoded.vramSize else MAX_VRAM_WINDOW
        val window = Mmio.map(decoded.vramBase, windowBytes, MemType.WriteCombining)
        if (window == 0UL) {
            GpuLog.step("vram map", false)
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
        GpuLog.step("vram rw", ok, "window ${GpuLog.hex(decoded.vramBase)} +${GpuLog.mib(windowBytes)}")

        carveoutSysBase = fbOffset

        GpuLog.info(
            "vm ctx0 ${GpuLog.hex(mapped.read(VegaReg.VM_CONTEXT0_CNTL))} " +
                "l2 ${GpuLog.hex(mapped.read(VegaReg.VM_L2_CNTL))} " +
                "ptb ${GpuLog.hex(mapped.read(VegaReg.VM_CONTEXT0_PAGE_TABLE_BASE_ADDR_LO32))} " +
                "dflt ${GpuLog.hex(mapped.read(VegaReg.MC_VM_SYSTEM_APERTURE_DEFAULT_ADDR_LSB))}"
        )

        val fb = BootInfo.framebuffer
        if (fb != null) {
            val scanoutPhys = BootInfo.toPhysical(fb.address)
            if (scanoutPhys >= decoded.vramBase && scanoutPhys < decoded.vramBase + decoded.vramSize) {
                scanoutOffset = scanoutPhys - decoded.vramBase
            }
            GpuLog.info(
                "scanout ${GpuLog.hex(scanoutPhys)} ${fb.width}x${fb.height} " +
                    if (scanoutOffset == ULong.MAX_VALUE) "outside vram bar" else "at vram +${GpuLog.hex(scanoutOffset)}"
            )
        }

        return ok
    }

    private const val MAX_VRAM_WINDOW: ULong = 0x10000000UL
    private const val GFXOFF_SETTLE_MS: ULong = 10UL
    private const val RING_BYTES: ULong = 0x1000UL

    private val FIRMWARE = listOf("picasso_rlc.bin", "picasso_mec.bin")

    private val APERTURE_PROBE = listOf(
        0x0u,
        VegaReg.SDMA0_STATUS_REG,
        VegaReg.MP1_C2PMSG_90,
        VegaReg.MC_VM_FB_LOCATION_BASE,
    )
}
