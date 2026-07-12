package kernel.drivers.usb

import hal.Arch
import hal.Clock
import kapi.GamepadEvent

object GamepadService {
    private var gamepad: Gamepad? = null
    private var seenInterrupts = 0UL
    private var nextPoll = 0UL

    var status: String = "no gamepad"
        private set

    val available: Boolean get() = gamepad != null

    val live: Boolean get() = gamepad?.live == true

    fun initialize(): Boolean {
        if (gamepad != null) return true
        if (!USBService.initialize()) {
            status = USBService.status
            return false
        }

        return attach()
    }

    fun rescan(): Boolean {
        gamepad = null
        status = "no gamepad"

        USBService.rescan()
        return attach()
    }

    fun refresh(): Boolean {
        if (USBService.changed()) return rescan()

        val pad = gamepad
        if (pad != null && pad.connected) return true
        if (pad == null) return false

        return rescan()
    }

    fun pump(): GamepadEvent? {
        if (!USBService.ready) return null

        val interrupts = Arch.usbInterrupts()
        val now = Clock.uptimeMillis()
        if (interrupts == seenInterrupts && now < nextPoll) return null

        seenInterrupts = interrupts
        nextPoll = now + if (USBService.interruptsArmed) ARMED_POLL_MS else FALLBACK_POLL_MS
        USBService.acknowledgeInterrupts()

        if (!USBService.changed()) return null

        val before = identity()
        rescan()
        val after = identity()

        return when {
            before == null && after != null -> GamepadEvent.Connected
            before != null && after == null -> GamepadEvent.Disconnected
            after != null && before != after -> GamepadEvent.Connected
            else -> null
        }
    }

    private fun identity(): String? =
        gamepad?.let { "${it.vendorId}:${it.productId}:${it.port}" }

    fun summary(): String {
        val pad = gamepad ?: return status
        if (pad.live) return status

        return "$status - no reports yet (controller powered off, or not paired to the dongle)"
    }

    private fun attach(): Boolean {
        val devices = USBService.all().filter { it.input != null }

        val xinput = devices.firstOrNull {
            it.interfaceClass == VENDOR_CLASS && it.interfaceSubclass == XINPUT_SUBCLASS
        }
        if (xinput != null) return use(xinput, "xinput gamepad ${id(xinput)}")

        val hid = devices.firstOrNull {
            it.interfaceClass == HID_CLASS &&
                it.interfaceSubclass == 0 &&
                it.interfaceProtocol == 0
        }
        if (hid != null) return use(hid, "hid gamepad ${id(hid)} (raw only, no button mapping yet)")

        status = "no gamepad (${USBService.all().size} usb devices; run 'usb')"
        return false
    }

    private fun use(device: USBDevice, label: String): Boolean {
        val pad = Gamepad(device)
        pad.start()

        gamepad = pad
        status = label

        return true
    }

    fun poll() {
        gamepad?.poll()
    }

    fun isDown(button: Int): Boolean = gamepad?.isDown(button) ?: false

    fun descriptor(): String = gamepad?.descriptor ?: status

    private fun id(device: USBDevice): String =
        "${device.vendorId.toString(16).padStart(4, '0')}:${device.productId.toString(16).padStart(4, '0')}"

    private const val VENDOR_CLASS = 0xFF
    private const val XINPUT_SUBCLASS = 0x5D
    private const val HID_CLASS = 0x03

    private const val ARMED_POLL_MS = 2000UL
    private const val FALLBACK_POLL_MS = 400UL
}
