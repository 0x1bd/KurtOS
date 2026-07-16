package kernel.drivers.usb

import hal.Arch
import hal.Clock
import kapi.GamepadEvent
import kapi.Pad
import kotlin.concurrent.AtomicInt
import kotlin.concurrent.AtomicLongArray
import kotlin.concurrent.Volatile

@OptIn(ExperimentalStdlibApi::class)
object GamepadService {
    private val pads = arrayOfNulls<Gamepad>(Pad.MAX_PLAYERS)

    private val states = AtomicLongArray(Pad.MAX_PLAYERS)
    private val sticks = AtomicLongArray(Pad.MAX_PLAYERS)
    private val players = AtomicInt(0)

    private val connects = AtomicInt(0)
    private val disconnects = AtomicInt(0)
    private var seenConnects = 0
    private var seenDisconnects = 0

    @Volatile
    var threaded = false

    @Volatile
    var status: String = "no gamepad"
        private set

    private var seenInterrupts = 0UL
    private var nextCheck = 0UL

    val available: Boolean get() = players.value > 0

    val count: Int get() = players.value

    val live: Boolean get() = pads.any { it?.live == true }

    fun initialize(): Boolean {
        if (!USBService.initialize()) {
            status = USBService.status
            return false
        }

        claim()
        seenConnects = connects.value
        seenDisconnects = disconnects.value

        return available
    }

    fun service() {
        checkHotplug()

        for (i in pads.indices) {
            val pad = pads[i] ?: continue
            pad.poll()
            publish(i, pad)
        }
    }

    private fun checkHotplug() {
        if (!USBService.ready) return

        val interrupts = Arch.usbInterrupts()
        val now = Clock.uptimeMillis()
        if (interrupts == seenInterrupts && now < nextCheck) return

        seenInterrupts = interrupts
        nextCheck = now + CHECK_MS
        USBService.acknowledgeInterrupts()

        if (!USBService.changed()) return
        if (USBService.handleChanges()) claim()
    }

    private fun claim() {
        val current = USBService.all()

        for (i in pads.indices) {
            val pad = pads[i] ?: continue
            if (current.contains(pad.device) && pad.device.configured) continue

            pads[i] = null
            states[i] = 0L
            sticks[i] = 0L
            disconnects.incrementAndGet()
        }

        claimMatching(current) { it.interfaceClass == VENDOR_CLASS && it.interfaceSubclass == XINPUT_SUBCLASS }
        claimMatching(current) { it.interfaceClass == HID_CLASS }

        players.value = pads.count { it != null }
        status = summarize()
    }

    private fun claimMatching(devices: List<USBDevice>, match: (USBDevice) -> Boolean) {
        for (device in devices) {
            if (device.input == null || !device.configured || device.storage) continue
            if (!match(device)) continue
            if (pads.any { it?.device === device }) continue

            val slot = pads.indexOfFirst { it == null }
            if (slot < 0) return

            val pad = Gamepad(device)
            if (!pad.start()) continue

            pads[slot] = pad
            publish(slot, pad)
            connects.incrementAndGet()
        }
    }

    private fun summarize(): String {
        val labels = mutableListOf<String>()

        for (i in pads.indices) {
            val pad = pads[i] ?: continue
            labels.add("p${i + 1} ${pad.kind} ${id(pad)} port ${pad.port}")
        }

        if (labels.isEmpty()) return "no gamepad (${USBService.all().size} usb devices; run 'usb')"
        return labels.joinToString(", ")
    }

    private fun publish(index: Int, pad: Gamepad) {
        var state = CONNECTED_BIT
        for (button in 0 until Pad.COUNT) {
            if (pad.isDown(button)) state = state or (1L shl button)
        }
        state = state or ((pad.axis(Pad.AXIS_LT).toLong() and 0xFFFF) shl 32)
        state = state or ((pad.axis(Pad.AXIS_RT).toLong() and 0xFFFF) shl 48)
        states[index] = state

        var stick = pad.axis(Pad.AXIS_LX).toLong() and 0xFFFF
        stick = stick or ((pad.axis(Pad.AXIS_LY).toLong() and 0xFFFF) shl 16)
        stick = stick or ((pad.axis(Pad.AXIS_RX).toLong() and 0xFFFF) shl 32)
        stick = stick or ((pad.axis(Pad.AXIS_RY).toLong() and 0xFFFF) shl 48)
        sticks[index] = stick
    }

    fun pump(): GamepadEvent? {
        if (!threaded) UsbLock.withLock { service() }

        val down = disconnects.value
        if (down != seenDisconnects) {
            seenDisconnects = down
            return GamepadEvent.Disconnected
        }

        val up = connects.value
        if (up != seenConnects) {
            seenConnects = up
            return GamepadEvent.Connected
        }

        return null
    }

    fun poll() {
        if (!threaded) UsbLock.withLock { service() }
    }

    fun refresh() {
        poll()
    }

    fun rescan(): Boolean {
        UsbLock.withLock {
            for (i in pads.indices) {
                pads[i] = null
                states[i] = 0L
                sticks[i] = 0L
            }
            players.value = 0

            USBService.rescan()
            claim()
        }

        return available
    }

    fun connected(player: Int): Boolean {
        if (player < 0 || player >= Pad.MAX_PLAYERS) return false
        return states[player] and CONNECTED_BIT != 0L
    }

    fun isDown(player: Int, button: Int): Boolean {
        if (player < 0 || player >= Pad.MAX_PLAYERS) return false
        if (button < 0 || button >= Pad.COUNT) return false
        return (states[player] shr button) and 1L != 0L
    }

    fun axis(player: Int, axis: Int): Int {
        if (player < 0 || player >= Pad.MAX_PLAYERS) return 0

        return when (axis) {
            Pad.AXIS_LX -> unpack(sticks[player], 0)
            Pad.AXIS_LY -> unpack(sticks[player], 16)
            Pad.AXIS_RX -> unpack(sticks[player], 32)
            Pad.AXIS_RY -> unpack(sticks[player], 48)
            Pad.AXIS_LT -> unpack(states[player], 32)
            Pad.AXIS_RT -> unpack(states[player], 48)
            else -> 0
        }
    }

    private fun unpack(word: Long, shift: Int): Int = ((word shr shift) and 0xFFFF).toShort().toInt()

    fun summary(): String {
        if (!available) return status
        if (live) return status

        return "$status - no reports yet (controller powered off, or not paired to the dongle)"
    }

    fun descriptor(player: Int = 0): String = pads.getOrNull(player)?.descriptor ?: status

    private fun id(pad: Gamepad): String =
        "${pad.vendorId.toString(16).padStart(4, '0')}:${pad.productId.toString(16).padStart(4, '0')}"

    private const val VENDOR_CLASS = 0xFF
    private const val XINPUT_SUBCLASS = 0x5D
    private const val HID_CLASS = 0x03

    private const val CHECK_MS = 250UL

    private const val CONNECTED_BIT = 1L shl 62
}
