package kernel.arch

import hal.Cpu

object PerfMonitor {
    var nominalMhz = 0
        private set

    private var effFreq = false
    private var lastActive = 0UL
    private var lastReference = 0UL

    fun initialize() {
        val start = Cpu.timestamp()
        Pit.waitMicros(50_000u)
        val elapsed = Cpu.timestamp() - start
        nominalMhz = (elapsed / 50_000UL).toInt()

        effFreq = Cpu.hasEffectiveFrequency()
        if (effFreq) {
            lastActive = Cpu.activeCycles()
            lastReference = Cpu.referenceCycles()
        }
    }

    fun actualMhz(): Int {
        if (!effFreq || nominalMhz == 0) return nominalMhz
        val active = Cpu.activeCycles()
        val reference = Cpu.referenceCycles()
        val deltaActive = active - lastActive
        val deltaReference = reference - lastReference
        lastActive = active
        lastReference = reference
        if (deltaReference == 0UL) return nominalMhz
        return ((nominalMhz.toULong() * deltaActive) / deltaReference).toInt()
    }
}
