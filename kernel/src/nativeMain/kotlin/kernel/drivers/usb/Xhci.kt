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

    private var portMajor = IntArray(0)
    private var portSpeeds = Array(0) { IntArray(SPEED_IDS) }

    var contextBytes = 32
        private set

    private var contextArray: Region? = null
    private var scratchpad: Region? = null

    private var commands: XhciRing? = null
    private var events: XhciEventRing? = null

    private val parkedTrb = ULongArray(PARKED_EVENTS)
    private val parkedStatus = UIntArray(PARKED_EVENTS)
    private val parkedControl = UIntArray(PARKED_EVENTS)
    private val parkedType = IntArray(PARKED_EVENTS)
    private var parkedCount = 0

    var status: String = "not initialized"
        private set

    var lastEventControl: UInt = 0u
        private set

    val ready: Boolean get() = commands != null

    fun lastSlot(): Int = ((lastEventControl shr 24) and 0xFFu).toInt()

    fun portSpeed(port: Int): Int {
        val psi = rawPortSpeed(port)
        if (port < 1 || port >= portSpeeds.size) return SPEED_FULL
        if (psi < 0 || psi >= SPEED_IDS) return SPEED_FULL

        val speed = portSpeeds[port][psi]
        if (speed != 0) return speed

        return if (protocolOf(port) >= 3) SPEED_SUPER else SPEED_FULL
    }

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

        portMajor = IntArray(ports + 1)
        portSpeeds = Array(ports + 1) { defaultSpeeds() }
        parseProtocols(capability1)

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

    private fun parseProtocols(capability1: UInt) {
        var offset = ((capability1 shr 16) and 0xFFFFu).toInt() * 4
        var guard = 0

        while (offset != 0 && guard < 64) {
            guard++

            val header = RawMemory.read32(base + offset.toULong())
            val id = (header and 0xFFu).toInt()

            if (id == CAPABILITY_PROTOCOL) {
                val major = ((header shr 24) and 0xFFu).toInt()
                val layout = RawMemory.read32(base + offset.toULong() + 8UL)

                val first = (layout and 0xFFu).toInt()
                val count = ((layout shr 8) and 0xFFu).toInt()
                val defined = ((layout shr 28) and 0xFu).toInt()

                val table = speedTable(offset, defined)

                for (i in 0 until count) {
                    val port = first + i
                    if (port !in 1..ports) continue

                    portMajor[port] = major
                    portSpeeds[port] = table
                }
            }

            val next = ((header shr 8) and 0xFFu).toInt() * 4
            if (next == 0) return
            offset += next
        }
    }

    private fun speedTable(offset: Int, defined: Int): IntArray {
        if (defined == 0) return defaultSpeeds()

        val table = IntArray(SPEED_IDS)

        for (i in 0 until defined) {
            val entry = RawMemory.read32(base + offset.toULong() + 0x10UL + (i * 4).toULong())

            val id = (entry and 0xFu).toInt()
            val exponent = ((entry shr 4) and 0x3u).toInt()
            val mantissa = ((entry shr 16) and 0xFFFFu).toLong()

            var rate = mantissa
            for (step in 0 until exponent) rate *= 1000L

            table[id] = speedOf(rate)
        }

        return table
    }

    private fun speedOf(rate: Long): Int = when {
        rate >= 8_000_000_000L -> SPEED_SUPER_PLUS
        rate >= 4_000_000_000L -> SPEED_SUPER
        rate >= 400_000_000L -> SPEED_HIGH
        rate >= 10_000_000L -> SPEED_FULL
        rate > 0L -> SPEED_LOW
        else -> 0
    }

    private fun defaultSpeeds(): IntArray {
        val table = IntArray(SPEED_IDS)

        table[1] = SPEED_FULL
        table[2] = SPEED_LOW
        table[3] = SPEED_HIGH
        table[4] = SPEED_SUPER

        return table
    }

    fun protocolOf(port: Int): Int {
        if (port < 1 || port >= portMajor.size) return 0
        return portMajor[port]
    }

    fun rawPortSpeed(port: Int): Int = ((read32(portRegister(port)) shr 10) and 0xFu).toInt()

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

        powerPorts()
        Clock.sleepMillis(CONNECT_SETTLE_MS)
    }

    fun enableInterrupts(vector: Int, apicId: UInt): Boolean {
        if (!ready) return false
        if (!device.enableMessageInterrupt(vector, apicId)) return false

        val interrupter = runtime + INTERRUPTER
        write32(interrupter + IMOD, IMOD_INTERVAL)
        write32(interrupter + IMAN, read32(interrupter + IMAN) or IMAN_ENABLE or IMAN_PENDING)
        write32(operational + USBCMD, read32(operational + USBCMD) or INTERRUPTS)

        return true
    }

    fun acknowledgeInterrupt() {
        val ring = events ?: return

        val interrupter = runtime + INTERRUPTER
        val iman = read32(interrupter + IMAN)
        if (iman and IMAN_PENDING != 0u) write32(interrupter + IMAN, iman)

        RawMemory.write64(interrupter + ERDP, ring.dequeuePointer() or EVENT_BUSY)
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

    fun portStatus(port: Int): UInt = read32(portRegister(port))

    fun connectChanged(port: Int): Boolean =
        read32(portRegister(port)) and PORT_CONNECT_CHANGE != 0u

    fun powerPorts() {
        for (port in 1..ports) {
            val register = portRegister(port)
            val value = read32(register)

            if (value and PORT_POWER == 0u) {
                write32(register, (value and PORT_WRITE_MASK) or PORT_POWER)
            }
        }

        Clock.sleepMillis(POWER_SETTLE_MS)
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
        val deadline = Clock.uptimeMillis() + EVENT_MS

        while (true) {
            val status = pump(trb, type)
            if (status != null) return status
            if (Clock.uptimeMillis() >= deadline) return null
        }
    }

    fun tryEvent(trb: ULong, type: Int): UInt? = pump(trb, type)

    private fun pump(trb: ULong, type: Int): UInt? {
        val parked = takeParked(trb, type)
        if (parked != null) return parked

        val ring = events ?: return null

        while (ring.pending()) {
            val parameter = ring.parameter()
            val status = ring.status()
            val control = ring.control()

            ring.advance()
            RawMemory.write64(runtime + INTERRUPTER + ERDP, ring.dequeuePointer() or EVENT_BUSY)

            val eventType = ((control shr 10) and 0x3Fu).toInt()

            if (eventType == type && (trb == 0UL || parameter == trb)) {
                lastEventControl = control
                return status
            }

            park(parameter, status, control, eventType)
        }

        return null
    }

    private fun park(parameter: ULong, status: UInt, control: UInt, type: Int) {
        if (type != TRANSFER_EVENT && type != COMMAND_COMPLETION) return

        if (parkedCount == PARKED_EVENTS) {
            for (i in 0 until PARKED_EVENTS - 1) {
                parkedTrb[i] = parkedTrb[i + 1]
                parkedStatus[i] = parkedStatus[i + 1]
                parkedControl[i] = parkedControl[i + 1]
                parkedType[i] = parkedType[i + 1]
            }
            parkedCount--
        }

        parkedTrb[parkedCount] = parameter
        parkedStatus[parkedCount] = status
        parkedControl[parkedCount] = control
        parkedType[parkedCount] = type
        parkedCount++
    }

    private fun takeParked(trb: ULong, type: Int): UInt? {
        for (i in 0 until parkedCount) {
            if (parkedType[i] != type) continue
            if (trb != 0UL && parkedTrb[i] != trb) continue

            val status = parkedStatus[i]
            lastEventControl = parkedControl[i]

            for (j in i until parkedCount - 1) {
                parkedTrb[j] = parkedTrb[j + 1]
                parkedStatus[j] = parkedStatus[j + 1]
                parkedControl[j] = parkedControl[j + 1]
                parkedType[j] = parkedType[j + 1]
            }
            parkedCount--

            return status
        }

        return null
    }

    fun drainEvents() {
        val ring = events ?: return

        parkedCount = 0

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
        const val TRB_RESET_ENDPOINT = 14
        const val TRB_SET_TR_DEQUEUE = 16

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
        private const val IMAN = 0x00UL
        private const val IMOD = 0x04UL
        private const val ERSTSZ = 0x08UL
        private const val ERSTBA = 0x10UL
        private const val ERDP = 0x18UL

        private const val IMAN_PENDING = 0x1u
        private const val IMAN_ENABLE = 0x2u
        private const val IMOD_INTERVAL = 4000u

        private const val RUN = 0x1u
        private const val RESET = 0x2u
        private const val INTERRUPTS = 0x4u
        private const val HALTED = 0x1u
        private const val NOT_READY = 0x800u

        private const val PORT_CONNECTED = 0x1u
        private const val PORT_ENABLED = 0x2u
        private const val PORT_RESET = 0x10u
        private const val PORT_RESET_CHANGE = 0x200000u
        private const val PORT_CONNECT_CHANGE = 0x20000u
        private const val PORT_CHANGE_MASK = 0x00FE0000u
        private const val PORT_POWER = 0x200u
        private const val PORT_WRITE_MASK = 0x0E00C3E0u
        private const val POWER_SETTLE_MS = 100UL
        private const val CONNECT_SETTLE_MS = 150UL

        const val SPEED_FULL = 1
        const val SPEED_LOW = 2
        const val SPEED_HIGH = 3
        const val SPEED_SUPER = 4
        const val SPEED_SUPER_PLUS = 5

        private const val SPEED_IDS = 16

        private const val CAPABILITY_LEGACY = 1
        private const val CAPABILITY_PROTOCOL = 2
        private const val BIOS_OWNED = 0x10000u
        private const val OS_OWNED = 0x1000000u

        private const val EVENT_BUSY = 0x8UL

        private const val RING_ENTRIES = 256
        private const val PARKED_EVENTS = 16
        private const val TIMEOUT_MS = 500UL
        private const val HANDOFF_MS = 1000UL
        private const val RESET_MS = 500UL
        private const val EVENT_MS = 1000UL
    }
}
