package kernel.drivers.gpu.vega

import hal.Clock
import kernel.drivers.gpu.GpuLog

class VegaGfx(private val regs: VegaRegs, private val smu: VegaSmu?) {
    fun disableGfxOff(): Boolean {
        val mailbox = smu
        if (mailbox == null) {
            GpuLog.step("gfxoff off", false, "no smu")
            return false
        }

        val resp = mailbox.send(VegaReg.SMU_MSG_DISABLE_GFXOFF)
        if (resp != VegaReg.SMU_RESULT_OK) {
            GpuLog.step("gfxoff off", false, "resp=${resp?.let { GpuLog.hex(it) } ?: "timeout"}")
            return false
        }

        val deadline = Clock.uptimeMillis() + 50UL
        while (Clock.uptimeMillis() < deadline) {
            val status = regs.read(VegaReg.PWR_MISC_CNTL_STATUS)
            if (status and VegaReg.PWR_GFXOFF_STATUS_MASK == VegaReg.PWR_GFXOFF_STATUS_ON) {
                GpuLog.step("gfxoff off", true, "pwr ${GpuLog.hex(status)}")
                return true
            }
        }

        GpuLog.step("gfxoff off", false, "pwr ${GpuLog.hex(regs.read(VegaReg.PWR_MISC_CNTL_STATUS))} not on")
        return false
    }

    fun startRlc(): Boolean {
        regs.write(VegaReg.RLC_CNTL, regs.read(VegaReg.RLC_CNTL) and 0x1u.inv())
        regs.write(VegaReg.RLC_CGCG_CGLS_CTRL, 0u)

        regs.write(VegaReg.RLC_CNTL, regs.read(VegaReg.RLC_CNTL) or 0x1u)
        settle(1UL)

        val cntl = regs.read(VegaReg.RLC_CNTL)
        val stat = regs.read(VegaReg.RLC_GPM_STAT)
        GpuLog.step("rlc start", cntl and 0x1u != 0u, "cntl ${GpuLog.hex(cntl)} stat ${GpuLog.hex(stat)}")
        return cntl and 0x1u != 0u
    }

    fun enableMec(): Boolean {
        regs.write(VegaReg.CP_MEC_CNTL, 0u)
        settle(1UL)

        val cntl = regs.read(VegaReg.CP_MEC_CNTL)
        val cp = regs.read(VegaReg.CP_STAT)
        GpuLog.step("mec enable", true, "mec_cntl ${GpuLog.hex(cntl)} cp ${GpuLog.hex(cp)}")
        return true
    }

    fun grbmSelect(me: Int, pipe: Int, queue: Int) {
        val value = (pipe.toUInt() shl 0) or (me.toUInt() shl 2) or (queue.toUInt() shl 8)
        regs.write(VegaReg.GRBM_GFX_CNTL, value)
    }

    fun grbmDeselect() {
        regs.write(VegaReg.GRBM_GFX_CNTL, 0u)
    }

    private fun settle(millis: ULong) {
        val deadline = Clock.uptimeMillis() + millis
        while (Clock.uptimeMillis() < deadline) {
        }
    }
}
