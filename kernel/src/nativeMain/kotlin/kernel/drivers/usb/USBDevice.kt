package kernel.drivers.usb

import hal.BootInfo
import hal.RawMemory
import kernel.memory.PageAllocator
import kernel.memory.Region

class UsbEndpoint(val address: Int, val attributes: Int, val maxPacket: Int, val interval: Int) {
    val number: Int get() = address and 0x0F
    val isInput: Boolean get() = address and 0x80 != 0
    val isInterrupt: Boolean get() = attributes and 0x03 == 0x03
    val dci: Int get() = number * 2 + if (isInput) 1 else 0
}

class UsbDevice(
    private val controller: Xhci,
    val slot: Int,
    val port: Int,
    val speed: Int,
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

    var input: UsbEndpoint? = null
        private set

    private var inputContext: Region? = null
    private var deviceContext: Region? = null
    private var buffer: Region? = null

    private var controlRing: XhciRing? = null
    private var inputRing: XhciRing? = null

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

        maxPacket = when (speed) {
            SPEED_LOW -> 8
            SPEED_FULL -> 8
            SPEED_HIGH -> 64
            else -> 512
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

        var offset = 0
        var currentInterface = -1

        while (offset + 2 <= full.size) {
            val length = full[offset].toInt() and 0xFF
            val type = full[offset + 1].toInt() and 0xFF
            if (length == 0) break

            if (type == DESCRIPTOR_INTERFACE && offset + 9 <= full.size) {
                currentInterface = full[offset + 2].toInt() and 0xFF

                if (input == null) {
                    interfaceNumber = currentInterface
                    interfaceClass = full[offset + 5].toInt() and 0xFF
                    interfaceSubclass = full[offset + 6].toInt() and 0xFF
                    interfaceProtocol = full[offset + 7].toInt() and 0xFF
                }
            }

            if (type == DESCRIPTOR_ENDPOINT && offset + 7 <= full.size && input == null) {
                val endpoint = UsbEndpoint(
                    address = full[offset + 2].toInt() and 0xFF,
                    attributes = full[offset + 3].toInt() and 0xFF,
                    maxPacket = word(full, offset + 4) and 0x7FF,
                    interval = full[offset + 6].toInt() and 0xFF,
                )

                if (endpoint.isInterrupt && endpoint.isInput) input = endpoint
            }

            offset += length
        }

        return true
    }

    fun configure(): Boolean {
        val endpoint = input ?: return false
        val ring = XhciRing.allocate(RING_ENTRIES, link = true) ?: return false
        inputRing = ring

        val region = inputContext ?: return false
        region.zero()

        writeInputControl(0x1u or (1u shl endpoint.dci))
        writeSlotContext(endpoint.dci)
        writeInterruptEndpoint(endpoint, ring)

        val result = controller.submitCommand(
            BootInfo.toPhysical(region.address),
            0u,
            ((Xhci.TRB_CONFIGURE_ENDPOINT shl 10).toUInt()) or (slot.toUInt() shl 24),
        ) ?: return false

        if (completion(result) != SUCCESS) return false

        return control(0x00, REQUEST_SET_CONFIGURATION, configuration, 0, 0) != null
    }

    fun poll(target: ByteArray): Int {
        val endpoint = input ?: return -1
        val ring = inputRing ?: return -1
        val data = buffer ?: return -1

        val length = if (target.size < endpoint.maxPacket) target.size else endpoint.maxPacket

        val trb = ring.enqueue(
            BootInfo.toPhysical(data.address),
            length.toUInt(),
            (TRB_NORMAL shl 10).toUInt() or INTERRUPT_ON_COMPLETION or INTERRUPT_ON_SHORT,
        )

        controller.ringDoorbell(slot, endpoint.dci)

        val status = controller.waitForEvent(trb, Xhci.TRANSFER_EVENT) ?: return -1
        val code = completion(status)
        if (code != SUCCESS && code != SHORT_PACKET) return -1

        val remaining = (status and 0xFFFFFFu).toInt()
        val received = length - remaining

        for (i in 0 until received) {
            target[i] = RawMemory.read8(data.address + i.toULong()).toByte()
        }

        return received
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
                (if (inbound || length == 0) 0u else DIRECTION_IN),
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

        RawMemory.write32(slotContext, (speed.toUInt() shl 20) or (lastEndpoint.toUInt() shl 27))
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

    private fun writeInterruptEndpoint(endpoint: UsbEndpoint, ring: XhciRing) {
        val region = inputContext ?: return
        val context = region.address + (controller.contextBytes * (endpoint.dci + 1)).toULong()

        RawMemory.write32(context, intervalFor(endpoint).toUInt() shl 16)
        RawMemory.write32(
            context + 4UL,
            (3u shl 1) or (ENDPOINT_INTERRUPT_IN.toUInt() shl 3) or (endpoint.maxPacket.toUInt() shl 16),
        )
        RawMemory.write64(context + 8UL, ring.physical or 1UL)
        RawMemory.write32(context + 16UL, endpoint.maxPacket.toUInt())
    }

    private fun intervalFor(endpoint: UsbEndpoint): Int {
        if (speed == SPEED_HIGH || speed == SPEED_SUPER) {
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

        private const val TRB_NORMAL = 1
        private const val TRB_SETUP = 2
        private const val TRB_DATA = 3
        private const val TRB_STATUS = 4

        private const val ENDPOINT_CONTROL = 4
        private const val ENDPOINT_INTERRUPT_IN = 7

        private const val IMMEDIATE_DATA = 0x40u
        private const val INTERRUPT_ON_COMPLETION = 0x20u
        private const val INTERRUPT_ON_SHORT = 0x4u
        private const val DIRECTION_IN = 0x10000u

        private const val DESCRIPTOR_DEVICE = 1
        private const val DESCRIPTOR_CONFIGURATION = 2
        private const val DESCRIPTOR_INTERFACE = 4
        private const val DESCRIPTOR_ENDPOINT = 5

        private const val REQUEST_GET_DESCRIPTOR = 6
        private const val REQUEST_SET_CONFIGURATION = 9

        private const val RING_ENTRIES = 64
    }
}
