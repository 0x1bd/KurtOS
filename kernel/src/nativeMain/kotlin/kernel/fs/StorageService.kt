package kernel.fs

import kernel.drivers.usb.MassStorage
import kernel.drivers.usb.USBDevice
import kernel.drivers.usb.USBService

object StorageService {
    const val LABEL = "KURTDATA"

    private var device: USBDevice? = null
    private var disk: MassStorage? = null
    private var volume: Fat32? = null

    private var attempted = false

    var status: String = "not initialized"
        private set

    val ready: Boolean get() = volume?.mounted == true

    fun initialize(): Boolean {
        if (attempted) return ready
        attempted = true

        return attach()
    }

    fun volume(): Fat32? {
        if (!attempted) initialize()
        if (!live()) reattach()

        return volume
    }

    fun refresh(): Boolean {
        reattach()
        return ready
    }

    private fun reattach() {
        device = null
        disk = null
        volume = null

        attach()
    }

    private fun live(): Boolean {
        val current = device ?: return false
        if (!USBService.all().any { it === current }) return false

        return USBService.connected(current)
    }

    private fun attach(): Boolean {
        if (!USBService.initialize()) {
            status = USBService.status
            return false
        }

        val found = USBService.all().firstOrNull { it.storage && it.configured }
        if (found == null) {
            status = "no usb mass storage device"
            return false
        }

        val medium = MassStorage(found)
        if (!medium.initialize()) {
            status = medium.status
            return false
        }

        val partitions = Gpt.partitions(medium)
        if (partitions.isEmpty()) {
            status = "${medium.vendor} ${medium.product}: no gpt partition table"
            return false
        }

        val entry = partitions.firstOrNull { it.name == LABEL }
            ?: partitions.firstOrNull { it.type == Gpt.BASIC_DATA }

        if (entry == null) {
            status = "${medium.vendor} ${medium.product}: no $LABEL partition"
            return false
        }

        val mounted = Fat32(Partition(medium, entry.firstLba, entry.blocks))
        if (!mounted.mount()) {
            status = "$LABEL: ${mounted.status}"
            return false
        }

        device = found
        disk = medium
        volume = mounted

        status = mounted.status

        return true
    }

    fun describe(): List<String> {
        val lines = mutableListOf(status)

        val medium = disk ?: return lines
        lines.add("device: ${medium.status}")

        for (entry in Gpt.partitions(medium)) {
            val mib = entry.blocks * medium.blockSize.toULong() / (1024UL * 1024UL)
            val name = entry.name.ifEmpty { "unnamed" }
            lines.add("  $name  lba ${entry.firstLba}..${entry.lastLba}  $mib MiB  ${entry.type}")
        }

        return lines
    }
}
