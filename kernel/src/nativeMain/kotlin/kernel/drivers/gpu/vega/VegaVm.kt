package kernel.drivers.gpu.vega

import kernel.KLog

class HubRegs(
    val tlbCntl: UInt,
    val l2Cntl: UInt,
    val l2Cntl2: UInt,
    val l2Cntl3: UInt,
    val l2Cntl4: UInt,
    val context0Cntl: UInt,
    val pageTableBaseLo: UInt,
    val pageTableBaseHi: UInt,
    val pageTableStartLo: UInt,
    val pageTableStartHi: UInt,
    val pageTableEndLo: UInt,
    val pageTableEndHi: UInt,
    val defaultAddrLsb: UInt,
    val defaultAddrMsb: UInt,
    val faultDefaultLo: UInt,
    val faultDefaultHi: UInt,
)

object VegaVm {
    var mmhubEnabled: Boolean = false
        private set

    var gfxhubEnabled: Boolean = false
        private set

    private val MMHUB = HubRegs(
        VegaReg.MC_VM_MX_L1_TLB_CNTL, VegaReg.VM_L2_CNTL, VegaReg.VM_L2_CNTL2, VegaReg.VM_L2_CNTL3, VegaReg.VM_L2_CNTL4,
        VegaReg.VM_CONTEXT0_CNTL,
        VegaReg.VM_CONTEXT0_PAGE_TABLE_BASE_ADDR_LO32, VegaReg.VM_CONTEXT0_PAGE_TABLE_BASE_ADDR_HI32,
        VegaReg.VM_CONTEXT0_PAGE_TABLE_START_ADDR_LO32, VegaReg.VM_CONTEXT0_PAGE_TABLE_START_ADDR_HI32,
        VegaReg.VM_CONTEXT0_PAGE_TABLE_END_ADDR_LO32, VegaReg.VM_CONTEXT0_PAGE_TABLE_END_ADDR_HI32,
        VegaReg.MC_VM_SYSTEM_APERTURE_DEFAULT_ADDR_LSB, VegaReg.MC_VM_SYSTEM_APERTURE_DEFAULT_ADDR_MSB,
        VegaReg.VM_L2_PROTECTION_FAULT_DEFAULT_ADDR_LO32, VegaReg.VM_L2_PROTECTION_FAULT_DEFAULT_ADDR_HI32,
    )

    private val GFXHUB = HubRegs(
        VegaReg.GFX_MC_VM_MX_L1_TLB_CNTL, VegaReg.GFX_VM_L2_CNTL, VegaReg.GFX_VM_L2_CNTL2, VegaReg.GFX_VM_L2_CNTL3, VegaReg.GFX_VM_L2_CNTL4,
        VegaReg.GFX_VM_CONTEXT0_CNTL,
        VegaReg.GFX_VM_CONTEXT0_PAGE_TABLE_BASE_ADDR_LO32, VegaReg.GFX_VM_CONTEXT0_PAGE_TABLE_BASE_ADDR_HI32,
        VegaReg.GFX_VM_CONTEXT0_PAGE_TABLE_START_ADDR_LO32, VegaReg.GFX_VM_CONTEXT0_PAGE_TABLE_START_ADDR_HI32,
        VegaReg.GFX_VM_CONTEXT0_PAGE_TABLE_END_ADDR_LO32, VegaReg.GFX_VM_CONTEXT0_PAGE_TABLE_END_ADDR_HI32,
        VegaReg.GFX_MC_VM_SYSTEM_APERTURE_DEFAULT_ADDR_LSB, VegaReg.GFX_MC_VM_SYSTEM_APERTURE_DEFAULT_ADDR_MSB,
        VegaReg.GFX_VM_L2_PROTECTION_FAULT_DEFAULT_ADDR_LO32, VegaReg.GFX_VM_L2_PROTECTION_FAULT_DEFAULT_ADDR_HI32,
    )

    fun enableMmhub(regs: VegaRegs): Boolean {
        mmhubEnabled = enableHub(regs, MMHUB, "gpuvm")
        return mmhubEnabled
    }

    fun enableGfxhub(regs: VegaRegs): Boolean {
        gfxhubEnabled = enableHub(regs, GFXHUB, "gfxhub vm")
        fixGfxhubAperture(regs)
        invalidateGfxhubTlb(regs)
        return gfxhubEnabled
    }

