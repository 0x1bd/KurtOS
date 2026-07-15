package n64.core

const val RDRAM_SIZE = 0x800000
const val RDRAM_MASK = 0x7FFFFF

const val ISVIEWER_BASE = 0x13FF0000
const val ISVIEWER_SIZE = 0x1000
const val ISVIEWER_LENGTH = 0x14
const val ISVIEWER_BUFFER = 0x20

const val EVENT_NONE = 0
const val EVENT_AI = 1
const val EVENT_SI = 2
const val EVENT_VI = 3
const val EVENT_PI = 4
const val EVENT_DP = 5
const val EVENT_SP = 6
const val EVENT_INT = 7
const val EVENT_SPDMA = 8
const val EVENT_COMPARE = 9
const val EVENT_COUNT = 10

class N64(image: ByteArray, forceNoDynarec: Boolean = false, forceNoRspDynarec: Boolean = false) {
    val rom = ROM(image)

    val rdram = IntArray(RDRAM_SIZE / 4)
    val hidden = ByteArray(RDRAM_SIZE / 2)
    val rdramRegs = Array(4) { IntArray(16) }
    val codePages = ByteArray(RDRAM_SIZE ushr CPU_PAGE_SHIFT)

    val mi = MI(this)
    val ri = Ri()
    val vi = VI(this)
    val ai = AI(this)
    val pi = PI(this)
    val si = Si(this)
    val pif = Pif(this)
    val cartridge = Cartridge(this)
    val rsp = RSP(this, forceNoRspDynarec)
    val rdp = Rdp(this)
    val cpu = CPU(this)
    val dynarec = if (forceNoDynarec) null else CpuDynarec.create(this)

    val clockRate = 93750000L

    val eventEnabled = BooleanArray(EVENT_COUNT)
    val eventCount = LongArray(EVENT_COUNT)
    val nextEventCount: Long get() = cpu.nextEventCount
    var nextEvent = EVENT_NONE
    var inEvent = false

    var frameDone: Boolean
        get() = cpu.frameDone
        set(value) { cpu.frameDone = value }
    var frameCount = 0
    var frameDeadline: Long
        get() = cpu.frameDeadline
        set(value) { cpu.frameDeadline = value }

    val frameMicros: ULong get() = if (rom.pal) 20000uL else 16667uL

    init {
        reset()
    }

    fun reset() {
        rdram.fill(0)
        for (bank in rdramRegs) bank.fill(0)

        for (i in 0 until EVENT_COUNT) {
            eventEnabled[i] = false
            eventCount[i] = 0
        }
        cpu.nextEventCount = Long.MAX_VALUE
        nextEvent = EVENT_NONE

        mi.reset()
        ri.reset()
        vi.reset()
        ai.reset()
        pi.reset()
        si.reset()
        pif.reset()
        rsp.reset()
        rdp.reset()
        cpu.reset()
        dynarec?.flush()

        rdram[0x318 / 4] = RDRAM_SIZE
        rdram[0x3F0 / 4] = RDRAM_SIZE

        boot()
    }

