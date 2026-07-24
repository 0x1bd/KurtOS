package hal

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PCIConfigTest {
    private val fake = TestPlatform.current

    @BeforeTest
    fun setUp() = TestPlatform.install()

    @Test
    fun encodesConfigAddress() {
        PCIConfig.read32(bus = 0, device = 2, function = 1, offset = 0x10)
        assertEquals(0x80001110u, fake.port.lastConfigAddress)
    }

    @Test
    fun masksOffsetToDwordBoundary() {
        PCIConfig.read32(bus = 0, device = 0, function = 0, offset = 0x0F)
        assertEquals(0x8000000Cu, fake.port.lastConfigAddress)
    }

    @Test
    fun encodesHighBusAndDevice() {
        PCIConfig.read32(bus = 0xFF, device = 0x1F, function = 7, offset = 0)
        assertEquals(0x80FFFF00u, fake.port.lastConfigAddress)
    }

    @Test
    fun readsVendorAndDeviceHalves() {
        fake.port.configSpace[0x80000000u] = 0x12345678u
        assertEquals(0x5678u.toUShort(), PCIConfig.read16(0, 0, 0, 0))
        assertEquals(0x1234u.toUShort(), PCIConfig.read16(0, 0, 0, 2))
    }

    @Test
    fun readsIndividualBytes() {
        fake.port.configSpace[0x80000008u] = 0xAABBCCDDu
        assertEquals(0xDDu.toUByte(), PCIConfig.read8(0, 0, 0, 8))
        assertEquals(0xAAu.toUByte(), PCIConfig.read8(0, 0, 0, 11))
    }
}

class BootInfoTest {
    private val fake = TestPlatform.current

    @BeforeTest
    fun setUp() = TestPlatform.install()

    @Test
    fun translatesBetweenPhysicalAndVirtual() {
        val physical = 0x1234UL
        assertEquals(physical, BootInfo.toPhysical(BootInfo.toVirtual(physical)))
        assertEquals(fake.boot.hhdmOffset + physical, BootInfo.toVirtual(physical))
    }

    @Test
    fun mapsMemoryKinds() {
        fake.boot.regions = listOf(
            Triple(0UL, 0x1000UL, 0UL),
            Triple(0x1000UL, 0x2000UL, 4UL),
            Triple(0x3000UL, 0x1000UL, 7UL),
            Triple(0x4000UL, 0x1000UL, 99UL),
        )

        val map = BootInfo.memoryMap
        assertEquals(4, map.size)
        assertEquals(MemoryKind.Usable, map[0].kind)
        assertEquals(MemoryKind.Bad, map[1].kind)
        assertEquals(MemoryKind.Framebuffer, map[2].kind)
        assertEquals(MemoryKind.Unknown, map[3].kind)
        assertEquals(0x3000UL, map[2].base)
    }

    @Test
    fun reportsFramebufferWhenPresent() {
        fake.boot.framebufferPresent = 1u
        val fb = BootInfo.framebuffer
        assertEquals(1280u, fb?.width)
        assertEquals(720u, fb?.height)
        assertEquals(16, fb?.redShift)
    }

    @Test
    fun reportsNoFramebufferWhenAbsent() {
        fake.boot.framebufferPresent = 0u
        assertNull(BootInfo.framebuffer)
        fake.boot.framebufferPresent = 1u
    }
}

class CpuTest {
    private val fake = TestPlatform.current

    @BeforeTest
    fun setUp() = TestPlatform.install()

    @Test
    fun decodesBrandString() {
        fake.cpu.brandString = "Fake Kurt CPU @ 3.00GHz"
        assertEquals("Fake Kurt CPU @ 3.00GHz", Cpu.brand())
    }

    @Test
    fun readsAndWritesMsrs() {
        Cpu.writeMsr(0xC0010015u, 0xDEADBEEFUL)
        assertEquals(0xDEADBEEFUL, Cpu.readMsr(0xC0010015u))
    }