    fun invalidateGfxhubTlb(regs: VegaRegs): Boolean {
        regs.write(VegaReg.GFX_VM_INVALIDATE_ENG17_ADDR_RANGE_LO32, 0xFFFFFFFFu)
        regs.write(VegaReg.GFX_VM_INVALIDATE_ENG17_ADDR_RANGE_HI32, 0x1Fu)

        regs.write(VegaReg.GFX_VM_INVALIDATE_ENG17_REQ, VegaReg.VM_INVALIDATE_REQ_ALL)
        regs.read(VegaReg.GFX_VM_INVALIDATE_ENG17_REQ)

        var spins = 200_000
        while (spins > 0) {
            if (regs.read(VegaReg.GFX_VM_INVALIDATE_ENG17_ACK) and 0x1u != 0u) return true
            spins--
        }
        return false
    }

    fun fixGfxhubAperture(regs: VegaRegs) {
        val fbBaseReg = regs.read(VegaReg.MC_VM_FB_LOCATION_BASE)
        val fbTopReg = regs.read(VegaReg.MC_VM_FB_LOCATION_TOP)
        val fbBase = (fbBaseReg and 0xFFFFFFu).toULong() shl 24
        val fbTop = (fbTopReg and 0xFFFFFFu).toULong() shl 24

        regs.write(VegaReg.GFX_MC_VM_FB_LOCATION_BASE, fbBaseReg)
        regs.write(VegaReg.GFX_MC_VM_FB_LOCATION_TOP, fbTopReg)
        regs.write(VegaReg.GFX_MC_VM_SYSTEM_APERTURE_LOW_ADDR, (fbBase shr 18).toUInt())
        regs.write(VegaReg.GFX_MC_VM_SYSTEM_APERTURE_HIGH_ADDR, ((fbTop + 0x1000000UL) shr 18).toUInt())
    }

    private fun enableHub(regs: VegaRegs, hub: HubRegs, label: String): Boolean {
        val pdb = VegaVram.allocate(0x1000UL) ?: return false
        pdb.zero()

        val pdbPa = mc2pa(pdb.gpuAddress)

        regs.write(hub.pageTableBaseLo, (pdbPa and 0xFFFFFFFFUL).toUInt())
        regs.write(hub.pageTableBaseHi, (pdbPa shr 32).toUInt())
        regs.write(hub.pageTableStartLo, 0u)
        regs.write(hub.pageTableStartHi, 0u)
        regs.write(hub.pageTableEndLo, 0u)
        regs.write(hub.pageTableEndHi, 0u)

        regs.write(hub.defaultAddrLsb, (pdbPa shr 12).toUInt())
        regs.write(hub.defaultAddrMsb, (pdbPa shr 44).toUInt())
        regs.write(hub.faultDefaultLo, (pdbPa shr 12).toUInt())
        regs.write(hub.faultDefaultHi, (pdbPa shr 44).toUInt())

        regs.write(hub.tlbCntl, VegaReg.L1_TLB_CNTL_VALUE)

        regs.write(hub.l2Cntl, regs.read(hub.l2Cntl) or VegaReg.L2_CNTL_ENABLE)
        regs.write(hub.l2Cntl2, VegaReg.L2_CNTL2_VALUE)
        regs.write(hub.l2Cntl3, VegaReg.L2_CNTL3_VALUE)
        regs.write(hub.l2Cntl4, VegaReg.L2_CNTL4_VALUE)

        regs.write(hub.context0Cntl, regs.read(hub.context0Cntl) or 0x1u)

        val ctx = regs.read(hub.context0Cntl)
        val l2 = regs.read(hub.l2Cntl)
        val tlb = regs.read(hub.tlbCntl)

        val ok = ctx and 0x1u != 0u && l2 and 0x1u != 0u
        KLog.step("gpu", label, ok, "ctx0 ${KLog.hex(ctx)} l2 ${KLog.hex(l2)} tlb ${KLog.hex(tlb)} pdb ${KLog.hex(pdbPa)}")
        return ok
    }

    fun mc2pa(mcAddress: ULong): ULong =
        mcAddress - GpuService.carveoutBase + GpuService.carveoutSysBase
}