    private fun boot() {
        for (i in 0 until 0x1000 / 4) rsp.dmem[i] = rom.word(i * 4)

        cpu.gpr[1] = 0x0000000000000000L
        cpu.gpr[2] = 0xFFFFFFFFD1731BE9uL.toLong()
        cpu.gpr[3] = 0xFFFFFFFFD1731BE9uL.toLong()
        cpu.gpr[4] = 0x0000000000001BE9L
        cpu.gpr[5] = 0xFFFFFFFFF45231E5uL.toLong()
        cpu.gpr[6] = 0xFFFFFFFFA4001F0CuL.toLong()
        cpu.gpr[7] = 0xFFFFFFFFA4001F08uL.toLong()
        cpu.gpr[8] = 0x00000000000000C0L
        cpu.gpr[10] = 0x0000000000000040L
        cpu.gpr[11] = 0xFFFFFFFFA4000040uL.toLong()
        cpu.gpr[12] = 0xFFFFFFFFED10D0B3uL.toLong()
        cpu.gpr[13] = 0x000000001402A4CCL
        cpu.gpr[14] = 0x000000002DE108EAL
        cpu.gpr[15] = 0x000000003103E121L
        cpu.gpr[19] = 0L
        cpu.gpr[20] = if (rom.pal) 0L else 1L
        cpu.gpr[21] = 0L
        cpu.gpr[22] = rom.cicSeed.toLong()
        cpu.gpr[23] = 0L
        cpu.gpr[25] = 0xFFFFFFFF9DEBB54FuL.toLong()
        cpu.gpr[29] = 0xFFFFFFFFA4001FF0uL.toLong()

        cpu.cop0[COP0_RANDOM] = 0x1F
        cpu.cop0[COP0_STATUS] = 0x241000E0
        cpu.cop0[COP0_CAUSE] = 0x30000000
        cpu.cop0[COP0_PRID] = 0x00000B22
        cpu.cop0[COP0_CONFIG] = 0x7006E463
        cpu.cop0[COP0_EPC] = -1L
        cpu.cop0[COP0_ERROREPC] = -1L
        cpu.cop0[COP0_CONTEXT] = 0x7FFFF0
        cpu.cop0[COP0_BADVADDR] = 0xFFFFFFFFL
        cpu.setFpuRegisterMode()

        cpu.pc = 0xFFFFFFFFA4000040uL.toLong()

        createEvent(EVENT_COMPARE, 0xFFFFFFFFL)
        vi.setRefreshRate()
    }

    fun createEvent(event: Int, delay: Long) {
        eventEnabled[event] = true
        eventCount[event] = cpu.count + delay
        setNextEvent()
    }

    fun createEventAt(event: Int, at: Long) {
        eventEnabled[event] = true
        eventCount[event] = at
        setNextEvent()
    }

    fun removeEvent(event: Int) {
        eventEnabled[event] = false
        setNextEvent()
    }

    fun eventPending(event: Int): Boolean = eventEnabled[event]

    fun setNextEvent() {
        var best = Long.MAX_VALUE
        var which = EVENT_NONE
        for (i in 0 until EVENT_COUNT) {
            if (eventEnabled[i] && eventCount[i] < best) {
                best = eventCount[i]
                which = i
            }
        }
        cpu.nextEventCount = best
        nextEvent = which
    }

    fun translateEvents(old: Long, new: Long) {
        for (i in 0 until EVENT_COUNT) {
            if (eventEnabled[i]) eventCount[i] = eventCount[i] - old + new
        }
        setNextEvent()
    }

    fun triggerEvent() {
        val which = nextEvent
        eventEnabled[which] = false
        inEvent = true
        when (which) {
            EVENT_AI -> ai.dmaEvent()
            EVENT_SI -> si.dmaEvent()
            EVENT_VI -> vi.verticalInterrupt()
            EVENT_PI -> pi.dmaEvent()
            EVENT_DP -> rdp.interruptEvent()
            EVENT_SP -> rsp.event()
            EVENT_SPDMA -> rsp.dmaPop()
            EVENT_INT -> cpu.interruptException()
            EVENT_COMPARE -> cpu.compareEvent()
        }
        inEvent = false
        setNextEvent()
    }

    fun runFrame() {
        frameDone = false
        frameCount++
        frameDeadline = cpu.count + vi.delay * 2

        var guard = 0
        while (!frameDone && cpu.count < frameDeadline) {
            cpu.run()
            if (++guard > 8) break
        }
    }

    var debugTrace = false
    val debugLog = ArrayList<String>()

    var onSerial: ((String) -> Unit)? = null

    private val isviewer = IntArray(ISVIEWER_SIZE / 4)

