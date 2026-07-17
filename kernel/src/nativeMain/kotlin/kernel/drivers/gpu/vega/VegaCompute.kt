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

    fun runKernel(shader: Shader): Boolean {
        val fenceMem = fence ?: return false
        val isaBuf = VegaVram.allocate(0x1000UL) ?: return false
        val kernarg = VegaVram.allocate(0x1000UL) ?: return false
        val output = VegaVram.allocate(0x1000UL) ?: return false

        for (i in 0 until shader.isa.size / 4) {
            val at = i * 4
            val word = (shader.isa[at].toUInt() and 0xFFu) or
                ((shader.isa[at + 1].toUInt() and 0xFFu) shl 8) or
                ((shader.isa[at + 2].toUInt() and 0xFFu) shl 16) or
                ((shader.isa[at + 3].toUInt() and 0xFFu) shl 24)
            isaBuf.writeDword(at.toULong(), word)
        }
        output.writeDword(0UL, 0u)
        kernarg.writeDword(0UL, (output.gpuAddress and 0xFFFFFFFFUL).toUInt())
        kernarg.writeDword(4UL, (output.gpuAddress shr 32).toUInt())
        Cpu.storeFence()

        seq++
        fenceMem.writeDword(0UL, 0u)
        Cpu.storeFence()

        val pgm = isaBuf.gpuAddress shr 8
        setShReg(VegaReg.COMPUTE_PGM_LO, (pgm and 0xFFFFFFFFUL).toUInt(), (pgm shr 32).toUInt())
        setShReg(VegaReg.COMPUTE_PGM_RSRC1, shader.rsrc1, shader.rsrc2)
        setShReg3(VegaReg.COMPUTE_NUM_THREAD_X, 1u, 1u, 1u)
        setShReg1(VegaReg.COMPUTE_RESOURCE_LIMITS, 0u)
        setShReg(VegaReg.COMPUTE_STATIC_THREAD_MGMT_SE0, 0xFFFFFFFFu, 0xFFFFFFFFu)
        setShReg(VegaReg.COMPUTE_STATIC_THREAD_MGMT_SE2, 0xFFFFFFFFu, 0xFFFFFFFFu)

        setShReg(VegaReg.COMPUTE_USER_DATA_0, 0u, 0u)
        setShReg(VegaReg.COMPUTE_USER_DATA_0 + 2u, 0u, 0u)

        if (shader.kernargEnabled) {
            val idx = shader.kernargSgprIndex
            setShReg(
                VegaReg.COMPUTE_USER_DATA_0 + idx.toUInt(),
                (kernarg.gpuAddress and 0xFFFFFFFFUL).toUInt(),
                (kernarg.gpuAddress shr 32).toUInt(),
            )
        }

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

        regs.write(VegaReg.HDP_READ_CACHE_INVALIDATE, 1u)
        val result = output.readDword(0UL)
        val ok = result == 0xCA11AB1Eu
        GpuLog.step("mec kernel", ok, "${shader.name} out ${GpuLog.hex(result)} fence ${GpuLog.hex(fenceMem.readDword(0UL))}")
        return ok
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