    @Test
    fun tracksInterruptState() {
        Cpu.enableInterrupts()
        assertTrue(fake.cpu.interruptsEnabled)
        Cpu.disableInterrupts()
        assertTrue(!fake.cpu.interruptsEnabled)
    }
}

class RawMemoryTest {
    private val fake = TestPlatform.current

    @BeforeTest
    fun setUp() = TestPlatform.install()

    @Test
    fun readsBackWrittenWords() {
        RawMemory.write32(0x2000UL, 0xCAFEBABEu)
        assertEquals(0xCAFEBABEu, RawMemory.read32(0x2000UL))
        assertEquals(0xBEu.toUByte(), RawMemory.read8(0x2000UL))
    }

    @Test
    fun readBytesMatchesUnderlyingStore() {
        for (i in 0 until 8) fake.memory.store[0x3000UL + i.toULong()] = (i * 3).toUByte()
        val bytes = RawMemory.readBytes(0x3000UL, 8)
        assertEquals(8, bytes.size)
        assertEquals(listOf(0, 3, 6, 9, 12, 15, 18, 21), bytes.map { it.toInt() })
    }

    @Test
    fun copyOutPinsTargetAndDelegatesWithDeviceAsSource() {
        val target = ByteArray(4)
        RawMemory.copyOut(0x4000UL, target, 0, 4)
        assertEquals(0x4000UL, fake.memory.lastCopySource)
        assertEquals(4UL, fake.memory.lastCopyBytes)
        assertTrue(fake.memory.lastCopyDestination != 0UL)
    }

    @Test
    fun copyInPinsSourceAndDelegatesWithDeviceAsDestination() {
        val source = ByteArray(8)
        RawMemory.copyIn(0x6000UL, source, 2, 6)
        assertEquals(0x6000UL, fake.memory.lastCopyDestination)
        assertEquals(6UL, fake.memory.lastCopyBytes)
        assertTrue(fake.memory.lastCopySource != 0UL)
    }

    @Test
    fun zeroLengthCopiesAreSkipped() {
        fake.memory.lastCopyBytes = 0xFFUL
        RawMemory.copyOut(0x7000UL, ByteArray(4), 0, 0)
        assertEquals(0xFFUL, fake.memory.lastCopyBytes)
    }

    @Test
    fun fill32RepeatsPattern() {
        RawMemory.fill32(0x5000UL, 0x11223344u, 3u)
        assertEquals(0x11223344u, RawMemory.read32(0x5000UL))
        assertEquals(0x11223344u, RawMemory.read32(0x5008UL))
    }
}

class SerialTest {
    @BeforeTest
    fun setUp() = TestPlatform.install()

    @Test
    fun probesScratchRegisterAndEmitsBytes() {
        Serial.initialize()
        assertTrue(Serial.isPresent)

        val fake = TestPlatform.current
        Serial.putChar('K')
        assertEquals('K'.code.toUByte(), fake.port.bytes[0x3F8])
    }

    @Test
    fun translatesNewlineToCarriageReturnPair() {
        Serial.initialize()
        val fake = TestPlatform.current
        fake.port.bytes.remove(0x3F8)
        Serial.putChar('\n')
        assertEquals('\n'.code.toUByte(), fake.port.bytes[0x3F8])
    }
}

class ArchTest {
    private val fake = TestPlatform.current

    @BeforeTest
    fun setUp() = TestPlatform.install()

    @Test
    fun drainsScancodeQueue() {
        fake.arch.scancodes.addAll(listOf(0x1E, 0x30))
        assertEquals(0x1E, Arch.nextScancode())
        assertEquals(0x30, Arch.nextScancode())
        assertEquals(-1, Arch.nextScancode())
    }

    @Test
    fun enablesKeyboardPolling() {
        Arch.enableKeyboardPoll()
        assertTrue(fake.arch.keyboardPollEnabled)
    }

    @Test
    fun exposesTickCounter() {
        fake.arch.tickCount = 4242UL
        assertEquals(4242UL, Arch.ticks())
        assertEquals(4242UL, Clock.uptimeMillis())
    }
}
