package kernel.drivers.gpu.vega

import kernel.KLog

import hal.Cpu

class VegaPsp(private val regs: VegaRegs) {
    private var ring: VramAlloc? = null
    private var cmd: VramAlloc? = null
    private var fence: VramAlloc? = null
    private var tmr: VramAlloc? = null
    private var fw: VramAlloc? = null

    private var wptr = 0
    private var seq = 0u

    fun initialize(): Boolean {
        val r = VegaVram.allocate(RING_BYTES) ?: return false
        val c = VegaVram.allocate(0x1000UL) ?: return false
        val f = VegaVram.allocate(0x1000UL) ?: return false
        val t = VegaVram.allocate(TMR_BYTES, TMR_ALIGN) ?: return false
        val b = VegaVram.allocate(FW_BYTES) ?: return false

        ring = r; cmd = c; fence = f; tmr = t; fw = b

        if (!createRing(r)) return false
        if (!setupTmr(t)) return false
        return true
    }

    private fun createRing(r: VramAlloc): Boolean {
        regs.write(VegaReg.MP0_C2PMSG_69, (r.gpuAddress and 0xFFFFFFFFUL).toUInt())
        regs.write(VegaReg.MP0_C2PMSG_70, (r.gpuAddress shr 32).toUInt())
        regs.write(VegaReg.MP0_C2PMSG_71, RING_BYTES.toUInt())
        regs.write(VegaReg.MP0_C2PMSG_64, VegaReg.PSP_RING_TYPE_KM shl 16)

        val ok = regs.poll(VegaReg.MP0_C2PMSG_64, 0x80000000u, 0x80000000u, 5_000_000)
        KLog.step("gpu", "psp ring", ok, "resp ${KLog.hex(regs.read(VegaReg.MP0_C2PMSG_64))}")
        return ok
    }

    private fun setupTmr(t: VramAlloc): Boolean {
        val c = cmd ?: return false
        c.zero()
        c.writeDword(0UL, 1024u)
        c.writeDword(4UL, 1u)
        c.writeDword(8UL, VegaReg.GFX_CMD_ID_SETUP_TMR)
        c.writeDword(28UL, (t.gpuAddress and 0xFFFFFFFFUL).toUInt())
        c.writeDword(32UL, (t.gpuAddress shr 32).toUInt())
        c.writeDword(36UL, TMR_BYTES.toUInt())
        c.writeDword(40UL, 0x2u)
        val pa = VegaVm.mc2pa(t.gpuAddress)
        c.writeDword(44UL, (pa and 0xFFFFFFFFUL).toUInt())
        c.writeDword(48UL, (pa shr 32).toUInt())

        val status = submit()
        KLog.step("gpu", "psp tmr", status == 0u, "status ${KLog.hex(status)}")
        return status == 0u
    }

    fun loadFirmware(name: String, fwType: UInt): Boolean {
        val ucode = GpuFirmware.load(name) ?: return false
        val label = name.removePrefix("picasso_").removeSuffix(".bin")
        return loadRegion(ucode, ucode.payloadOffset, ucode.payloadBytes, fwType, label)
    }

    fun loadMecJt(fwType: UInt): Boolean {
        val ucode = GpuFirmware.load("picasso_mec.bin") ?: return false
        return loadRegion(ucode, ucode.jtOffsetBytes, ucode.jtSizeBytes, fwType, "mec-jt")
    }

    private fun loadRegion(ucode: Ucode, byteOffset: Int, byteSize: Int, fwType: UInt, label: String): Boolean {
        val c = cmd ?: return false
        val b = fw ?: return false

        if (byteSize.toULong() > FW_BYTES || byteOffset + byteSize > ucode.data.size) {
            KLog.step("gpu", "psp $label", false, "region ${KLog.hex(byteOffset.toUInt())}+$byteSize out of range")
            return false
        }

        for (i in 0 until byteSize / 4) b.writeDword(i.toULong() * 4UL, ucode.dwordAt(byteOffset + i * 4))
        Cpu.storeFence()

        c.zero()
        c.writeDword(0UL, 1024u)
        c.writeDword(4UL, 1u)
        c.writeDword(8UL, VegaReg.GFX_CMD_ID_LOAD_IP_FW)
        c.writeDword(28UL, (b.gpuAddress and 0xFFFFFFFFUL).toUInt())
        c.writeDword(32UL, (b.gpuAddress shr 32).toUInt())
        c.writeDword(36UL, byteSize.toUInt())
        c.writeDword(40UL, fwType)

        val status = submit()
        KLog.step("gpu", "psp $label", status == 0u, "status ${KLog.hex(status)} ${byteSize}b")
        return status == 0u
    }

    private fun submit(): UInt {
        val r = ring ?: return 0xFFFFFFFFu
        val c = cmd ?: return 0xFFFFFFFFu
        val f = fence ?: return 0xFFFFFFFFu

        seq++
        f.writeDword(0UL, 0u)
        Cpu.storeFence()

        val frame = ((wptr / RB_FRAME_DW) * RB_FRAME_BYTES).toULong()
        r.writeDword(frame + 0UL, (c.gpuAddress and 0xFFFFFFFFUL).toUInt())
        r.writeDword(frame + 4UL, (c.gpuAddress shr 32).toUInt())
        r.writeDword(frame + 8UL, 1024u)
        r.writeDword(frame + 12UL, (f.gpuAddress and 0xFFFFFFFFUL).toUInt())
        r.writeDword(frame + 16UL, (f.gpuAddress shr 32).toUInt())
        r.writeDword(frame + 20UL, seq)
        Cpu.storeFence()

        wptr = (wptr + RB_FRAME_DW) % RING_DW
        regs.write(VegaReg.MP0_C2PMSG_67, wptr.toUInt())

        var spins = SUBMIT_SPINS
        while (spins > 0 && f.readDword(0UL) != seq) spins--
        if (f.readDword(0UL) != seq) return 0xDEAD0000u

        regs.write(VegaReg.HDP_READ_CACHE_INVALIDATE, 1u)
        return c.readDword(864UL)
    }

    private companion object {
        const val RING_BYTES: ULong = 0x10000UL
        const val RING_DW = 0x4000
        const val RB_FRAME_BYTES = 64
        const val RB_FRAME_DW = 16
        const val TMR_BYTES: ULong = 0x400000UL
        const val TMR_ALIGN: ULong = 0x100000UL
        const val FW_BYTES: ULong = 0x50000UL
        const val SUBMIT_SPINS = 40_000_000
    }
}
