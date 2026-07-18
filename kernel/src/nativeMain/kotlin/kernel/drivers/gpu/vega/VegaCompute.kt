package kernel.drivers.gpu.vega

import hal.Cpu
import hal.RawMemory
import kernel.drivers.gpu.GpuLog

class VegaCompute(
    private val regs: VegaRegs,
    private val gfx: VegaGfx,
    private val doorbellBase: ULong,
) {
    private var wptr = 0
    private var seq = 0u

    private var ring: VramAlloc? = null
    private var fence: VramAlloc? = null
    private var shader: VramAlloc? = null
    private var mqdBuf: VramAlloc? = null
    private var eopBuf: VramAlloc? = null
    private var rptrBuf: VramAlloc? = null

    private val doorbellByte: ULong = (VegaReg.MEC_RING0_DOORBELL_INDEX shl 2).toULong()

    fun bringUp(): Boolean {
        val mqd = VegaVram.allocate(0x1000UL) ?: return false
        val eop = VegaVram.allocate(0x1000UL) ?: return false
        val pq = VegaVram.allocate(RING_BYTES) ?: return false
        val rptr = VegaVram.allocate(0x1000UL) ?: return false
        val fenceMem = VegaVram.allocate(0x1000UL) ?: return false
        val shaderMem = VegaVram.allocate(0x1000UL) ?: return false

        ring = pq
        fence = fenceMem
        shader = shaderMem
        mqdBuf = mqd
        eopBuf = eop
        rptrBuf = rptr

        shaderMem.writeDword(0UL, VegaReg.S_ENDPGM)
        Cpu.storeFence()

        regs.write(VegaReg.RCC_DOORBELL_APER_EN, regs.read(VegaReg.RCC_DOORBELL_APER_EN) or 0x1u)

        gfx.grbmSelect(1, 0, 0)
        programHqd(mqd, eop, pq, rptr)
        gfx.grbmDeselect()

        gfx.grbmSelect(1, 0, 0)
        val active = regs.read(VegaReg.CP_HQD_ACTIVE)
        val pqBase = regs.read(VegaReg.CP_HQD_PQ_BASE)
        gfx.grbmDeselect()

        GpuLog.step("mec hqd", active and 0x1u != 0u, "active ${GpuLog.hex(active)} pqbase ${GpuLog.hex(pqBase)}")
        return active and 0x1u != 0u
    }

    fun rearm(): Boolean {
        val mqd = mqdBuf ?: return false
        val eop = eopBuf ?: return false
        val pq = ring ?: return false
        val rptr = rptrBuf ?: return false

        gfx.disableGfxOff()
        gfx.disableClockAndPowerGating()
        VegaVm.enableGfxhub(regs)

        regs.write(VegaReg.RCC_DOORBELL_APER_EN, regs.read(VegaReg.RCC_DOORBELL_APER_EN) or 0x1u)

        gfx.grbmSelect(1, 0, 0)
        programHqd(mqd, eop, pq, rptr)
        gfx.grbmDeselect()

        wptr = 0

        gfx.grbmSelect(1, 0, 0)
        val active = regs.read(VegaReg.CP_HQD_ACTIVE)
        gfx.grbmDeselect()

        GpuLog.step("mec rearm", active and 0x1u != 0u, "active ${GpuLog.hex(active)}")
        return active and 0x1u != 0u
    }

    private fun programHqd(mqd: VramAlloc, eop: VramAlloc, pq: VramAlloc, rptr: VramAlloc) {
        val eopControl = 9u
        val doorbellControl = (VegaReg.MEC_RING0_DOORBELL_INDEX shl 2) or 0x40000000u
        val pqControl = 9u or (9u shl 8) or 0x40000000u or 0x80000000u
        val persistentState = 0x53u shl 8

        regs.write(VegaReg.CP_PQ_WPTR_POLL_CNTL, regs.read(VegaReg.CP_PQ_WPTR_POLL_CNTL) and 0x1u.inv())

        regs.write(VegaReg.CP_HQD_EOP_BASE_ADDR, (eop.gpuAddress shr 8).toUInt())
        regs.write(VegaReg.CP_HQD_EOP_BASE_ADDR_HI, (eop.gpuAddress shr 40).toUInt())
        regs.write(VegaReg.CP_HQD_EOP_CONTROL, eopControl)

        regs.write(VegaReg.CP_HQD_PQ_DOORBELL_CONTROL, doorbellControl)

        regs.write(VegaReg.CP_HQD_DEQUEUE_REQUEST, 0u)
        regs.write(VegaReg.CP_HQD_PQ_RPTR, 0u)
        regs.write(VegaReg.CP_HQD_PQ_WPTR_LO, 0u)
        regs.write(VegaReg.CP_HQD_PQ_WPTR_HI, 0u)

        regs.write(VegaReg.CP_MQD_BASE_ADDR, (mqd.gpuAddress and 0xFFFFFFFCUL).toUInt())
        regs.write(VegaReg.CP_MQD_BASE_ADDR_HI, (mqd.gpuAddress shr 32).toUInt())
        regs.write(VegaReg.CP_MQD_CONTROL, 0u)

        regs.write(VegaReg.CP_HQD_PQ_BASE, (pq.gpuAddress shr 8).toUInt())
        regs.write(VegaReg.CP_HQD_PQ_BASE_HI, (pq.gpuAddress shr 40).toUInt())
        regs.write(VegaReg.CP_HQD_PQ_CONTROL, pqControl)

        regs.write(VegaReg.CP_HQD_PQ_RPTR_REPORT_ADDR, (rptr.gpuAddress and 0xFFFFFFFCUL).toUInt())
        regs.write(VegaReg.CP_HQD_PQ_RPTR_REPORT_ADDR_HI, (rptr.gpuAddress shr 32).toUInt() and 0xFFFFu)

        val pollAddr = rptr.gpuAddress + 0x100UL
        regs.write(VegaReg.CP_HQD_PQ_WPTR_POLL_ADDR, (pollAddr and 0xFFFFFFFCUL).toUInt())
        regs.write(VegaReg.CP_HQD_PQ_WPTR_POLL_ADDR_HI, (pollAddr shr 32).toUInt())

        regs.write(VegaReg.CP_MEC_DOORBELL_RANGE_LOWER, 0x10u)
        regs.write(VegaReg.CP_MEC_DOORBELL_RANGE_UPPER, 0x20u)

        regs.write(VegaReg.CP_HQD_PQ_DOORBELL_CONTROL, doorbellControl)
        regs.write(VegaReg.CP_HQD_PQ_WPTR_LO, 0u)
        regs.write(VegaReg.CP_HQD_PQ_WPTR_HI, 0u)
        regs.write(VegaReg.CP_HQD_VMID, 0u)
        regs.write(VegaReg.CP_HQD_PERSISTENT_STATE, persistentState)
        regs.write(VegaReg.CP_HQD_ACTIVE, 1u)
    }

    fun ringTest(): Boolean {
        val target = fence ?: return false
        target.writeDword(0UL, 0xCAFEu)
        Cpu.storeFence()

        emit(VegaReg.packet3(VegaReg.PACKET3_WRITE_DATA, 3))
        emit(0x00100500u)
        emit((target.gpuAddress and 0xFFFFFFFFUL).toUInt())
        emit((target.gpuAddress shr 32).toUInt())
        emit(0xD00Du)
        ringDoorbell()

        var spins = FENCE_SPINS
        while (spins > 0) {
            regs.write(VegaReg.HDP_READ_CACHE_INVALIDATE, 1u)
            if (target.readDword(0UL) == 0xD00Du) break
            spins--
        }

        gfx.grbmSelect(1, 0, 0)
        val rptr = regs.read(VegaReg.CP_HQD_PQ_RPTR)
        gfx.grbmDeselect()

        val ok = target.readDword(0UL) == 0xD00Du
        GpuLog.step("mec ringtest", ok, "val ${GpuLog.hex(target.readDword(0UL))} rptr ${GpuLog.hex(rptr)}")
        return ok
    }

    private fun ringDoorbell() {
        Cpu.storeFence()
        regs.write(VegaReg.HDP_FLUSH_REG, 0u)
        RawMemory.write64(doorbellBase + doorbellByte, wptr.toULong())
        Cpu.storeFence()
    }

    fun dispatch(): Boolean {
        val pq = ring ?: return false
        val fenceMem = fence ?: return false
        val shaderMem = shader ?: return false

        seq++
        fenceMem.writeDword(0UL, 0u)
        Cpu.storeFence()

        val pgm = shaderMem.gpuAddress shr 8

        emitAcquireMem()
        setShReg(VegaReg.COMPUTE_PGM_LO, (pgm and 0xFFFFFFFFUL).toUInt(), (pgm shr 32).toUInt())
        setShReg(VegaReg.COMPUTE_PGM_RSRC1, VegaReg.COMPUTE_RSRC1_ENDPGM, 0u)
        setShReg3(VegaReg.COMPUTE_NUM_THREAD_X, 1u, 1u, 1u)
        setShReg1(VegaReg.COMPUTE_RESOURCE_LIMITS, 0u)
        setShReg(VegaReg.COMPUTE_STATIC_THREAD_MGMT_SE0, 0xFFFFFFFFu, 0xFFFFFFFFu)
        setShReg(VegaReg.COMPUTE_STATIC_THREAD_MGMT_SE2, 0xFFFFFFFFu, 0xFFFFFFFFu)

        emit(VegaReg.packet3(VegaReg.PACKET3_DISPATCH_DIRECT, 3))
        emit(1u)
        emit(1u)
        emit(1u)
        emit(VegaReg.COMPUTE_SHADER_EN)

        emit(VegaReg.packet3(VegaReg.PACKET3_RELEASE_MEM, 6))
        emit(RELEASE_EVENT)
        emit(RELEASE_DATA_SEL)
        emit((fenceMem.gpuAddress and 0xFFFFFFFFUL).toUInt())
        emit((fenceMem.gpuAddress shr 32).toUInt())
        emit(seq)
        emit(0u)
        emit(0u)

        ringDoorbell()

        var spins = FENCE_SPINS
        while (spins > 0) {
            regs.write(VegaReg.HDP_READ_CACHE_INVALIDATE, 1u)
            if (fenceMem.readDword(0UL) == seq) break
            spins--
        }

        gfx.grbmSelect(1, 0, 0)
        val rptr = regs.read(VegaReg.CP_HQD_PQ_RPTR)
        val active = regs.read(VegaReg.CP_HQD_ACTIVE)
        gfx.grbmDeselect()

        regs.write(VegaReg.HDP_READ_CACHE_INVALIDATE, 1u)
        val ok = fenceMem.readDword(0UL) == seq
        GpuLog.step(
            "mec dispatch", ok,
            "fence ${GpuLog.hex(fenceMem.readDword(0UL))} rptr ${GpuLog.hex(rptr)} active ${GpuLog.hex(active)} cp ${GpuLog.hex(regs.read(VegaReg.CP_STAT))}",
        )
        return ok
    }

    private val isaCache = mutableMapOf<String, VramAlloc>()
    private var kernargBuf: VramAlloc? = null
    private var testOutput: VramAlloc? = null

    private fun isaFor(shader: Shader): VramAlloc? {
        isaCache[shader.name]?.let { return it }
        val buf = VegaVram.allocate(0x1000UL) ?: return null
        for (i in 0 until shader.isa.size / 4) {
            val at = i * 4
            val word = (shader.isa[at].toUInt() and 0xFFu) or
                ((shader.isa[at + 1].toUInt() and 0xFFu) shl 8) or
                ((shader.isa[at + 2].toUInt() and 0xFFu) shl 16) or
                ((shader.isa[at + 3].toUInt() and 0xFFu) shl 24)
            buf.writeDword(at.toULong(), word)
        }
        Cpu.storeFence()
        isaCache[shader.name] = buf
        return buf
    }

    private fun dispatchKernel(shader: Shader, kernarg: VramAlloc, gridX: UInt, gridY: UInt, tx: UInt, ty: UInt): Boolean {
        val fenceMem = fence ?: return false
        val isaBuf = isaFor(shader) ?: return false

        VegaVm.invalidateGfxhubTlb(regs)

        seq++
        fenceMem.writeDword(0UL, 0u)
        Cpu.storeFence()

        val pgm = isaBuf.gpuAddress shr 8
        emitAcquireMem()
        setShReg(VegaReg.COMPUTE_PGM_LO, (pgm and 0xFFFFFFFFUL).toUInt(), (pgm shr 32).toUInt())
        setShReg(VegaReg.COMPUTE_PGM_RSRC1, shader.rsrc1, shader.rsrc2)
        setShReg3(VegaReg.COMPUTE_NUM_THREAD_X, tx, ty, 1u)
        setShReg1(VegaReg.COMPUTE_RESOURCE_LIMITS, 0u)
        setShReg(VegaReg.COMPUTE_STATIC_THREAD_MGMT_SE0, 0xFFFFFFFFu, 0xFFFFFFFFu)
        setShReg(VegaReg.COMPUTE_STATIC_THREAD_MGMT_SE2, 0xFFFFFFFFu, 0xFFFFFFFFu)

        setShReg(VegaReg.COMPUTE_USER_DATA_0, 0u, 0u)
        setShReg(VegaReg.COMPUTE_USER_DATA_0 + 2u, 0u, 0u)

        if (shader.kernargEnabled) {
            setShReg(
                VegaReg.COMPUTE_USER_DATA_0 + shader.kernargSgprIndex.toUInt(),
                (kernarg.gpuAddress and 0xFFFFFFFFUL).toUInt(),
                (kernarg.gpuAddress shr 32).toUInt(),
            )
        }

        emit(VegaReg.packet3(VegaReg.PACKET3_DISPATCH_DIRECT, 3))
        emit(gridX)
        emit(gridY)
        emit(1u)
        emit(VegaReg.COMPUTE_SHADER_EN)

        emit(VegaReg.packet3(VegaReg.PACKET3_EVENT_WRITE, 0))
        emit(VegaReg.EVENT_CS_PARTIAL_FLUSH)

        emit(VegaReg.packet3(VegaReg.PACKET3_RELEASE_MEM, 6))
        emit(RELEASE_EVENT)
        emit(RELEASE_DATA_SEL)
        emit((fenceMem.gpuAddress and 0xFFFFFFFFUL).toUInt())
        emit((fenceMem.gpuAddress shr 32).toUInt())
        emit(seq)
        emit(0u)
        emit(0u)

        ringDoorbell()

        var spins = FENCE_SPINS
        while (spins > 0) {
            regs.write(VegaReg.HDP_READ_CACHE_INVALIDATE, 1u)
            if (fenceMem.readDword(0UL) == seq) break
            spins--
        }
        return fenceMem.readDword(0UL) == seq
    }

    fun runKernel(shader: Shader): Boolean {
        if (kernargBuf == null) kernargBuf = VegaVram.allocate(0x1000UL)
        if (testOutput == null) testOutput = VegaVram.allocate(0x1000UL)
        val kernarg = kernargBuf ?: return false
        val output = testOutput ?: return false

        output.writeDword(0UL, 0u)
        kernarg.writeDword(0UL, (output.gpuAddress and 0xFFFFFFFFUL).toUInt())
        kernarg.writeDword(4UL, (output.gpuAddress shr 32).toUInt())
        Cpu.storeFence()

        val fenceOk = dispatchKernel(shader, kernarg, 1u, 1u, 1u, 1u)
        regs.write(VegaReg.HDP_READ_CACHE_INVALIDATE, 1u)
        val result = output.readDword(0UL)
        val ok = result == 0xCA11AB1Eu
        GpuLog.step("mec kernel", ok, "${shader.name} out ${GpuLog.hex(result)} fence ${if (fenceOk) "ok" else "timeout"}")
        return ok
    }

    fun renderTest(shader: Shader): Boolean {
        val scratch = VegaVram.allocate(0x40000UL) ?: return false
        scratch.zero()
        Cpu.storeFence()

        val ok = drawFramebuffer(shader, scratch.gpuAddress, 256u, 256u, 256u)
        regs.write(VegaReg.HDP_READ_CACHE_INVALIDATE, 1u)

        val x = 100u
        val y = 50u
        val px = scratch.readDword(((y * 256u + x) * 4u).toULong())
        val r = x * 255u / 256u
        val g = y * 255u / 256u
        val expected = (r shl 16) or (g shl 8) or 0x60u
        val match = px == expected

        val px0 = scratch.readDword(0UL)
        val px1 = scratch.readDword((256u * 4u).toULong())
        GpuLog.step("mec render", match, "px(100,50)=${GpuLog.hex(px)} exp=${GpuLog.hex(expected)} row0=${GpuLog.hex(px0)} row1=${GpuLog.hex(px1)}")
        return match && ok
    }

    private fun renderProbe(shader: Shader): Triple<Boolean, UInt, UInt> {
        val scratch = VegaVram.allocate(0x40000UL) ?: return Triple(false, 0u, 0u)
        scratch.zero()
        Cpu.storeFence()
        val ok = drawFramebuffer(shader, scratch.gpuAddress, 256u, 256u, 256u)
        regs.write(VegaReg.HDP_READ_CACHE_INVALIDATE, 1u)
        val offset = ((50u * 256u + 100u) * 4u).toULong()
        val px = scratch.readDword(offset)
        var spins = 4_000_000
        while (spins > 0) spins--
        regs.write(VegaReg.HDP_READ_CACHE_INVALIDATE, 1u)
        return Triple(ok, px, scratch.readDword(offset))
    }

    private fun fillProbe(shader: Shader): UInt {
        if (kernargBuf == null) kernargBuf = VegaVram.allocate(0x1000UL)
        val kernarg = kernargBuf ?: return 0xDEAD0000u
        val output = VegaVram.allocate(0x1000UL) ?: return 0xDEAD0001u
        output.writeDword(0UL, 0u)
        kernarg.writeDword(0UL, (output.gpuAddress and 0xFFFFFFFFUL).toUInt())
        kernarg.writeDword(4UL, (output.gpuAddress shr 32).toUInt())
        Cpu.storeFence()
        dispatchKernel(shader, kernarg, 1u, 1u, 1u, 1u)
        regs.write(VegaReg.HDP_READ_CACHE_INVALIDATE, 1u)
        return output.readDword(0UL)
    }

    fun diagnose(shader: Shader, fill: Shader?): List<String> {
        val out = mutableListOf<String>()

        val pwr0 = regs.read(VegaReg.PWR_MISC_CNTL_STATUS)
        val gfxOn0 = pwr0 and VegaReg.PWR_GFXOFF_STATUS_MASK == VegaReg.PWR_GFXOFF_STATUS_ON
        out.add("pwr ${GpuLog.hex(pwr0)} gfx ${if (gfxOn0) "ON" else "GATED"}")
        out.add("gating(idle) ${gfx.gatingState()}")
        out.add("vm(idle) ${VegaVm.gfxhubApertureState(regs)}")
        out.add(
            "vmfault gfx ${GpuLog.hex(regs.read(VegaReg.GFX_VM_L2_PROTECTION_FAULT_STATUS))} " +
                "mm ${GpuLog.hex(regs.read(VegaReg.VM_L2_PROTECTION_FAULT_STATUS))}",
        )
        out.add("tlbflush acked=${VegaVm.invalidateGfxhubTlb(regs)}")

        val (okA, pxA, pxA2) = renderProbe(shader)
        val pwrA = regs.read(VegaReg.PWR_MISC_CNTL_STATUS)
        out.add("render(no rearm) ${if (okA) "OK" else "FAIL"} px=${GpuLog.hex(pxA)} recheck=${GpuLog.hex(pxA2)} pwr=${GpuLog.hex(pwrA)}")

        val rearmed = rearm()
        out.add("rearm ${if (rearmed) "active" else "failed"}")
        out.add("gating(rearm) ${gfx.gatingState()}")
        out.add("vm(rearm) ${VegaVm.gfxhubApertureState(regs)}")

        val (okB, pxB, pxB2) = renderProbe(shader)
        val pwrB = regs.read(VegaReg.PWR_MISC_CNTL_STATUS)
        out.add("render(rearm) ${if (okB) "OK" else "FAIL"} px=${GpuLog.hex(pxB)} recheck=${GpuLog.hex(pxB2)} pwr=${GpuLog.hex(pwrB)}")

        if (fill != null) {
            val fillOut = fillProbe(fill)
            out.add("fill(rearm) out=${GpuLog.hex(fillOut)} exp=0xca11ab1e ${if (fillOut == 0xCA11AB1Eu) "OK" else "FAIL"}")
        }

        out.add(
            "vmfault(post) gfx ${GpuLog.hex(regs.read(VegaReg.GFX_VM_L2_PROTECTION_FAULT_STATUS))} " +
                "mm ${GpuLog.hex(regs.read(VegaReg.VM_L2_PROTECTION_FAULT_STATUS))}",
        )

        val gfxOnB = pwrB and VegaReg.PWR_GFXOFF_STATUS_MASK == VegaReg.PWR_GFXOFF_STATUS_ON
        if (gfxOnB) {
            out.add(
                "rlc ${GpuLog.hex(regs.read(VegaReg.RLC_CNTL))} rlcstat ${GpuLog.hex(regs.read(VegaReg.RLC_GPM_STAT))} " +
                    "mec ${GpuLog.hex(regs.read(VegaReg.CP_MEC_CNTL))} cp ${GpuLog.hex(regs.read(VegaReg.CP_STAT))}",
            )
            gfx.grbmSelect(1, 0, 0)
            val active = regs.read(VegaReg.CP_HQD_ACTIVE)
            val rptr = regs.read(VegaReg.CP_HQD_PQ_RPTR)
            gfx.grbmDeselect()
            out.add("hqd active ${GpuLog.hex(active)} rptr ${GpuLog.hex(rptr)}")
        } else {
            out.add("gfx gated -> skipping gfx-domain reads (would hang)")
        }
        return out
    }

    fun drawFramebuffer(shader: Shader, fbGpuAddr: ULong, width: UInt, height: UInt, pitchPx: UInt): Boolean {
        if (kernargBuf == null) kernargBuf = VegaVram.allocate(0x1000UL)
        val kernarg = kernargBuf ?: return false

        val total = width * height
        kernarg.writeDword(0UL, (fbGpuAddr and 0xFFFFFFFFUL).toUInt())
        kernarg.writeDword(4UL, (fbGpuAddr shr 32).toUInt())
        kernarg.writeDword(8UL, width)
        kernarg.writeDword(12UL, height)
        kernarg.writeDword(16UL, pitchPx)
        Cpu.storeFence()

        val groups = (total + 63u) / 64u
        val ok = dispatchKernel(shader, kernarg, groups, 1u, 64u, 1u)
        GpuLog.step("mec fbdraw", ok, "${shader.name} ${width}x${height} groups $groups")
        return ok
    }

    private fun emitAcquireMem() {
        emit(VegaReg.packet3(VegaReg.PACKET3_ACQUIRE_MEM, 5))
        emit(VegaReg.ACQUIRE_MEM_COHER_CNTL)
        emit(0xFFFFFFFFu)
        emit(0xFFFFFFu)
        emit(0u)
        emit(0u)
        emit(VegaReg.ACQUIRE_MEM_POLL_INTERVAL)
    }

    private fun emit(value: UInt) {
        pqWrite((wptr and (RING_DWORDS - 1)), value)
        wptr++
    }

    private fun pqWrite(index: Int, value: UInt) {
        ring?.writeDword(index.toULong() * 4UL, value)
    }

    private fun setShReg(reg: UInt, d0: UInt, d1: UInt) {
        emit(VegaReg.packet3(VegaReg.PACKET3_SET_SH_REG, 2))
        emit(reg - VegaReg.SET_SH_REG_START)
        emit(d0)
        emit(d1)
    }

    private fun setShReg1(reg: UInt, d0: UInt) {
        emit(VegaReg.packet3(VegaReg.PACKET3_SET_SH_REG, 1))
        emit(reg - VegaReg.SET_SH_REG_START)
        emit(d0)
    }

    private fun setShReg3(reg: UInt, d0: UInt, d1: UInt, d2: UInt) {
        emit(VegaReg.packet3(VegaReg.PACKET3_SET_SH_REG, 3))
        emit(reg - VegaReg.SET_SH_REG_START)
        emit(d0)
        emit(d1)
        emit(d2)
    }

    private companion object {
        const val RING_BYTES: ULong = 0x1000UL
        const val RING_DWORDS = 1024
        const val FENCE_SPINS = 40_000_002
        const val RELEASE_EVENT: UInt = 0x238514u
        const val RELEASE_DATA_SEL: UInt = 0x20000000u
    }
}
