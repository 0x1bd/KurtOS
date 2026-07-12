package kernel.drivers.usb

import hal.BootInfo
import hal.Clock
import hal.RawMemory
import kernel.drivers.PciDevice
import kernel.memory.Mmio
import kernel.memory.PageAllocator
import kernel.memory.Region

class XhciPort(val number: Int, val speed: Int, val connected: Boolean, val enabled: Boolean)

class Xhci(private val device: PciDevice) {
    private var base: ULong = 0UL
    private var operational: ULong = 0UL
    private var runtime: ULong = 0UL
    private var doorbells: ULong = 0UL

    private var slots = 0
    private var ports = 0

    var contextBytes = 32
        private set

    private var contextArray: Region? = null
    private var scratchpad: Region? = null

    private var commands: XhciRing? = null
    private var events: XhciEventRing? = null

    var status: String = "not initialized"
        private set

    var lastEventControl: UInt = 0u
        private set

    val ready: Boolean get() = commands != null

    fun lastSlot(): Int = ((lastEventControl shr 24) and 0xFFu).toInt()

    fun portSpeed(port: Int): Int = ((read32(portRegister(port)) shr 10) and 0xFu).toInt()

    fun initialize(): Boolean {
        val bar = device.bar(0)
        if (bar == 0UL) {
            status = "no mmio bar"
            return false
        }

        base = Mmio.map(bar, REGISTER_BYTES)
        if (base == 0UL) {
            status = "cannot map bar 0x${bar.toString(16)}"
            return false
        }

        device.enableBusMaster()

        val capabilityLength = RawMemory.read8(base).toInt()
        val structural1 = RawMemory.read32(base + HCSPARAMS1)
        val capability1 = RawMemory.read32(base + HCCPARAMS1)

        if (capabilityLength == 0 || structural1 == 0xFFFFFFFFu) {
            status = "bar 0x${bar.toString(16)} does not look like xhci"
            return false
        }

        slots = (structural1 and 0xFFu).toInt()
        ports = ((structural1 shr 24) and 0xFFu).toInt()
        contextBytes = if (capability1 and 0x4u != 0u) 64 else 32

        operational = base + capabilityLength.toULong()
        runtime = base + (RawMemory.read32(base + RTSOFF) and 0xFFFFFFE0u).toULong()
        doorbells = base + (RawMemory.read32(base + DBOFF) and 0xFFFFFFFCu).toULong()

        takeOwnership(capability1)

        if (!reset()) {
            status = "controller reset timed out"
            return false
        }

        if (!allocate()) {
            status = "no memory for xhci structures"
            return false
        }

        start()

        status = "xhci $slots slots, $ports ports, ${contextBytes}-byte contexts"
        return true
    }

    private fun takeOwnership(capability1: UInt) {
        var offset = ((capability1 shr 16) and 0xFFFFu).toInt() * 4
        if (offset == 0) return

        var guard = 0
        while (offset != 0 && guard < 64) {
            guard++

            val header = RawMemory.read32(base + offset.toULong())
            val id = (header and 0xFFu).toInt()

            if (id == CAPABILITY_LEGACY) {
                val legacy = base + offset.toULong()
                val value = RawMemory.read32(legacy)

                if (value and BIOS_OWNED != 0u) {
                    RawMemory.write32(legacy, value or OS_OWNED)

                    val deadline = Clock.uptimeMillis() + HANDOFF_MS
                    while (Clock.uptimeMillis() < deadline) {
                        if (RawMemory.read32(legacy) and BIOS_OWNED == 0u) break
                    }
                }

                RawMemory.write32(legacy, (RawMemory.read32(legacy) and BIOS_OWNED.inv()) or OS_OWNED)
                RawMemory.write32(legacy + 4UL, 0u)
                return
            }

            val next = ((header shr 8) and 0xFFu).toInt() * 4
            if (next == 0) return
            offset += next
        }
    }