    private fun isviewerWrite(addr: Int, value: Int, mask: Int) {
        val offset = addr and (ISVIEWER_SIZE - 1)
        if (offset == ISVIEWER_LENGTH) {
            val length = value and mask
            val text = StringBuilder()
            for (i in 0 until length) {
                val at = ISVIEWER_BUFFER + i
                val word = isviewer[at ushr 2]
                val byte = (word ushr (24 - ((at and 3) shl 3))) and 0xFF
                text.append(byte.toChar())
            }
            onSerial?.invoke(text.toString())
            return
        }
        val index = offset ushr 2
        isviewer[index] = (isviewer[index] and mask.inv()) or (value and mask)
    }

    private fun isviewerRead(addr: Int): Int = isviewer[(addr and (ISVIEWER_SIZE - 1)) ushr 2]

    fun read32(address: Int): Int {
        val addr = address and 0x1FFFFFFF
        if (addr < 0x03F00000) {
            if (addr >= RDRAM_SIZE) return 0
            return rdram[addr ushr 2]
        }
        val value = readSlow(addr)
        if (debugTrace && addr >= 0x04000000 && addr < 0x05000000) {
            debugLog.add("R ${hex(addr)} = ${hex(value)} pc=${hex(cpu.pc.toInt())} f=$frameCount")
            if (debugLog.size > 300000) debugLog.removeAt(0)
        }
        return value
    }

    private fun hex(value: Int) = "0x${value.toUInt().toString(16)}"

    private fun readSlow(addr: Int): Int = when {
        addr < 0x04000000 -> rdramRegs[(addr ushr 13) and 3][(addr and 0x3FF) ushr 2]
        addr < 0x04040000 -> rsp.readMem(addr and 0x1FFF)
        addr < 0x04080000 -> rsp.readReg((addr and 0xFFFF) ushr 2)
        addr < 0x04100000 -> rsp.readPcReg((addr and 0xFFFF) ushr 2)
        addr < 0x04200000 -> rdp.readDpc((addr and 0xFFFF) ushr 2)
        addr < 0x04300000 -> rdp.readDps((addr and 0xFFFF) ushr 2)
        addr < 0x04400000 -> mi.read((addr and 0xFFFF) ushr 2)
        addr < 0x04500000 -> vi.read((addr and 0xFFFF) ushr 2)
        addr < 0x04600000 -> ai.read((addr and 0xFFFF) ushr 2)
        addr < 0x04700000 -> pi.read((addr and 0xFFFF) ushr 2)
        addr < 0x04800000 -> ri.read((addr and 0xFFFF) ushr 2, this)
        addr < 0x04900000 -> si.read((addr and 0xFFFF) ushr 2)
        addr in 0x05000000..0x0FFFFFFF -> cartridge.readSave(addr)
        addr in ISVIEWER_BASE until ISVIEWER_BASE + ISVIEWER_SIZE -> isviewerRead(addr)
        addr in 0x10000000..0x1FBFFFFF -> cartridge.readRom(addr)
        addr >= 0x1FC00000 -> pif.readMem(addr)
        else -> 0
    }

    fun write32(address: Int, value: Int, mask: Int) {
        val addr = address and 0x1FFFFFFF
        if (debugTrace && addr >= 0x04000000 && addr < 0x05000000) {
            debugLog.add("W ${hex(addr)} = ${hex(value)} pc=${hex(cpu.pc.toInt())} f=$frameCount")
            if (debugLog.size > 300000) debugLog.removeAt(0)
        }
        if (addr < 0x03F00000) {
            if (addr >= RDRAM_SIZE) return
            if (mi.initMode) {
                writeRepeat(addr, value)
                return
            }
            val index = addr ushr 2
            rdram[index] = (rdram[index] and mask.inv()) or (value and mask)
            if (codePages[addr ushr CPU_PAGE_SHIFT].toInt() != 0) dynarec?.invalidatePage(addr ushr CPU_PAGE_SHIFT)
            return
        }
        writeSlow(addr, value, mask)
    }

