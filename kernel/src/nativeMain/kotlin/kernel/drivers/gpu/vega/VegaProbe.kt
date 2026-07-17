package kernel.drivers.gpu.vega

import kernel.drivers.Pci
import kernel.drivers.PciDevice

data class VegaBars(
    val vramBase: ULong,
    val vramSize: ULong,
    val doorbellBase: ULong,
    val doorbellSize: ULong,
    val registerBase: ULong,
    val registerSize: ULong,
)

object VegaProbe {
    private val SUPPORTED = mapOf(
        0x15D8 to "Picasso",
        0x15DD to "Raven",
    )

    fun find(): Pair<PciDevice, String>? {
        for (device in Pci.all()) {
            if (device.classCode != 0x03) continue
            if (device.vendorId != 0x1002) continue
            val name = SUPPORTED[device.deviceId] ?: continue
            return device to name
        }
        return null
    }

    fun bars(device: PciDevice): VegaBars? {
        var vramBase = 0UL
        var vramSize = 0UL
        var doorbellBase = 0UL
        var doorbellSize = 0UL
        var registerBase = 0UL
        var registerSize = 0UL

        var index = 0
        while (index < 6) {
            val size = device.barSize(index)
            if (size == 0UL) {
                index++
                continue
            }

            val base = device.bar(index)
            val wide = device.bar64(index)
            val prefetchable = device.barPrefetchable(index)

            when {
                prefetchable && size > vramSize -> {
                    if (vramSize > doorbellSize) {
                        doorbellBase = vramBase
                        doorbellSize = vramSize
                    }
                    vramBase = base
                    vramSize = size
                }
                prefetchable || (wide && size <= 0x100000UL) -> {
                    doorbellBase = base
                    doorbellSize = size
                }
                else -> {
                    registerBase = base
                    registerSize = size
                }
            }

            index += if (wide) 2 else 1
        }

        if (vramBase == 0UL || registerBase == 0UL) return null
        return VegaBars(vramBase, vramSize, doorbellBase, doorbellSize, registerBase, registerSize)
    }
}
