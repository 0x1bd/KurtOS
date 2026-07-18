package kernel.drivers.gpu.vega

import kernel.KLog

import hal.Cpu
import hal.RawMemory

class VegaSdma(
    private val regs: VegaRegs,
    private val ring: VramAlloc,
    private val fence: VramAlloc,
    private val rptr: VramAlloc,
    private val doorbellBase: ULong,
) {
    private var wptr = 0
    private var seq = 0u

    private val doorbellByte: ULong = (VegaReg.SDMA0_DOORBELL_INDEX shl 2).toULong()

    fun start(): Boolean {
        regs.write(VegaReg.RCC_DOORBELL_APER_EN, regs.read(VegaReg.RCC_DOORBELL_APER_EN) or 0x1u)
        regs.write(VegaReg.BIF_SDMA0_DOORBELL_RANGE, (VegaReg.SDMA0_DOORBELL_INDEX shl 2) or (0x2u shl 16))

        halt()
        ctxSwitch()
        regs.write(VegaReg.SDMA0_SEM_WAIT_FAIL_TIMER_CNTL, 0u)
        resumeRing()

        regs.write(VegaReg.SDMA0_CNTL, regs.read(VegaReg.SDMA0_CNTL) or VegaReg.SDMA_UTC_L1_ENABLE)
        unhalt()

        val running = regs.read(VegaReg.SDMA0_STATUS_REG)
        KLog.step("gpu", "sdma ring", running and 0x1u != 0u, "status ${KLog.hex(running)}")
        return running and 0x1u != 0u
    }

    private fun unhalt() {
        regs.write(VegaReg.SDMA0_F32_CNTL, regs.read(VegaReg.SDMA0_F32_CNTL) and VegaReg.SDMA_F32_HALT.inv())
    }

    private fun halt() {
        regs.write(VegaReg.SDMA0_F32_CNTL, regs.read(VegaReg.SDMA0_F32_CNTL) or VegaReg.SDMA_F32_HALT)
    }

    private fun ctxSwitch() {
        regs.write(VegaReg.SDMA0_CNTL, regs.read(VegaReg.SDMA0_CNTL) or VegaReg.SDMA_AUTO_CTXSW)
        regs.write(VegaReg.SDMA0_UTCL1_TIMEOUT, 0x00800080u)
    }

    private fun resumeRing() {
        regs.write(VegaReg.SDMA0_GFX_RB_CNTL, 0u)
        regs.write(VegaReg.SDMA0_GFX_IB_CNTL, 0u)

        var cntl = (RING_ORDER shl VegaReg.SDMA_RB_SIZE_SHIFT).toUInt() and VegaReg.SDMA_RB_SIZE_MASK

        regs.write(VegaReg.SDMA0_GFX_RB_CNTL, cntl)
        regs.write(VegaReg.SDMA0_GFX_RB_RPTR, 0u)
        regs.write(VegaReg.SDMA0_GFX_RB_RPTR_HI, 0u)
        regs.write(VegaReg.SDMA0_GFX_RB_WPTR, 0u)
        regs.write(VegaReg.SDMA0_GFX_RB_WPTR_HI, 0u)

        regs.write(VegaReg.SDMA0_GFX_RB_RPTR_ADDR_HI, (rptr.gpuAddress shr 32).toUInt())
        regs.write(VegaReg.SDMA0_GFX_RB_RPTR_ADDR_LO, (rptr.gpuAddress and 0xFFFFFFFCUL).toUInt())
        cntl = cntl or VegaReg.SDMA_RPTR_WRITEBACK_ENABLE

        regs.write(VegaReg.SDMA0_GFX_RB_BASE, (ring.gpuAddress shr 8).toUInt())
        regs.write(VegaReg.SDMA0_GFX_RB_BASE_HI, (ring.gpuAddress shr 40).toUInt())

        rptr.writeDword(POLL_OFFSET, 0u)
        rptr.writeDword(POLL_OFFSET + 4UL, 0u)
        Cpu.storeFence()

        val poll = rptr.gpuAddress + POLL_OFFSET
        regs.write(VegaReg.SDMA0_GFX_RB_WPTR_POLL_ADDR_LO, (poll and 0xFFFFFFFCUL).toUInt())
        regs.write(VegaReg.SDMA0_GFX_RB_WPTR_POLL_ADDR_HI, (poll shr 32).toUInt())
        regs.write(
            VegaReg.SDMA0_GFX_RB_WPTR_POLL_CNTL,
            regs.read(VegaReg.SDMA0_GFX_RB_WPTR_POLL_CNTL) or VegaReg.SDMA_F32_POLL_ENABLE,
        )

        regs.write(VegaReg.SDMA0_GFX_MINOR_PTR_UPDATE, 1u)
        regs.write(VegaReg.SDMA0_GFX_DOORBELL_OFFSET, VegaReg.SDMA0_DOORBELL_INDEX shl 2)
        regs.write(VegaReg.SDMA0_GFX_DOORBELL, VegaReg.SDMA_DOORBELL_ENABLE)
        wptr = 0
        pushWptr()
        regs.write(VegaReg.SDMA0_GFX_MINOR_PTR_UPDATE, 0u)

        regs.write(VegaReg.SDMA0_GFX_RB_CNTL, cntl or VegaReg.SDMA_RB_ENABLE)
        regs.write(VegaReg.SDMA0_GFX_IB_CNTL, VegaReg.SDMA_IB_ENABLE)
    }

    private fun emit(value: UInt) {
        ring.writeDword((wptr and (RING_DWORDS - 1)).toULong() * 4UL, value)
        wptr++
    }

    private fun pushWptr() {
        val byteWptr = (wptr shl 2).toULong()
        rptr.writeDword(POLL_OFFSET, byteWptr.toUInt())
        rptr.writeDword(POLL_OFFSET + 4UL, 0u)
        Cpu.storeFence()
        regs.write(VegaReg.HDP_FLUSH_REG, 0u)
        regs.write(VegaReg.SDMA0_GFX_RB_WPTR, byteWptr.toUInt())
        regs.write(VegaReg.SDMA0_GFX_RB_WPTR_HI, 0u)
        if (doorbellBase != 0UL) {
            RawMemory.write64(doorbellBase + doorbellByte, byteWptr)
            Cpu.storeFence()
        }
    }

    private fun commit() {
        var pad = (ALIGN_MASK + 1 - (wptr and ALIGN_MASK)) % (ALIGN_MASK + 1)
        while (pad > 0) {
            emit(VegaReg.SDMA_OP_NOP)
            pad--
        }
        pushWptr()
    }

    fun ringTest(): Boolean {
        val target = VegaVram.allocate(0x1000UL) ?: return false
        target.writeDword(0UL, 0xCAFEDEADu)
        Cpu.storeFence()

        emit(VegaReg.SDMA_OP_WRITE)
        emit((target.gpuAddress and 0xFFFFFFFFUL).toUInt())
        emit((target.gpuAddress shr 32).toUInt())
        emit(0u)
        emit(0xDEADBEEFu)
        commit()

        var spins = FENCE_SPINS
        while (spins > 0) {
            regs.write(VegaReg.HDP_READ_CACHE_INVALIDATE, 1u)
            if (target.readDword(0UL) == 0xDEADBEEFu) break
            spins--
        }

        val ok = target.readDword(0UL) == 0xDEADBEEFu
        KLog.step("gpu", "sdma ringtest", ok, "val ${KLog.hex(target.readDword(0UL))}")
        return ok
    }

    fun blit(srcGpu: ULong, dstGpu: ULong, bytes: ULong): Boolean {
        var offset = 0UL
        while (offset < bytes) {
            val chunk = if (bytes - offset > MAX_COPY) MAX_COPY else bytes - offset
            if (!copyChunk(srcGpu + offset, dstGpu + offset, chunk)) return false
            offset += chunk
        }
        return true
    }

    private fun copyChunk(srcGpu: ULong, dstGpu: ULong, bytes: ULong): Boolean {
        seq++
        fence.writeDword(0UL, 0u)
        Cpu.storeFence()

        emit(VegaReg.SDMA_OP_COPY or (VegaReg.SDMA_SUBOP_COPY_LINEAR shl 8))
        emit((bytes - 1UL).toUInt())
        emit(0u)
        emit((srcGpu and 0xFFFFFFFFUL).toUInt())
        emit((srcGpu shr 32).toUInt())
        emit((dstGpu and 0xFFFFFFFFUL).toUInt())
        emit((dstGpu shr 32).toUInt())

        emit(VegaReg.SDMA_OP_FENCE)
        emit((fence.gpuAddress and 0xFFFFFFFFUL).toUInt())
        emit((fence.gpuAddress shr 32).toUInt())
        emit(seq)

        commit()

        var spins = FENCE_SPINS
        while (spins > 0 && fence.readDword(0UL) != seq) spins--
        regs.write(VegaReg.HDP_READ_CACHE_INVALIDATE, 1u)
        return fence.readDword(0UL) == seq
    }

    fun copyTest(bytes: ULong): Boolean {
        val src = VegaVram.allocate(bytes) ?: return false
        val dst = VegaVram.allocate(bytes) ?: return false

        val dwords = (bytes / 4UL).toInt()
        for (i in 0 until dwords) src.writeDword(i.toULong() * 4UL, 0xA5A50000u + i.toUInt())
        dst.zero()

        seq++
        fence.writeDword(0UL, 0u)
        Cpu.storeFence()

        emit(VegaReg.SDMA_OP_COPY or (VegaReg.SDMA_SUBOP_COPY_LINEAR shl 8))
        emit((bytes - 1UL).toUInt())
        emit(0u)
        emit((src.gpuAddress and 0xFFFFFFFFUL).toUInt())
        emit((src.gpuAddress shr 32).toUInt())
        emit((dst.gpuAddress and 0xFFFFFFFFUL).toUInt())
        emit((dst.gpuAddress shr 32).toUInt())

        emit(VegaReg.SDMA_OP_FENCE)
        emit((fence.gpuAddress and 0xFFFFFFFFUL).toUInt())
        emit((fence.gpuAddress shr 32).toUInt())
        emit(seq)

        commit()

        var spins = FENCE_SPINS
        while (spins > 0 && fence.readDword(0UL) != seq) spins--

        val signalled = fence.readDword(0UL) == seq
        if (!signalled) {
            KLog.step(
                "gpu",
                "sdma copy",
                false,
                "fence ${KLog.hex(fence.readDword(0UL))} rptr ${KLog.hex(regs.read(VegaReg.SDMA0_GFX_RB_RPTR))} " +
                    "wptr ${KLog.hex(regs.read(VegaReg.SDMA0_GFX_RB_WPTR))} " +
                    "status ${KLog.hex(regs.read(VegaReg.SDMA0_STATUS_REG))}",
            )
            regs.write(VegaReg.HDP_READ_CACHE_INVALIDATE, 1u)
            KLog.info(
                "gpu",
                "vm fault ${KLog.hex(regs.read(VegaReg.VM_L2_PROTECTION_FAULT_STATUS))} " +
                    "rptr_wb ${KLog.hex(rptr.readDword(0UL))} " +
                    "dst0 ${KLog.hex(dst.readDword(0UL))} ring0 ${KLog.hex(ring.readDword(0UL))}",
            )
            return false
        }

        regs.write(VegaReg.HDP_READ_CACHE_INVALIDATE, 1u)

        var mismatch = -1
        for (i in 0 until dwords) {
            if (dst.readDword(i.toULong() * 4UL) != 0xA5A50000u + i.toUInt()) {
                mismatch = i
                break
            }
        }

        KLog.step("gpu", "sdma copy", mismatch < 0, if (mismatch < 0) "$bytes bytes verified" else "dword $mismatch differs")
        return mismatch < 0
    }

    private companion object {
        const val RING_DWORDS = 1024
        const val RING_ORDER = 10
        const val ALIGN_MASK = 0xFF
        const val POLL_OFFSET = 0x100UL
        const val FENCE_SPINS = 20_000_000
        const val MAX_COPY = 0x200000UL
    }
}