    private fun reset(): Boolean {
        write32(operational + USBCMD, read32(operational + USBCMD) and RUN.inv())
        if (!waitFor { read32(operational + USBSTS) and HALTED != 0u }) return false

        write32(operational + USBCMD, read32(operational + USBCMD) or RESET)
        if (!waitFor { read32(operational + USBCMD) and RESET == 0u }) return false
        if (!waitFor { read32(operational + USBSTS) and NOT_READY == 0u }) return false

        return true
    }

    private fun allocate(): Boolean {
        val array = PageAllocator.allocateBytes(((slots + 1) * 8).toUInt()) ?: return false
        array.zero()
        contextArray = array

        if (!allocateScratchpad(array)) return false

        val command = XhciRing.allocate(RING_ENTRIES, link = true) ?: return false
        val event = XhciEventRing.allocate(RING_ENTRIES) ?: return false

        commands = command
        events = event

        write32(operational + CONFIG, slots.toUInt())
        RawMemory.write64(operational + DCBAAP, BootInfo.toPhysical(array.address))
        RawMemory.write64(operational + CRCR, command.physical or 1UL)

        val table = PageAllocator.allocateBytes(16u) ?: return false
        table.zero()
        RawMemory.write64(table.address, event.physical)
        RawMemory.write32(table.address + 8UL, RING_ENTRIES.toUInt())

        val interrupter = runtime + INTERRUPTER
        write32(interrupter + ERSTSZ, 1u)
        RawMemory.write64(interrupter + ERDP, event.physical)
        RawMemory.write64(interrupter + ERSTBA, BootInfo.toPhysical(table.address))

        return true
    }

    private fun allocateScratchpad(array: Region): Boolean {
        val structural2 = RawMemory.read32(base + HCSPARAMS2)
        val high = ((structural2 shr 21) and 0x1Fu).toInt()
        val low = ((structural2 shr 27) and 0x1Fu).toInt()
        val count = (high shl 5) or low

        if (count == 0) return true

        val pointers = PageAllocator.allocateBytes((count * 8).toUInt()) ?: return false
        pointers.zero()

        for (i in 0 until count) {
            val page = PageAllocator.allocatePages(1) ?: return false
            page.zero()
            RawMemory.write64(pointers.address + (i * 8).toULong(), BootInfo.toPhysical(page.address))
        }

        scratchpad = pointers
        RawMemory.write64(array.address, BootInfo.toPhysical(pointers.address))
        return true
    }

    private fun start() {
        write32(operational + USBCMD, read32(operational + USBCMD) or RUN)
        waitFor { read32(operational + USBSTS) and HALTED == 0u }
    }

    fun portRegister(port: Int): ULong = operational + PORTSC + ((port - 1) * 0x10).toULong()

    fun scanPorts(): List<XhciPort> {
        val result = mutableListOf<XhciPort>()

        for (port in 1..ports) {
            val value = read32(portRegister(port))

            result.add(
                XhciPort(
                    number = port,
                    speed = ((value shr 10) and 0xFu).toInt(),
                    connected = value and PORT_CONNECTED != 0u,
                    enabled = value and PORT_ENABLED != 0u,
                )
            )
        }

        return result
    }

    fun resetPort(port: Int): Boolean {
        val register = portRegister(port)
        val value = read32(register) and PORT_WRITE_MASK

        write32(register, value or PORT_RESET)

        val deadline = Clock.uptimeMillis() + RESET_MS
        while (Clock.uptimeMillis() < deadline) {
            val current = read32(register)
            if (current and PORT_RESET_CHANGE != 0u) {
                write32(register, (current and PORT_WRITE_MASK) or PORT_RESET_CHANGE)
                return read32(register) and PORT_ENABLED != 0u
            }
        }

        return false
    }

    fun clearPortChanges(port: Int) {
        val register = portRegister(port)
        val value = read32(register)
        write32(register, (value and PORT_WRITE_MASK) or (value and PORT_CHANGE_MASK))
    }

