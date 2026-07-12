package kernel.drivers.usb

import kernel.drivers.Pci

object USBService {
    private val controllers = mutableListOf<Xhci>()
    private val devices = mutableListOf<USBDevice>()
    private val notes = mutableListOf<String>()

    var status: String = "not initialized"
        private set

    fun all(): List<USBDevice> = devices

    fun initialize(): Boolean {
        if (controllers.isNotEmpty()) return true

        val found = Pci.findAll(CLASS_SERIAL_BUS, SUBCLASS_USB)
        if (found.isEmpty()) {
            status = "no usb controller"
            return false
        }

        for (device in found) {
            if (device.programmingInterface != PROGIF_XHCI) {
                notes.add("skipped non-xhci controller (progif 0x${device.programmingInterface.toString(16)})")
                continue
            }

            val xhci = Xhci(device)
            if (!xhci.initialize()) {
                notes.add("controller: ${xhci.status}")
                continue
            }

            controllers.add(xhci)
            enumerate(xhci)
        }

        if (controllers.isEmpty()) {
            status = "no usable xhci controller"
            return false
        }

        status = "${controllers.size} xhci controller(s), ${devices.size} device(s)"
        return true
    }

    fun rescan(): Boolean {
        if (controllers.isEmpty()) return initialize()

        notes.clear()

        for (xhci in controllers) {
            xhci.powerPorts()
            discard(xhci)

            for (port in xhci.scanPorts()) {
                if (!xhci.connectChanged(port.number)) continue

                devices.removeAll { it.controller === xhci && it.port == port.number }
                xhci.clearPortChanges(port.number)
            }
        }

        devices.retainAll { it.controller.portStatus(it.port) and CONNECTED != 0u }

        for (xhci in controllers) enumerate(xhci)

        status = "${controllers.size} xhci controller(s), ${devices.size} device(s)"
        return true
    }

    private fun discard(xhci: Xhci) {
        xhci.drainEvents()

        for (device in devices) {
            if (device.controller === xhci) device.rearm()
        }
    }

    private fun enumerate(xhci: Xhci) {
        for (port in xhci.scanPorts()) {
            if (!port.connected) continue
            if (devices.any { it.controller === xhci && it.port == port.number }) continue

            xhci.clearPortChanges(port.number)

            var enabled = port.enabled
            if (!enabled) enabled = xhci.resetPort(port.number)

            if (!enabled) {
                notes.add("port ${port.number}: reset failed (portsc 0x${hexLong(xhci.portStatus(port.number))})")
                continue
            }

            discard(xhci)

            val speed = xhci.portSpeed(port.number)

            val result = xhci.submitCommand(0UL, 0u, (Xhci.TRB_ENABLE_SLOT shl 10).toUInt())
            if (result == null) {
                notes.add("port ${port.number}: enable slot timed out")
                continue
            }

            val completion = ((result shr 24) and 0xFFu).toInt()
            if (completion != 1) {
                notes.add("port ${port.number}: enable slot failed (code $completion)")
                continue
            }

            val slot = xhci.lastSlot()
            if (slot == 0) {
                notes.add("port ${port.number}: controller returned slot 0")
                continue
            }

            val psi = xhci.rawPortSpeed(port.number)

            val device = USBDevice(xhci, slot, port.number, speed, psi)
            if (!device.address()) {
                notes.add(
                    "port ${port.number}: address device failed " +
                        "(${speedName(speed)}, psi $psi, usb${xhci.protocolOf(port.number)})"
                )
                continue
            }

            if (device.input != null && !device.configure()) {
                notes.add("port ${port.number}: configure failed for ${hex(device.vendorId)}:${hex(device.productId)}")
            }

            devices.add(device)
        }
    }

    fun describe(): List<String> {
        if (controllers.isEmpty()) return listOf(status)

        val lines = mutableListOf(status)

        devices.forEachIndexed { index, device ->
            lines.add(
                "#$index port ${device.port}  ${hex(device.vendorId)}:${hex(device.productId)}  " +
                    "${hex2(device.interfaceClass)}/${hex2(device.interfaceSubclass)}/" +
                    "${hex2(device.interfaceProtocol)}  ${name(device)}"
            )
        }

        lines.addAll(notes)

        if (devices.isEmpty() && notes.isEmpty()) lines.add("no devices enumerated")
        return lines
    }

    fun detail(index: Int): List<String> {
        if (controllers.isEmpty()) return listOf(status)

        val device = devices.getOrNull(index) ?: return listOf("no device #$index")
        val xhci = device.controller

        val lines = mutableListOf<String>()

        lines.add(
            "#$index slot ${device.slot} port ${device.port} " +
                "${hex(device.vendorId)}:${hex(device.productId)} " +
                "${speedName(device.speed)} speed " +
                "(psi ${device.psi}, usb${xhci.protocolOf(device.port)})"
        )

        device.interfaces.forEach { lines.add("  $it") }

        device.endpoints.forEach { endpoint ->
            val direction = if (endpoint.isInput) "in " else "out"
            val kind = when (endpoint.attributes and 0x03) {
                0 -> "control"
                1 -> "isoch"
                2 -> "bulk"
                else -> "interrupt"
            }
            lines.add(
                "  if ${endpoint.owner} ep 0x${endpoint.address.toString(16)} $direction $kind " +
                    "${endpoint.maxPacket} bytes, interval ${endpoint.interval}"
            )
        }

        lines.add(if (device.configured) "  configured" else "  NOT configured (${device.configureError})")

        return lines
    }

    fun report(index: Int, buffer: ByteArray): Int {
        val device = devices.getOrNull(index) ?: return -1
        return device.poll(buffer)
    }

    fun lastCompletion(index: Int): Int = devices.getOrNull(index)?.lastCompletion ?: -1

    fun connected(device: USBDevice): Boolean =
        device.controller.portStatus(device.port) and CONNECTED != 0u

    fun changed(): Boolean {
        for (xhci in controllers) {
            for (port in xhci.scanPorts()) {
                if (xhci.connectChanged(port.number)) return true
            }
        }

        return devices.any { !connected(it) }
    }

    fun configurationOf(index: Int): ByteArray = devices.getOrNull(index)?.configBytes ?: ByteArray(0)

    private fun speedName(speed: Int): String = when (speed) {
        0 -> "undefined"
        1 -> "full"
        2 -> "low"
        3 -> "high"
        4 -> "super"
        5 -> "super+"
        else -> "speed$speed"
    }

    private fun name(device: USBDevice): String = when {
        device.interfaceClass == 0xFF && device.interfaceSubclass == 0x5D -> "xinput gamepad"
        device.interfaceClass == 0xFF -> "vendor specific"
        device.interfaceClass == 0x03 && device.interfaceProtocol == 0x01 -> "hid keyboard"
        device.interfaceClass == 0x03 && device.interfaceProtocol == 0x02 -> "hid mouse"
        device.interfaceClass == 0x03 -> "hid device"
        device.interfaceClass == 0x08 -> "mass storage"
        device.interfaceClass == 0x09 -> "hub (not supported)"
        else -> "device"
    }

    private fun hex(value: Int): String = value.toString(16).padStart(4, '0')

    private fun hex2(value: Int): String = value.toString(16).padStart(2, '0')

    private fun hexLong(value: UInt): String = value.toString(16).padStart(8, '0')

    private const val CONNECTED = 0x1u

    private const val CLASS_SERIAL_BUS = 0x0C
    private const val SUBCLASS_USB = 0x03
    private const val PROGIF_XHCI = 0x30
}
