package kernel.drivers.usb

import hal.BootInfo
import hal.RawMemory
import kernel.memory.PageAllocator
import kernel.memory.Region

class USBEndpoint(
    val address: Int,
    val attributes: Int,
    val maxPacket: Int,
    val interval: Int,
    val owner: Int,
) {
    val number: Int get() = address and 0x0F
    val isInput: Boolean get() = address and 0x80 != 0
    val isInterrupt: Boolean get() = attributes and 0x03 == 0x03
    val isBulk: Boolean get() = attributes and 0x03 == 0x02
    val dci: Int get() = number * 2 + if (isInput) 1 else 0
}

class USBDevice(
    val controller: Xhci,
    val slot: Int,
    val port: Int,
    val speed: Int,
    val psi: Int,
) {
    var vendorId = 0
        private set
    var productId = 0
        private set

    var interfaceClass = 0
        private set
    var interfaceSubclass = 0
        private set
    var interfaceProtocol = 0
        private set
    var interfaceNumber = 0
        private set

    var configuration = 1
        private set

    var input: USBEndpoint? = null
        private set

    var output: USBEndpoint? = null
        private set

    var bulkIn: USBEndpoint? = null
        private set

    var bulkOut: USBEndpoint? = null
        private set

    val storage: Boolean get() = bulkIn != null && bulkOut != null

    val interfaces = mutableListOf<String>()
    val endpoints = mutableListOf<USBEndpoint>()

    var lastCompletion = 0
        private set

    var configureError = ""
        private set

    var configured = false
        private set

    var configBytes: ByteArray = ByteArray(0)
        private set

    private val siblings = mutableListOf<IntArray>()

    private var inputContext: Region? = null
    private var deviceContext: Region? = null
    private var buffer: Region? = null
    private var inputBuffer: Region? = null

    private var controlRing: XhciRing? = null
    private var inputRing: XhciRing? = null
    private var outputRing: XhciRing? = null
    private var outputBuffer: Region? = null

    private var bulkInRing: XhciRing? = null
    private var bulkOutRing: XhciRing? = null
    private var bulkBuffer: Region? = null
    private var bulkVirtual: ULong = 0UL
    private var bulkPhysical: ULong = 0UL

    private var maxPacket = 8

    fun address(): Boolean {
        val inputRegion = PageAllocator.allocateBytes((controller.contextBytes * 33).toUInt()) ?: return false
        val deviceRegion = PageAllocator.allocateBytes((controller.contextBytes * 32).toUInt()) ?: return false
        val data = PageAllocator.allocateBytes(1024u) ?: return false
        val ring = XhciRing.allocate(RING_ENTRIES, link = true) ?: return false

        inputRegion.zero()
        deviceRegion.zero()
        data.zero()

        inputContext = inputRegion
        deviceContext = deviceRegion
        buffer = data
        controlRing = ring

        maxPacket = when {
            speed >= SPEED_SUPER -> 512
            speed == SPEED_HIGH -> 64
            else -> 8
        }

        RawMemory.write64(
            controller.contextArrayAddress() + (slot * 8).toULong(),
            BootInfo.toPhysical(deviceRegion.address),
        )

        writeInputControl(0x3u)
        writeSlotContext(1)
        writeControlEndpoint(ring)

        val result = controller.submitCommand(
            BootInfo.toPhysical(inputRegion.address),
            0u,
            ((Xhci.TRB_ADDRESS_DEVICE shl 10).toUInt()) or (slot.toUInt() shl 24),
        ) ?: return false

        if (completion(result) != SUCCESS) return false

        val header = readDescriptor(DESCRIPTOR_DEVICE, 0, 8) ?: return false
        val reported = header[7].toInt() and 0xFF

        if (speed == SPEED_FULL && reported != maxPacket && reported > 0) {
            maxPacket = reported
            if (!evaluateContext()) return false
        }

        val descriptor = readDescriptor(DESCRIPTOR_DEVICE, 0, 18) ?: return false
        vendorId = word(descriptor, 8)
        productId = word(descriptor, 10)

        return readConfiguration()
    }

    private fun readConfiguration(): Boolean {
        val header = readDescriptor(DESCRIPTOR_CONFIGURATION, 0, 9) ?: return false
        val total = word(header, 2)
        if (total <= 0 || total > 1024) return false

        val full = readDescriptor(DESCRIPTOR_CONFIGURATION, 0, total) ?: return false
        configuration = full[5].toInt() and 0xFF
        configBytes = full

        val settings = mutableListOf<IntArray>()

        var offset = 0
        var currentInterface = -1
        var currentClass = -1

        while (offset + 2 <= full.size) {
            val length = full[offset].toInt() and 0xFF
            val type = full[offset + 1].toInt() and 0xFF
            if (length == 0) break

            if (type == DESCRIPTOR_INTERFACE && offset + 9 <= full.size) {
                val index = full[offset + 2].toInt() and 0xFF
                val alternate = full[offset + 3].toInt() and 0xFF

                val klass = full[offset + 5].toInt() and 0xFF
                val subclass = full[offset + 6].toInt() and 0xFF
                val protocol = full[offset + 7].toInt() and 0xFF

                if (alternate == 0) {
                    currentInterface = index
                    currentClass = klass
                    settings.add(intArrayOf(index, klass, subclass, protocol))
                    siblings.add(intArrayOf(index, klass, subclass, protocol, 0))
                    interfaces.add(
                        "if $index class ${hex2(klass)}/${hex2(subclass)}/${hex2(protocol)}"
                    )
                } else {
                    currentInterface = -1
                    currentClass = -1
                }
            }

            if (type == DESCRIPTOR_HID && offset + 9 <= full.size &&
                currentInterface >= 0 && currentClass == HID_CLASS
            ) {
                siblings.lastOrNull()?.set(4, word(full, offset + 7))
            }

            if (type == DESCRIPTOR_ENDPOINT && offset + 7 <= full.size && currentInterface >= 0) {
                endpoints.add(
                    USBEndpoint(
                        address = full[offset + 2].toInt() and 0xFF,
                        attributes = full[offset + 3].toInt() and 0xFF,
                        maxPacket = word(full, offset + 4) and 0x7FF,
                        interval = full[offset + 6].toInt() and 0xFF,
                        owner = currentInterface,
                    )
                )
            }

            offset += length
        }

        select(settings)
        return true
    }

    private fun select(settings: List<IntArray>) {
        if (selectStorage(settings)) return

        var chosen = pick(settings) { it[1] == VENDOR_CLASS && it[2] == XINPUT_SUBCLASS }
            ?: pick(settings) { it[1] == HID_CLASS }

        if (chosen == null) {
            chosen = endpoints.firstOrNull { it.isInterrupt && it.isInput }
        }

        val endpoint = chosen ?: return

        input = endpoint
        output = endpoints.firstOrNull {
            it.isInterrupt && !it.isInput && it.owner == endpoint.owner
        }

        val setting = settings.firstOrNull { it[0] == endpoint.owner } ?: return

        interfaceNumber = setting[0]
        interfaceClass = setting[1]
        interfaceSubclass = setting[2]
        interfaceProtocol = setting[3]
    }

    private fun selectStorage(settings: List<IntArray>): Boolean {
        for (setting in settings) {
            if (setting[1] != STORAGE_CLASS) continue
            if (setting[3] != BULK_ONLY_PROTOCOL) continue

            val source = endpoints.firstOrNull { it.isBulk && it.isInput && it.owner == setting[0] } ?: continue
            val sink = endpoints.firstOrNull { it.isBulk && !it.isInput && it.owner == setting[0] } ?: continue

            bulkIn = source
            bulkOut = sink

            interfaceNumber = setting[0]
            interfaceClass = setting[1]
            interfaceSubclass = setting[2]
            interfaceProtocol = setting[3]

            return true
        }

        return false
    }

    private fun pick(settings: List<IntArray>, match: (IntArray) -> Boolean): USBEndpoint? {
        for (setting in settings) {
            if (!match(setting)) continue

            val endpoint = endpoints.firstOrNull {
                it.isInterrupt && it.isInput && it.owner == setting[0]
            }
            if (endpoint != null) return endpoint
        }

        return null
    }

    fun configure(): Boolean {
        if (storage) return configureStorage()

        val endpoint = input ?: return fail("no interrupt in endpoint")

        val ring = XhciRing.allocate(RING_ENTRIES, link = true) ?: return fail("no memory for transfer ring")
        inputRing = ring

        val reports = PageAllocator.allocateBytes(256u) ?: return fail("no memory for report buffer")
        reports.zero()
        inputBuffer = reports

        val region = inputContext ?: return false
        region.zero()

        val sink = output
        var add = 0x1u or (1u shl endpoint.dci)
        var last = endpoint.dci

        if (sink != null) {
            val sinkRing = XhciRing.allocate(RING_ENTRIES, link = true) ?: return false
            val sinkBuffer = PageAllocator.allocateBytes(64u) ?: return false
            sinkBuffer.zero()

            outputRing = sinkRing
            outputBuffer = sinkBuffer

            add = add or (1u shl sink.dci)
            if (sink.dci > last) last = sink.dci
        }

        writeInputControl(add)
        writeSlotContext(last)
        writeInterruptEndpoint(endpoint, ring)

        val sinkRing = outputRing
        if (sink != null && sinkRing != null) writeInterruptEndpoint(sink, sinkRing)

        val result = controller.submitCommand(
            BootInfo.toPhysical(region.address),
            0u,
            ((Xhci.TRB_CONFIGURE_ENDPOINT shl 10).toUInt()) or (slot.toUInt() shl 24),
        ) ?: return fail("configure endpoint timed out")

        val code = completion(result)
        if (code != SUCCESS) return fail("configure endpoint code $code")

        if (control(0x00, REQUEST_SET_CONFIGURATION, configuration, 0, 0) == null) {
            return fail("set configuration failed")
        }

        if (interfaceClass == HID_CLASS) {
            control(0x21, REQUEST_SET_IDLE, 0, interfaceNumber, 0)
        }

        configured = true
        return true
    }

    private fun fail(reason: String): Boolean {
        configureError = reason
        return false
    }

    private fun configureStorage(): Boolean {
        val source = bulkIn ?: return fail("no bulk in endpoint")
        val sink = bulkOut ?: return fail("no bulk out endpoint")

        val sourceRing = XhciRing.allocate(RING_ENTRIES, link = true) ?: return fail("no memory for bulk in ring")
        val sinkRing = XhciRing.allocate(RING_ENTRIES, link = true) ?: return fail("no memory for bulk out ring")

        val dma = PageAllocator.allocatePages(BULK_PAGES) ?: return fail("no memory for bulk buffer")
        dma.zero()

        val physical = BootInfo.toPhysical(dma.address)
        val aligned = (physical + BULK_ALIGN - 1UL) and (BULK_ALIGN - 1UL).inv()

        bulkInRing = sourceRing
        bulkOutRing = sinkRing
        bulkBuffer = dma
        bulkPhysical = aligned
        bulkVirtual = dma.address + (aligned - physical)

        val region = inputContext ?: return false
        region.zero()

        val add = 0x1u or (1u shl source.dci) or (1u shl sink.dci)
        val last = if (source.dci > sink.dci) source.dci else sink.dci

        writeInputControl(add)
        writeSlotContext(last)
        writeBulkEndpoint(source, sourceRing)
        writeBulkEndpoint(sink, sinkRing)

        val result = controller.submitCommand(
            BootInfo.toPhysical(region.address),
            0u,
            ((Xhci.TRB_CONFIGURE_ENDPOINT shl 10).toUInt()) or (slot.toUInt() shl 24),
        ) ?: return fail("configure endpoint timed out")

        val code = completion(result)
        if (code != SUCCESS) return fail("configure endpoint code $code")

        if (control(0x00, REQUEST_SET_CONFIGURATION, configuration, 0, 0) == null) {
            return fail("set configuration failed")
        }

        configured = true
        return true
    }

    val bulkTransferBytes: Int get() = BULK_BUFFER_BYTES

    fun bulkSend(source: ByteArray, offset: Int, length: Int): Int {
        val endpoint = bulkOut ?: return -1
        val ring = bulkOutRing ?: return -1
        if (length <= 0 || length > BULK_BUFFER_BYTES) return -1

        RawMemory.copyIn(bulkVirtual, source, offset, length)
        return bulkTransfer(endpoint, ring, length)
    }

    fun bulkReceive(target: ByteArray, offset: Int, length: Int): Int {
        val endpoint = bulkIn ?: return -1
        val ring = bulkInRing ?: return -1
        if (length <= 0 || length > BULK_BUFFER_BYTES) return -1

        val moved = bulkTransfer(endpoint, ring, length)
        if (moved > 0) RawMemory.copyOut(bulkVirtual, target, offset, moved)

        return moved
    }

    private fun bulkTransfer(endpoint: USBEndpoint, ring: XhciRing, length: Int): Int {
        val trb = ring.enqueue(
            bulkPhysical,
            length.toUInt(),
            (TRB_NORMAL shl 10).toUInt() or INTERRUPT_ON_COMPLETION or INTERRUPT_ON_SHORT,
        )

        controller.ringDoorbell(slot, endpoint.dci)

        val status = controller.waitForEvent(trb, Xhci.TRANSFER_EVENT)
        if (status == null) {
            lastCompletion = -1
            return -1
        }

        val code = completion(status)
        lastCompletion = code

        if (code != SUCCESS && code != SHORT_PACKET) {
            recover(endpoint, ring)
            if (code == STALL) clearHalt(endpoint)
            return -1
        }

        val remaining = (status and 0xFFFFFFu).toInt()
        return length - remaining
    }

    fun clearHalt(endpoint: USBEndpoint): Boolean =
        control(0x02, REQUEST_CLEAR_FEATURE, FEATURE_ENDPOINT_HALT, endpoint.address, 0) != null

    fun resetStorage(): Boolean {
        if (control(0x21, REQUEST_STORAGE_RESET, 0, interfaceNumber, 0) == null) return false

        val source = bulkIn
        val sink = bulkOut

        val sourceRing = bulkInRing
        val sinkRing = bulkOutRing

        if (source != null && sourceRing != null) {
            recover(source, sourceRing)
            clearHalt(source)
        }

        if (sink != null && sinkRing != null) {
            recover(sink, sinkRing)
            clearHalt(sink)
        }

        return true
    }

    fun maxLun(): Int {
        val result = control(0xA1, REQUEST_MAX_LUN, 0, interfaceNumber, 1) ?: return 0
        if (result.isEmpty()) return 0
        return result[0].toInt() and 0xFF
    }

    fun xinputStart(): ByteArray? = control(0xC1, REQUEST_XINPUT, 0x0100, 0, 20)

    fun hidReportDescriptor(): ByteArray? {
        if (interfaceClass != HID_CLASS) return null

        val length = siblings.firstOrNull { it[0] == interfaceNumber }?.get(4) ?: 0
        if (length <= 0) return null

        return control(0x81, REQUEST_GET_DESCRIPTOR, DESCRIPTOR_HID_REPORT shl 8, interfaceNumber, length)
    }

    fun release() {
        controlRing?.release()
        inputRing?.release()
        outputRing?.release()
        bulkInRing?.release()
        bulkOutRing?.release()

        controlRing = null
        inputRing = null
        outputRing = null
        bulkInRing = null
        bulkOutRing = null

        inputContext?.let { PageAllocator.free(it) }
        deviceContext?.let { PageAllocator.free(it) }
        buffer?.let { PageAllocator.free(it) }
        inputBuffer?.let { PageAllocator.free(it) }
        outputBuffer?.let { PageAllocator.free(it) }
        bulkBuffer?.let { PageAllocator.free(it) }

        inputContext = null
        deviceContext = null
        buffer = null
        inputBuffer = null
        outputBuffer = null
        bulkBuffer = null

        pending = 0UL
        configured = false
    }

    fun initSiblings() {
        for (setting in siblings) {
            val number = setting[0]
            if (setting[1] != HID_CLASS) continue

            control(0x21, REQUEST_SET_IDLE, 0, number, 0)

            val length = setting[4]
            if (length > 0) control(0x81, REQUEST_GET_DESCRIPTOR, DESCRIPTOR_HID_REPORT shl 8, number, length)

            if (setting[2] == HID_BOOT_SUBCLASS) {
                control(0x21, REQUEST_SET_REPORT, 0x0201, number, 2, byteArrayOf(0x01, 0x00))
            }
        }
    }

    private var pending: ULong = 0UL

    fun rearm() {
        pending = 0UL
    }

    fun send(data: ByteArray): Boolean {
        val endpoint = output ?: return false
        val ring = outputRing ?: return false
        val region = outputBuffer ?: return false

        for (i in data.indices) {
            RawMemory.write8(region.address + i.toULong(), data[i].toUByte())
        }

        val trb = ring.enqueue(
            BootInfo.toPhysical(region.address),
            data.size.toUInt(),
            (TRB_NORMAL shl 10).toUInt() or INTERRUPT_ON_COMPLETION,
        )

        controller.ringDoorbell(slot, endpoint.dci)

        val status = controller.waitForEvent(trb, Xhci.TRANSFER_EVENT) ?: return false
        lastCompletion = completion(status)

        return lastCompletion == SUCCESS
    }

    fun submit(): Boolean {
        val endpoint = input ?: return false
        val ring = inputRing ?: return false
        val data = inputBuffer ?: return false

        if (pending != 0UL) return true

        pending = ring.enqueue(
            BootInfo.toPhysical(data.address),
            endpoint.maxPacket.toUInt(),
            (TRB_NORMAL shl 10).toUInt() or INTERRUPT_ON_COMPLETION or INTERRUPT_ON_SHORT,
        )

        controller.ringDoorbell(slot, endpoint.dci)
        return true
    }

    fun complete(target: ByteArray): Int {
        val endpoint = input ?: return -1
        val data = inputBuffer ?: return -1
        val ring = inputRing ?: return -1
        if (pending == 0UL) return -1

        val status = controller.tryEvent(pending, Xhci.TRANSFER_EVENT) ?: return -1
        pending = 0UL

        val code = completion(status)
        lastCompletion = code

        if (code != SUCCESS && code != SHORT_PACKET) {
            recover(endpoint, ring)
            return -1
        }

        val remaining = (status and 0xFFFFFFu).toInt()
        val received = endpoint.maxPacket - remaining
        val length = if (received < target.size) received else target.size

        for (i in 0 until length) {
            target[i] = RawMemory.read8(data.address + i.toULong()).toByte()
        }

        return length
    }

    fun poll(target: ByteArray): Int {
        val endpoint = input ?: return -1
        val ring = inputRing ?: return -1
        val data = inputBuffer ?: return -1

        val length = if (target.size < endpoint.maxPacket) target.size else endpoint.maxPacket

        val trb = ring.enqueue(
            BootInfo.toPhysical(data.address),
            length.toUInt(),
            (TRB_NORMAL shl 10).toUInt() or INTERRUPT_ON_COMPLETION or INTERRUPT_ON_SHORT,
        )

        controller.ringDoorbell(slot, endpoint.dci)

        val status = controller.waitForEvent(trb, Xhci.TRANSFER_EVENT)
        if (status == null) {
            lastCompletion = -1
            return -1
        }

        val code = completion(status)
        lastCompletion = code
        if (code != SUCCESS && code != SHORT_PACKET) return -1

        val remaining = (status and 0xFFFFFFu).toInt()
        val received = length - remaining

        for (i in 0 until received) {
            target[i] = RawMemory.read8(data.address + i.toULong()).toByte()
        }

        return received
    }

    private fun recover(endpoint: USBEndpoint, ring: XhciRing): Boolean {
        val target = (slot.toUInt() shl 24) or (endpoint.dci.toUInt() shl 16)

        val reset = controller.submitCommand(
            0UL,
            0u,
            (Xhci.TRB_RESET_ENDPOINT shl 10).toUInt() or target,
        ) ?: return false

        if (completion(reset) != SUCCESS) return false

        val dequeue = controller.submitCommand(
            ring.dequeuePointer() or ring.cycle.toULong(),
            0u,
            (Xhci.TRB_SET_TR_DEQUEUE shl 10).toUInt() or target,
        ) ?: return false

        return completion(dequeue) == SUCCESS
    }

    private fun readDescriptor(type: Int, index: Int, length: Int): ByteArray? {
        val value = (type shl 8) or index
        return control(0x80, REQUEST_GET_DESCRIPTOR, value, 0, length)
    }

    private fun control(
        requestType: Int,
        request: Int,
        value: Int,
        index: Int,
        length: Int,
        payload: ByteArray? = null,
    ): ByteArray? {
        val ring = controlRing ?: return null
        val data = buffer ?: return null

        val setup = requestType.toULong() or
            (request.toULong() shl 8) or
            (value.toULong() shl 16) or
            (index.toULong() shl 32) or
            (length.toULong() shl 48)

        val inbound = requestType and 0x80 != 0
        val transferType = if (length == 0) 0 else if (inbound) 3 else 2

        if (payload != null) {
            for (i in payload.indices) {
                RawMemory.write8(data.address + i.toULong(), payload[i].toUByte())
            }
        }

        ring.enqueue(
            setup,
            8u,
            (TRB_SETUP shl 10).toUInt() or IMMEDIATE_DATA or (transferType.toUInt() shl 16),
        )

        if (length > 0) {
            ring.enqueue(
                BootInfo.toPhysical(data.address),
                length.toUInt(),
                (TRB_DATA shl 10).toUInt() or (if (inbound) DIRECTION_IN else 0u),
            )
        }

        val trb = ring.enqueue(
            0UL,
            0u,
            (TRB_STATUS shl 10).toUInt() or INTERRUPT_ON_COMPLETION or
                (if (length > 0 && inbound) 0u else DIRECTION_IN),
        )

        controller.ringDoorbell(slot, 1)

        val status = controller.waitForEvent(trb, Xhci.TRANSFER_EVENT) ?: return null
        if (completion(status) != SUCCESS) return null

        val result = ByteArray(length)
        for (i in 0 until length) {
            result[i] = RawMemory.read8(data.address + i.toULong()).toByte()
        }

        return result
    }

    private fun evaluateContext(): Boolean {
        val region = inputContext ?: return false
        val ring = controlRing ?: return false

        region.zero()
        writeInputControl(0x3u)
        writeSlotContext(1)
        writeControlEndpoint(ring)

        val result = controller.submitCommand(
            BootInfo.toPhysical(region.address),
            0u,
            (13 shl 10).toUInt() or (slot.toUInt() shl 24),
        ) ?: return false

        return completion(result) == SUCCESS
    }

    private fun writeInputControl(add: UInt) {
        val region = inputContext ?: return
        RawMemory.write32(region.address, 0u)
        RawMemory.write32(region.address + 4UL, add)
    }

    private fun writeSlotContext(lastEndpoint: Int) {
        val region = inputContext ?: return
        val slotContext = region.address + controller.contextBytes.toULong()

        RawMemory.write32(slotContext, (psi.toUInt() shl 20) or (lastEndpoint.toUInt() shl 27))
        RawMemory.write32(slotContext + 4UL, port.toUInt() shl 16)
        RawMemory.write32(slotContext + 8UL, 0u)
        RawMemory.write32(slotContext + 12UL, 0u)
    }

    private fun writeControlEndpoint(ring: XhciRing) {
        val region = inputContext ?: return
        val endpoint = region.address + (controller.contextBytes * 2).toULong()

        RawMemory.write32(endpoint, 0u)
        RawMemory.write32(
            endpoint + 4UL,
            (3u shl 1) or (ENDPOINT_CONTROL.toUInt() shl 3) or (maxPacket.toUInt() shl 16),
        )
        RawMemory.write64(endpoint + 8UL, ring.physical or 1UL)
        RawMemory.write32(endpoint + 16UL, 8u)
    }

    private fun writeInterruptEndpoint(endpoint: USBEndpoint, ring: XhciRing) {
        val region = inputContext ?: return
        val context = region.address + (controller.contextBytes * (endpoint.dci + 1)).toULong()

        val type = if (endpoint.isInput) ENDPOINT_INTERRUPT_IN else ENDPOINT_INTERRUPT_OUT
        val payload = endpoint.maxPacket

        RawMemory.write32(context, intervalFor(endpoint).toUInt() shl 16)
        RawMemory.write32(
            context + 4UL,
            (3u shl 1) or (type.toUInt() shl 3) or (endpoint.maxPacket.toUInt() shl 16),
        )
        RawMemory.write64(context + 8UL, ring.physical or 1UL)
        RawMemory.write32(
            context + 16UL,
            endpoint.maxPacket.toUInt() or ((payload.toUInt() and 0xFFFFu) shl 16),
        )
    }

    private fun writeBulkEndpoint(endpoint: USBEndpoint, ring: XhciRing) {
        val region = inputContext ?: return
        val context = region.address + (controller.contextBytes * (endpoint.dci + 1)).toULong()

        val type = if (endpoint.isInput) ENDPOINT_BULK_IN else ENDPOINT_BULK_OUT

        RawMemory.write32(context, 0u)
        RawMemory.write32(
            context + 4UL,
            (3u shl 1) or (type.toUInt() shl 3) or (endpoint.maxPacket.toUInt() shl 16),
        )
        RawMemory.write64(context + 8UL, ring.physical or 1UL)
        RawMemory.write32(context + 16UL, endpoint.maxPacket.toUInt())
    }

    private fun intervalFor(endpoint: USBEndpoint): Int {
        if (speed >= SPEED_HIGH) {
            val value = endpoint.interval - 1
            return if (value < 0) 0 else if (value > 15) 15 else value
        }

        var frames = endpoint.interval
        if (frames < 1) frames = 1

        var exponent = 3
        while ((1 shl (exponent - 3)) < frames && exponent < 10) exponent++

        return exponent
    }

    private fun completion(status: UInt): Int = ((status shr 24) and 0xFFu).toInt()

    private fun hex2(value: Int): String = value.toString(16).padStart(2, '0')

    private fun word(data: ByteArray, offset: Int): Int {
        if (offset + 1 >= data.size) return 0
        return (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
    }

    companion object {
        const val SPEED_FULL = 1
        const val SPEED_LOW = 2
        const val SPEED_HIGH = 3
        const val SPEED_SUPER = 4

        private const val SUCCESS = 1
        private const val SHORT_PACKET = 13
        private const val STALL = 6

        private const val TRB_NORMAL = 1
        private const val TRB_SETUP = 2
        private const val TRB_DATA = 3
        private const val TRB_STATUS = 4

        private const val ENDPOINT_CONTROL = 4
        private const val ENDPOINT_INTERRUPT_OUT = 3
        private const val ENDPOINT_INTERRUPT_IN = 7
        private const val ENDPOINT_BULK_OUT = 2
        private const val ENDPOINT_BULK_IN = 6

        private const val IMMEDIATE_DATA = 0x40u
        private const val INTERRUPT_ON_COMPLETION = 0x20u
        private const val INTERRUPT_ON_SHORT = 0x4u
        private const val DIRECTION_IN = 0x10000u

        private const val DESCRIPTOR_DEVICE = 1
        private const val DESCRIPTOR_CONFIGURATION = 2
        private const val DESCRIPTOR_INTERFACE = 4
        private const val DESCRIPTOR_ENDPOINT = 5
        private const val DESCRIPTOR_HID = 0x21
        private const val DESCRIPTOR_HID_REPORT = 0x22

        private const val REQUEST_GET_DESCRIPTOR = 6
        private const val REQUEST_SET_CONFIGURATION = 9
        private const val REQUEST_SET_IDLE = 0x0A
        private const val REQUEST_SET_REPORT = 0x09
        private const val REQUEST_XINPUT = 0x01
        private const val REQUEST_CLEAR_FEATURE = 0x01
        private const val REQUEST_STORAGE_RESET = 0xFF
        private const val REQUEST_MAX_LUN = 0xFE

        private const val FEATURE_ENDPOINT_HALT = 0x00

        private const val HID_BOOT_SUBCLASS = 0x01

        const val VENDOR_CLASS = 0xFF
        const val XINPUT_SUBCLASS = 0x5D
        const val HID_CLASS = 0x03
        const val STORAGE_CLASS = 0x08
        const val BULK_ONLY_PROTOCOL = 0x50

        private const val BULK_BUFFER_BYTES = 65536
        private const val BULK_PAGES = 32
        private const val BULK_ALIGN = 65536UL

        private const val RING_ENTRIES = 64
    }
}
