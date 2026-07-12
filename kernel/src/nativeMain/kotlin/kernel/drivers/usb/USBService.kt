package kernel.drivers.usb

import kernel.drivers.Pci

object USBService {
    private var controller: Xhci? = null
    private val devices = mutableListOf<UsbDevice>()

    var status: String = "not initialized"
        private set

    fun all(): List<UsbDevice> = devices

    fun initialize(): Boolean {
        if (controller != null) return true

        val device = Pci.find(CLASS_SERIAL_BUS, SUBCLASS_USB)
        if (device == null) {
            status = "no usb controller"
            return false
        }

        if (device.programmingInterface != PROGIF_XHCI) {
            status = "usb controller is not xhci (progif 0x${device.programmingInterface.toString(16)})"
            return false
        }

        val xhci = Xhci(device)
        if (!xhci.initialize()) {
            status = xhci.status
            return false
        }

        controller = xhci
        status = xhci.status

        enumerate(xhci)
        return true
    }

    private fun enumerate(xhci: Xhci) {
        for (port in xhci.scanPorts()) {
            if (!port.connected) continue

            xhci.clearPortChanges(port.number)

            var enabled = port.enabled
            if (!enabled) enabled = xhci.resetPort(port.number)
            if (!enabled) continue

            xhci.drainEvents()

            val speed = xhci.portSpeed(port.number)

            val result = xhci.submitCommand(0UL, 0u, (Xhci.TRB_ENABLE_SLOT shl 10).toUInt()) ?: continue
            if (((result shr 24) and 0xFFu).toInt() != 1) continue

            val slot = xhci.lastSlot()
            if (slot == 0) continue

            val device = UsbDevice(xhci, slot, port.number, speed)
            if (!device.address()) continue

            device.configure()
            devices.add(device)
        }
    }

    fun describe(): List<String> {
        controller ?: return listOf(status)

        val lines = mutableListOf(status)

        for (device in devices) {
            val id = "${hex(device.vendorId)}:${hex(device.productId)}"
            val kind = "class ${hex2(device.interfaceClass)}/" +
                "${hex2(device.interfaceSubclass)}/${hex2(device.interfaceProtocol)}"

            lines.add("port ${device.port} slot ${device.slot}  $id  $kind  ${name(device)}")

            val endpoint = device.input
            if (endpoint != null) {
                lines.add("  interrupt in: ep ${endpoint.number}, ${endpoint.maxPacket} bytes, interval ${endpoint.interval}")
            } else {
                lines.add("  no interrupt in endpoint")
            }
        }

        if (devices.isEmpty()) lines.add("no devices enumerated")
        return lines
    }

    fun report(index: Int, buffer: ByteArray): Int {
        val device = devices.getOrNull(index) ?: return -1
        return device.poll(buffer)
    }

    private fun name(device: UsbDevice): String = when {
        device.interfaceClass == 0xFF && device.interfaceSubclass == 0x5D -> "xinput gamepad"
        device.interfaceClass == 0x03 && device.interfaceProtocol == 0x01 -> "hid keyboard"
        device.interfaceClass == 0x03 && device.interfaceProtocol == 0x02 -> "hid mouse"
        device.interfaceClass == 0x03 -> "hid device"
        device.interfaceClass == 0x08 -> "mass storage"
        else -> "device"
    }

    private fun hex(value: Int): String = value.toString(16).padStart(4, '0')

    private fun hex2(value: Int): String = value.toString(16).padStart(2, '0')

    private const val CLASS_SERIAL_BUS = 0x0C
    private const val SUBCLASS_USB = 0x03
    private const val PROGIF_XHCI = 0x30
}
