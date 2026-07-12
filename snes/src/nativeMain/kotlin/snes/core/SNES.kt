package snes.core

import kapi.state.StateReader
import kapi.state.StateWriter

class SNES(image: ByteArray) {
    val cartridge = Cartridge(image)
    val bus = Bus(cartridge)
    val ppu = Ppu()
    val apu = Apu()
    val joypad = Joypad()
    val regs = InternalRegs(bus, joypad)
    val dma = Dma(bus, regs)
    val cpu = Cpu(bus)

    val frame get() = ppu.frame

    var frameCount = 0
        private set

    private var masterClock = 0L
    private var lineClock = 0
    private var stall = 0

    init {
        bus.ppu = ppu
        bus.apu = apu
        bus.regs = regs
        bus.dma = dma
        bus.joypad = joypad
        bus.onApuAccess = { apu.catchUp(masterClock) }
        bus.cpu = cpu

        reset()
    }

    fun reset() {
        masterClock = 0
        lineClock = 0
        stall = 0

        cartridge.reset()
        ppu.reset()
        apu.reset()
        joypad.reset()
        regs.reset()
        dma.reset()
        cpu.reset()
    }

    fun setButtons(mask: Int) = joypad.setButtons(mask)

    fun runFrame() {
        frameCount++
        ppu.startFrame()

        var lastHdma = -1

        for (line in 0 until LINES) {
            startLine(line)

            if (debugTrace && regs.hdmaEnabled != lastHdma) {
                lastHdma = regs.hdmaEnabled
                println("[trace] frame=$frameCount line=$line hdmaEnabled=0x${lastHdma.toString(16)}")
            }

            if (line <= ppu.visibleLines) {
                runTo(RENDER_POINT)

                if (line >= 1) ppu.renderLine(line)

                if (regs.hdmaEnabled != 0) stall += dma.hdmaRun()
            }

            runTo(CYCLES_PER_LINE)

            apu.catchUp(masterClock)
        }

        apu.rebase(masterClock)
        masterClock = 0
    }

    private fun startLine(line: Int) {
        lineClock = 0
        ppu.vCounter = line
        regs.debugLine = line
        regs.debugTrace = debugTrace

        regs.hblank = false
        irqLatched = false

        if (line == 0) {
            regs.vblank = false
            stall += dma.hdmaInit()
        }

        if (line == ppu.visibleLines + 1) {
            regs.vblank = true
            regs.nmiFlag = true

            if (regs.nmiEnabled) {
                cpu.nmiPending = true
                debugNmis++
                if (debugTrace) println("[nmi] line=$line")
            }

            if (regs.autoJoypad) {
                regs.autoBusy = true
                joypad.autoRead()
            }
        }

        if (line == ppu.visibleLines + 3) regs.autoBusy = false
    }

    private fun runTo(target: Int) {
        while (lineClock < target) {
            checkIrq()

            if (regs.dmaPending != 0) {
                val mask = regs.dmaPending
                regs.dmaPending = 0
                stall += dma.runGeneral(mask)
            }

            if (stall > 0) {
                val used = minOf(stall, target - lineClock)
                stall -= used
                advance(used)
                continue
            }

            if (cpu.stopped) {
                advance(target - lineClock)
                continue
            }

            if (cpu.waiting && !cpu.nmiPending && !cpu.irqLine) {
                advance(target - lineClock)
                continue
            }

            advance(cpu.step())
        }
    }

    private fun advance(cycles: Int) {
        masterClock += cycles
        lineClock += cycles

        val dot = lineClock / DOT
        regs.hblank = dot >= HBLANK_DOT
        ppu.hCounter = dot
    }

    private fun checkIrq() {
        val mode = regs.irqMode

        if (mode == 0) {
            irqLatched = false
            cpu.irqLine = false
            return
        }

        val line = ppu.vCounter
        val dot = lineClock / DOT

        val matched = when (mode) {
            1 -> dot >= regs.htime
            2 -> line == regs.vtime
            else -> line == regs.vtime && dot >= regs.htime
        }

        if (matched && !irqLatched) {
            irqLatched = true
            regs.irqFlag = true
            debugIrqs++
        }

        if (!matched) irqLatched = false

        cpu.irqLine = regs.irqFlag
    }

    var debugIrqs = 0
    var debugNmis = 0
    var debugTrace = false
    private var irqLatched = false

    fun saveData(): ByteArray = cartridge.saveData()

    fun loadSaveData(data: ByteArray) = cartridge.loadSaveData(data)

    fun saveVersion(): Int = cartridge.saveVersion

    fun saveState(): ByteArray {
        apu.catchUp(masterClock)

        val writer = StateWriter()

        writer.int(MAGIC)
        writer.int(VERSION)
        writer.long(masterClock)
        writer.int(lineClock)
        writer.int(stall)
        writer.bool(irqLatched)

        cpu.save(writer)
        bus.save(writer)
        ppu.save(writer)
        apu.save(writer)
        regs.save(writer)
        dma.save(writer)
        joypad.save(writer)
        cartridge.save(writer)

        return writer.toByteArray()
    }

    fun loadState(data: ByteArray): Boolean {
        val reader = StateReader(data)

        if (reader.int() != MAGIC) return false
        if (reader.int() != VERSION) return false

        masterClock = reader.long()
        lineClock = reader.int()
        stall = reader.int()
        irqLatched = reader.bool()

        cpu.load(reader)
        bus.load(reader)
        ppu.load(reader)
        apu.load(reader)
        regs.load(reader)
        dma.load(reader)
        joypad.load(reader)
        cartridge.load(reader)

        return reader.valid
    }

    fun describe(): String {
        val mapping = if (cartridge.hiRom) "HiROM" else "LoROM"
        val chip = if (cartridge.chip == ChipId.DSP1) " DSP-1" else ""

        return "${cartridge.title} $mapping${chip}"
    }

    companion object {
        const val LINES = 262
        const val CYCLES_PER_LINE = 1364
        const val DOT = 4
        const val RENDER_POINT = 1024
        const val HBLANK_DOT = 274
        const val IRQ_WINDOW = 4
        const val FRAME_MICROS = 16639UL

        private const val MAGIC = 0x534E4553
        private const val VERSION = 1
    }
}
