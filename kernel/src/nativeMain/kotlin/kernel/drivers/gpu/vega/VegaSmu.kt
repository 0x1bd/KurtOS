package kernel.drivers.gpu.vega

class VegaSmu(private val regs: VegaRegs, private val direct: Boolean) {
    fun send(message: UInt, argument: UInt? = null): UInt? {
        drainPrevious()

        write(VegaReg.MP1_C2PMSG_90, 0u)
        if (argument != null) write(VegaReg.MP1_C2PMSG_82, argument)
        write(VegaReg.MP1_C2PMSG_66, message)

        var spins = RESPONSE_SPINS
        while (spins > 0) {
            val response = read(VegaReg.MP1_C2PMSG_90)
            if (response != 0u) return response
            spins--
        }

        return null
    }

    fun argument(): UInt = read(VegaReg.MP1_C2PMSG_82)

    private fun read(reg: UInt): UInt = if (direct) regs.read(reg) else regs.readIndirect(reg)

    private fun write(reg: UInt, value: UInt) {
        if (direct) regs.write(reg, value) else regs.writeIndirect(reg, value)
    }

    private fun drainPrevious() {
        var spins = DRAIN_SPINS
        while (spins > 0) {
            if (read(VegaReg.MP1_C2PMSG_90) != 0u) return
            spins--
        }
    }

    private companion object {
        const val RESPONSE_SPINS = 2_000_000
        const val DRAIN_SPINS = 200_000
    }
}