    fun ringDoorbell(slot: Int, target: Int) {
        RawMemory.write32(doorbells + (slot * 4).toULong(), target.toUInt())
    }

    fun submitCommand(parameter: ULong, status: UInt, control: UInt): UInt? {
        val ring = commands ?: return null

        val trb = ring.enqueue(parameter, status, control)
        ringDoorbell(0, 0)

        return waitForEvent(trb, COMMAND_COMPLETION)
    }

    fun waitForEvent(trb: ULong, type: Int): UInt? {
        val ring = events ?: return null
        val deadline = Clock.uptimeMillis() + EVENT_MS

        while (Clock.uptimeMillis() < deadline) {
            if (!ring.pending()) continue

            val parameter = ring.parameter()
            val status = ring.status()
            val control = ring.control()

            val eventType = ((control shr 10) and 0x3Fu).toInt()

            ring.advance()
            RawMemory.write64(runtime + INTERRUPTER + ERDP, ring.dequeuePointer() or EVENT_BUSY)

            if (eventType == type && (trb == 0UL || parameter == trb)) {
                lastEventControl = control
                return status
            }
        }

        return null
    }

    fun drainEvents() {
        val ring = events ?: return

        while (ring.pending()) {
            ring.advance()
            RawMemory.write64(runtime + INTERRUPTER + ERDP, ring.dequeuePointer() or EVENT_BUSY)
        }
    }

    private fun waitFor(condition: () -> Boolean): Boolean {
        val deadline = Clock.uptimeMillis() + TIMEOUT_MS

        while (Clock.uptimeMillis() < deadline) {
            if (condition()) return true
        }

        return condition()
    }

    fun contextArrayAddress(): ULong = contextArray?.address ?: 0UL

    private fun read32(address: ULong): UInt = RawMemory.read32(address)
    private fun write32(address: ULong, value: UInt) = RawMemory.write32(address, value)

    companion object {
        const val COMMAND_COMPLETION = 33
        const val TRANSFER_EVENT = 32

        const val TRB_ENABLE_SLOT = 9
        const val TRB_ADDRESS_DEVICE = 11
        const val TRB_CONFIGURE_ENDPOINT = 12

        private const val REGISTER_BYTES = 0x2000UL

        private const val HCSPARAMS1 = 0x04UL
        private const val HCSPARAMS2 = 0x08UL
        private const val HCCPARAMS1 = 0x10UL
        private const val DBOFF = 0x14UL
        private const val RTSOFF = 0x18UL

        private const val USBCMD = 0x00UL
        private const val USBSTS = 0x04UL
        private const val CRCR = 0x18UL
        private const val DCBAAP = 0x30UL
        private const val CONFIG = 0x38UL
        private const val PORTSC = 0x400UL

        private const val INTERRUPTER = 0x20UL
        private const val ERSTSZ = 0x08UL
        private const val ERSTBA = 0x10UL
        private const val ERDP = 0x18UL

        private const val RUN = 0x1u
        private const val RESET = 0x2u
        private const val HALTED = 0x1u
        private const val NOT_READY = 0x800u

        private const val PORT_CONNECTED = 0x1u
        private const val PORT_ENABLED = 0x2u
        private const val PORT_RESET = 0x10u
        private const val PORT_RESET_CHANGE = 0x200000u
        private const val PORT_CHANGE_MASK = 0x00FE0000u
        private const val PORT_WRITE_MASK = 0x0E00C3E0u

        private const val CAPABILITY_LEGACY = 1
        private const val BIOS_OWNED = 0x10000u
        private const val OS_OWNED = 0x1000000u

        private const val EVENT_BUSY = 0x8UL

        private const val RING_ENTRIES = 256
        private const val TIMEOUT_MS = 500UL
        private const val HANDOFF_MS = 1000UL
        private const val RESET_MS = 500UL
        private const val EVENT_MS = 1000UL
    }
}