    private fun writeRepeat(addr: Int, value: Int) {
        val length = (mi.regs[MI_INIT_MODE] and MI_INIT_LENGTH_MASK) + 1
        var at = addr
        val end = addr + length
        while (at + 3 < end && at < RDRAM_SIZE) {
            rdram[at ushr 2] = value
            at += 4
        }
        dynarec?.invalidateRange(addr, length)
        mi.regs[MI_INIT_MODE] = mi.regs[MI_INIT_MODE] and MI_INIT_MODE_BIT.inv()
        mi.initMode = false
    }

    private fun writeSlow(addr: Int, value: Int, mask: Int) {
        when {
            addr < 0x04000000 -> {
                val bank = rdramRegs[(addr ushr 13) and 3]
                val reg = (addr and 0x3FF) ushr 2
                if (reg < bank.size) bank[reg] = (bank[reg] and mask.inv()) or (value and mask)
            }

            addr < 0x04040000 -> rsp.writeMem(addr and 0x1FFF, value, mask)
            addr < 0x04080000 -> rsp.writeReg((addr and 0xFFFF) ushr 2, value, mask)
            addr < 0x04100000 -> rsp.writePcReg((addr and 0xFFFF) ushr 2, value, mask)
            addr < 0x04200000 -> rdp.writeDpc((addr and 0xFFFF) ushr 2, value, mask)
            addr < 0x04300000 -> rdp.writeDps((addr and 0xFFFF) ushr 2, value, mask)
            addr < 0x04400000 -> mi.write((addr and 0xFFFF) ushr 2, value, mask)
            addr < 0x04500000 -> vi.write((addr and 0xFFFF) ushr 2, value, mask)
            addr < 0x04600000 -> ai.write((addr and 0xFFFF) ushr 2, value, mask)
            addr < 0x04700000 -> pi.write((addr and 0xFFFF) ushr 2, value, mask)
            addr < 0x04800000 -> ri.write((addr and 0xFFFF) ushr 2, value, mask)
            addr < 0x04900000 -> si.write((addr and 0xFFFF) ushr 2, value, mask)
            addr in 0x05000000..0x0FFFFFFF -> cartridge.writeSave(addr, value, mask)
            addr in ISVIEWER_BASE until ISVIEWER_BASE + ISVIEWER_SIZE -> isviewerWrite(addr, value, mask)
            addr in 0x10000000..0x1FBFFFFF -> cartridge.writeRom(addr, value, mask)
            addr >= 0x1FC00000 -> pif.writeMem(addr, value, mask)
        }
    }

    fun ramRead8(addr: Int): Int {
        val index = (addr and RDRAM_MASK) ushr 2
        val shift = 24 - ((addr and 3) shl 3)
        return (rdram[index] ushr shift) and 0xFF
    }

    fun ramWrite8(addr: Int, value: Int) {
        val index = (addr and RDRAM_MASK) ushr 2
        val shift = 24 - ((addr and 3) shl 3)
        rdram[index] = (rdram[index] and (0xFF shl shift).inv()) or ((value and 0xFF) shl shift)
        val page = (addr and RDRAM_MASK) ushr CPU_PAGE_SHIFT
        if (codePages[page].toInt() != 0) dynarec?.invalidatePage(page)
    }

    fun ramRead16(addr: Int): Int = (ramRead8(addr) shl 8) or ramRead8(addr + 1)

    fun ramRead32(addr: Int): Int = rdram[(addr and RDRAM_MASK) ushr 2]

    fun ramWrite32(addr: Int, value: Int) {
        rdram[(addr and RDRAM_MASK) ushr 2] = value
        val page = (addr and RDRAM_MASK) ushr CPU_PAGE_SHIFT
        if (codePages[page].toInt() != 0) dynarec?.invalidatePage(page)
    }

    fun describe(): String =
        "${rom.name} [${rom.gameCode}] cic=${rom.cicSeed} ${if (rom.pal) "PAL" else "NTSC"} save=${rom.save}"

    fun setButtons(mask: Int) = pif.setButtons(mask)

    fun setStick(x: Int, y: Int) = pif.setStick(x, y)
}
