package kernel.drivers.gpu.vega

import kernel.drivers.gpu.GpuLog

object VegaVm {
    var enabled: Boolean = false
        private set

    fun enable(regs: VegaRegs): Boolean {
        val pdb = VegaVram.allocate(0x1000UL) ?: return false
        pdb.zero()

        val pdbPa = mc2pa(pdb.gpuAddress)

        regs.write(VegaReg.VM_CONTEXT0_PAGE_TABLE_BASE_ADDR_LO32, (pdbPa and 0xFFFFFFFFUL).toUInt())
        regs.write(VegaReg.VM_CONTEXT0_PAGE_TABLE_BASE_ADDR_HI32, (pdbPa shr 32).toUInt())
        regs.write(VegaReg.VM_CONTEXT0_PAGE_TABLE_START_ADDR_LO32, 0u)
        regs.write(VegaReg.VM_CONTEXT0_PAGE_TABLE_START_ADDR_HI32, 0u)
        regs.write(VegaReg.VM_CONTEXT0_PAGE_TABLE_END_ADDR_LO32, 0u)
        regs.write(VegaReg.VM_CONTEXT0_PAGE_TABLE_END_ADDR_HI32, 0u)

        regs.write(VegaReg.MC_VM_SYSTEM_APERTURE_DEFAULT_ADDR_LSB, (pdbPa shr 12).toUInt())
        regs.write(VegaReg.MC_VM_SYSTEM_APERTURE_DEFAULT_ADDR_MSB, (pdbPa shr 44).toUInt())
        regs.write(VegaReg.VM_L2_PROTECTION_FAULT_DEFAULT_ADDR_LO32, (pdbPa shr 12).toUInt())
        regs.write(VegaReg.VM_L2_PROTECTION_FAULT_DEFAULT_ADDR_HI32, (pdbPa shr 44).toUInt())

        regs.write(VegaReg.MC_VM_MX_L1_TLB_CNTL, VegaReg.L1_TLB_CNTL_VALUE)

        regs.write(VegaReg.VM_L2_CNTL, regs.read(VegaReg.VM_L2_CNTL) or VegaReg.L2_CNTL_ENABLE)
        regs.write(VegaReg.VM_L2_CNTL2, VegaReg.L2_CNTL2_VALUE)
        regs.write(VegaReg.VM_L2_CNTL3, VegaReg.L2_CNTL3_VALUE)
        regs.write(VegaReg.VM_L2_CNTL4, VegaReg.L2_CNTL4_VALUE)

        regs.write(VegaReg.VM_CONTEXT0_CNTL, regs.read(VegaReg.VM_CONTEXT0_CNTL) or 0x1u)

        val ctx = regs.read(VegaReg.VM_CONTEXT0_CNTL)
        val l2 = regs.read(VegaReg.VM_L2_CNTL)
        val tlb = regs.read(VegaReg.MC_VM_MX_L1_TLB_CNTL)

        enabled = ctx and 0x1u != 0u && l2 and 0x1u != 0u
        GpuLog.step(
            "gpuvm", enabled,
            "ctx0 ${GpuLog.hex(ctx)} l2 ${GpuLog.hex(l2)} tlb ${GpuLog.hex(tlb)} pdb ${GpuLog.hex(pdbPa)}",
        )
        return enabled
    }

    fun mc2pa(mcAddress: ULong): ULong =
        mcAddress - GpuService.carveoutBase + GpuService.carveoutSysBase
}
