package snes.core

import kapi.state.StateReader
import kapi.state.StateWriter

class Dma(private val bus: Bus, private val regs: InternalRegs) {
    private val control = IntArray(CHANNELS)
    private val bAddress = IntArray(CHANNELS)
    private val aAddress = IntArray(CHANNELS)
    private val aBank = IntArray(CHANNELS)
    private val count = IntArray(CHANNELS)
    private val indirectBank = IntArray(CHANNELS)
    private val table = IntArray(CHANNELS)
    private val lineCounter = IntArray(CHANNELS)
    private val indirect = IntArray(CHANNELS)
    private val repeat = BooleanArray(CHANNELS)
    private val doTransfer = BooleanArray(CHANNELS)
    private val terminated = BooleanArray(CHANNELS)
    private val unused = IntArray(CHANNELS)

    fun reset() {
        for (i in 0 until CHANNELS) {
            control[i] = 0xFF
            bAddress[i] = 0xFF
            aAddress[i] = 0xFFFF
            aBank[i] = 0xFF
            count[i] = 0xFFFF
            indirectBank[i] = 0xFF
            table[i] = 0xFFFF
            lineCounter[i] = 0xFF
            indirect[i] = 0xFFFF
            repeat[i] = false
            doTransfer[i] = false
            terminated[i] = false
            unused[i] = 0xFF
        }
    }

    fun readReg(addr: Int): Int {
        val channel = (addr ushr 4) and 7

        return when (addr and 0xF) {
            0x0 -> control[channel]
            0x1 -> bAddress[channel]
            0x2 -> aAddress[channel] and 0xFF
            0x3 -> (aAddress[channel] ushr 8) and 0xFF
            0x4 -> aBank[channel]
            0x5 -> count[channel] and 0xFF
            0x6 -> (count[channel] ushr 8) and 0xFF
            0x7 -> indirectBank[channel]
            0x8 -> table[channel] and 0xFF
            0x9 -> (table[channel] ushr 8) and 0xFF
            0xA -> lineCounter[channel]
            else -> unused[channel]
        }
    }

    fun writeReg(addr: Int, value: Int) {
        val channel = (addr ushr 4) and 7

        when (addr and 0xF) {
            0x0 -> control[channel] = value
            0x1 -> bAddress[channel] = value
            0x2 -> aAddress[channel] = (aAddress[channel] and 0xFF00) or value
            0x3 -> aAddress[channel] = (aAddress[channel] and 0x00FF) or (value shl 8)
            0x4 -> aBank[channel] = value
            0x5 -> count[channel] = (count[channel] and 0xFF00) or value
            0x6 -> count[channel] = (count[channel] and 0x00FF) or (value shl 8)
            0x7 -> indirectBank[channel] = value
            0x8 -> table[channel] = (table[channel] and 0xFF00) or value
            0x9 -> table[channel] = (table[channel] and 0x00FF) or (value shl 8)
            0xA -> lineCounter[channel] = value
            0xB, 0xF -> unused[channel] = value
        }
    }

    fun runGeneral(mask: Int): Int {
        var cycles = OVERHEAD

        for (channel in 0 until CHANNELS) {
            if (mask and (1 shl channel) == 0) continue
            cycles += CHANNEL_OVERHEAD + transferChannel(channel)
        }

        return cycles
    }

    var debugInits = 0
    var debugRuns = 0
    var debugUnits = 0

    val debugControl get() = control
    val debugBAddress get() = bAddress
    val debugAAddress get() = aAddress
    val debugABank get() = aBank
    val debugIndirectBank get() = indirectBank
    val debugIndirect get() = indirect

    fun hdmaInit(): Int {
        val enabled = regs.hdmaEnabled
        if (enabled == 0) return 0
        debugInits++

        var cycles = OVERHEAD

        for (channel in 0 until CHANNELS) {
            if (enabled and (1 shl channel) == 0) continue

            table[channel] = aAddress[channel]
            lineCounter[channel] = 0
            repeat[channel] = false
            doTransfer[channel] = false
            terminated[channel] = false
            cycles += CHANNEL_OVERHEAD
        }

        return cycles
    }

