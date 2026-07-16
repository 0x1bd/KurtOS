package kernel.drivers.usb

import kapi.Pad

class Gamepad(val device: USBDevice) {
    private val report = ByteArray(64)
    private val buttons = BooleanArray(Pad.COUNT)
    private val axes = IntArray(Pad.AXES)

    private val xinput = device.interfaceClass == VENDOR_CLASS &&
        device.interfaceSubclass == XINPUT_SUBCLASS

    private var hid: HidReportMap? = null

    val vendorId: Int get() = device.vendorId
    val productId: Int get() = device.productId
    val port: Int get() = device.port

    val connected: Boolean get() = USBService.connected(device)

    val live: Boolean get() = reports > 0

    var reports = 0
        private set

    val kind: String get() = if (xinput) "xinput" else "hid"

    val descriptor: String
        get() {
            val id = "class ${hex2(device.interfaceClass)}/" +
                "${hex2(device.interfaceSubclass)}/${hex2(device.interfaceProtocol)}"

            val endpoint = device.input ?: return "$id, no interrupt in endpoint"

            val state = if (device.configured) {
                "configured"
            } else {
                "NOT configured (${device.configureError})"
            }

            val mapping = hid?.let { ", ${it.inputs} hid inputs" } ?: ""

            return "$id, ep 0x${endpoint.address.toString(16)}, ${endpoint.maxPacket} bytes, " +
                "interval ${endpoint.interval}, $state$mapping"
        }

    fun start(): Boolean {
        if (!xinput) {
            val raw = device.hidReportDescriptor() ?: return false
            val map = HidReport.parse(raw) ?: return false
            if (!map.gamepad) return false
            hid = map
        }

        if (xinput && device.output != null) device.send(LED_COMMAND)
        if (xinput) device.initSiblings()

        val submitted = device.submit()
        if (xinput) device.xinputStart()

        return submitted
    }

    fun poll() {
        val length = device.complete(report)

        if (length > 0) {
            reports++
            decode(length)
        }

        device.submit()
    }

    fun isDown(button: Int): Boolean {
        if (button < 0 || button >= buttons.size) return false
        return buttons[button]
    }

    fun axis(index: Int): Int {
        if (index < 0 || index >= axes.size) return 0
        return axes[index]
    }

    private fun decode(length: Int) {
        if (xinput) {
            decodeXinput(length)
            return
        }

        val map = hid ?: return
        map.decode(report, length, buttons, axes)

        buttons[Pad.LT] = buttons[Pad.LT] || axes[Pad.AXIS_LT] > AXIS_TRIGGER_THRESHOLD
        buttons[Pad.RT] = buttons[Pad.RT] || axes[Pad.AXIS_RT] > AXIS_TRIGGER_THRESHOLD

        mergeDirections()
    }

    private fun decodeXinput(length: Int) {
        val base = payloadOffset(length)
        if (base < 0) return

        val first = report[base + 2].toInt() and 0xFF
        val second = report[base + 3].toInt() and 0xFF

        axes[Pad.AXIS_LX] = word16(base + 6)
        axes[Pad.AXIS_LY] = word16(base + 8)
        axes[Pad.AXIS_RX] = word16(base + 10)
        axes[Pad.AXIS_RY] = word16(base + 12)
        axes[Pad.AXIS_LT] = (report[base + 4].toInt() and 0xFF) * 128
        axes[Pad.AXIS_RT] = (report[base + 5].toInt() and 0xFF) * 128

        buttons[Pad.DPAD_UP] = first and 0x01 != 0
        buttons[Pad.DPAD_DOWN] = first and 0x02 != 0
        buttons[Pad.DPAD_LEFT] = first and 0x04 != 0
        buttons[Pad.DPAD_RIGHT] = first and 0x08 != 0

        buttons[Pad.START] = first and 0x10 != 0
        buttons[Pad.SELECT] = first and 0x20 != 0
        buttons[Pad.L3] = first and 0x40 != 0
        buttons[Pad.R3] = first and 0x80 != 0

        buttons[Pad.L] = second and 0x01 != 0
        buttons[Pad.R] = second and 0x02 != 0
        buttons[Pad.GUIDE] = second and 0x04 != 0

        buttons[Pad.A] = second and 0x10 != 0
        buttons[Pad.B] = second and 0x20 != 0
        buttons[Pad.X] = second and 0x40 != 0
        buttons[Pad.Y] = second and 0x80 != 0

        buttons[Pad.LT] = axes[Pad.AXIS_LT] > AXIS_TRIGGER_THRESHOLD
        buttons[Pad.RT] = axes[Pad.AXIS_RT] > AXIS_TRIGGER_THRESHOLD

        mergeDirections()
    }

    private fun mergeDirections() {
        applyStick(axes[Pad.AXIS_LX], axes[Pad.AXIS_LY])

        buttons[Pad.UP] = buttons[Pad.UP] || buttons[Pad.DPAD_UP]
        buttons[Pad.DOWN] = buttons[Pad.DOWN] || buttons[Pad.DPAD_DOWN]
        buttons[Pad.LEFT] = buttons[Pad.LEFT] || buttons[Pad.DPAD_LEFT]
        buttons[Pad.RIGHT] = buttons[Pad.RIGHT] || buttons[Pad.DPAD_RIGHT]
    }

    private fun payloadOffset(length: Int): Int {
        for (base in PAYLOAD_OFFSETS) {
            if (base + REPORT_BYTES > length) continue
            if (report[base].toInt() != 0) continue
            if ((report[base + 1].toInt() and 0xFF) != REPORT_BYTES) continue
            return base
        }

        return -1
    }

    private fun applyStick(x: Int, y: Int) {
        val absX = if (x < 0) -x else x
        val absY = if (y < 0) -y else y

        val horizontal = absX > DEADZONE && absX * 2 > absY
        val vertical = absY > DEADZONE && absY * 2 > absX

        buttons[Pad.LEFT] = horizontal && x < 0
        buttons[Pad.RIGHT] = horizontal && x > 0
        buttons[Pad.DOWN] = vertical && y < 0
        buttons[Pad.UP] = vertical && y > 0
    }

    private fun hex2(value: Int): String = value.toString(16).padStart(2, '0')

    private fun word16(offset: Int): Int {
        val low = report[offset].toInt() and 0xFF
        val high = report[offset + 1].toInt()
        return (high shl 8) or low
    }

    private companion object {
        const val REPORT_BYTES = 20
        const val DEADZONE = 12000
        const val AXIS_TRIGGER_THRESHOLD = 8192

        const val VENDOR_CLASS = 0xFF
        const val XINPUT_SUBCLASS = 0x5D

        val LED_COMMAND = byteArrayOf(0x01, 0x03, 0x02)

        val PAYLOAD_OFFSETS = intArrayOf(0, 4)
    }
}