    fun hdmaRun(): Int {
        val enabled = regs.hdmaEnabled
        if (enabled == 0) return 0
        debugRuns++

        var cycles = 0

        for (channel in 0 until CHANNELS) {
            if (enabled and (1 shl channel) == 0) continue
            if (terminated[channel]) continue

            cycles += CHANNEL_OVERHEAD

            if (lineCounter[channel] == 0) {
                val header = readA(channel)

                if (header == 0) {
                    terminated[channel] = true
                    continue
                }

                repeat[channel] = header and 0x80 != 0
                lineCounter[channel] = header and 0x7F

                if (isIndirect(channel)) {
                    val low = readA(channel)
                    val high = readA(channel)
                    indirect[channel] = low or (high shl 8)
                    cycles += 2 * BYTE_COST
                }

                doTransfer[channel] = true
                cycles += BYTE_COST
            }

            if (doTransfer[channel]) {
                debugUnits++
                cycles += transferUnit(channel, true)
            }

            lineCounter[channel]--
            doTransfer[channel] = repeat[channel]
        }

        return cycles
    }

    private fun transferChannel(channel: Int): Int {
        var cycles = 0
        var remaining = if (count[channel] == 0) 0x10000 else count[channel]

        while (remaining > 0) {
            val used = minOf(remaining, unitLength(channel))
            cycles += transferGeneral(channel, used)
            remaining -= used
        }

        count[channel] = 0

        return cycles
    }

    private fun transferGeneral(channel: Int, length: Int): Int {
        val pattern = control[channel] and 7
        val toB = control[channel] and 0x80 == 0
        val step = step(channel)

        for (i in 0 until length) {
            val b = 0x2100 or ((bAddress[channel] + PATTERNS[pattern][i]) and 0xFF)
            val a = (aBank[channel] shl 16) or aAddress[channel]

            if (toB) bus.write8(b, bus.read8(a)) else bus.write8(a, bus.read8(b))

            aAddress[channel] = (aAddress[channel] + step) and 0xFFFF
        }

        return length * BYTE_COST
    }

    private fun transferUnit(channel: Int, hdma: Boolean): Int {
        val pattern = control[channel] and 7
        val toB = control[channel] and 0x80 == 0
        val length = unitLength(channel)

        for (i in 0 until length) {
            val b = 0x2100 or ((bAddress[channel] + PATTERNS[pattern][i]) and 0xFF)
            val a = if (isIndirect(channel)) {
                val address = (indirectBank[channel] shl 16) or indirect[channel]
                indirect[channel] = (indirect[channel] + 1) and 0xFFFF
                address
            } else {
                val address = (aBank[channel] shl 16) or table[channel]
                table[channel] = (table[channel] + 1) and 0xFFFF
                address
            }

            if (toB) bus.write8(b, bus.read8(a)) else bus.write8(a, bus.read8(b))
        }

        return length * BYTE_COST
    }

    private fun readA(channel: Int): Int {
        val address = (aBank[channel] shl 16) or table[channel]
        table[channel] = (table[channel] + 1) and 0xFFFF
        return bus.read8(address)
    }

    private fun isIndirect(channel: Int) = control[channel] and 0x40 != 0

    private fun unitLength(channel: Int) = PATTERNS[control[channel] and 7].size

    private fun step(channel: Int): Int {
        if (control[channel] and 0x08 != 0) return 0
        return if (control[channel] and 0x10 != 0) -1 else 1
    }

    fun save(writer: StateWriter) {
        writer.ints(control)
        writer.ints(bAddress)
        writer.ints(aAddress)
        writer.ints(aBank)
        writer.ints(count)
        writer.ints(indirectBank)
        writer.ints(table)
        writer.ints(lineCounter)
        writer.ints(indirect)
        writer.ints(unused)

        for (i in 0 until CHANNELS) {
            writer.bool(repeat[i])
            writer.bool(doTransfer[i])
            writer.bool(terminated[i])
        }
    }

    fun load(reader: StateReader) {
        reader.ints(control)
        reader.ints(bAddress)
        reader.ints(aAddress)
        reader.ints(aBank)
        reader.ints(count)
        reader.ints(indirectBank)
        reader.ints(table)
        reader.ints(lineCounter)
        reader.ints(indirect)
        reader.ints(unused)

        for (i in 0 until CHANNELS) {
            repeat[i] = reader.bool()
            doTransfer[i] = reader.bool()
            terminated[i] = reader.bool()
        }
    }

    companion object {
        const val CHANNELS = 8
        const val BYTE_COST = 8
        const val OVERHEAD = 18
        const val CHANNEL_OVERHEAD = 8

        private val PATTERNS = arrayOf(
            intArrayOf(0),
            intArrayOf(0, 1),
            intArrayOf(0, 0),
            intArrayOf(0, 0, 1, 1),
            intArrayOf(0, 1, 2, 3),
            intArrayOf(0, 1, 0, 1),
            intArrayOf(0, 0),
            intArrayOf(0, 0, 1, 1),
        )
    }
}
