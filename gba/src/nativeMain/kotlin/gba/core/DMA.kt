package gba.core

import kapi.state.StateReader
import kapi.state.StateWriter

class DMA(private val interrupts: Interrupts) {
    lateinit var bus: Bus

    private val source = IntArray(4)
    private val destination = IntArray(4)
    private val count = IntArray(4)
    private val control = IntArray(4)

    private val internalSource = IntArray(4)
    private val internalDestination = IntArray(4)

    fun save(writer: StateWriter) {
        writer.ints(source)
        writer.ints(destination)
        writer.ints(count)
        writer.ints(control)
        writer.ints(internalSource)
        writer.ints(internalDestination)
    }

    fun load(reader: StateReader) {
        reader.ints(source)
        reader.ints(destination)
        reader.ints(count)
        reader.ints(control)
        reader.ints(internalSource)
        reader.ints(internalDestination)
    }

    fun ioRead(offset: Int): Int {
        val channel = (offset - 0xB0) / 12
        return if ((offset - 0xB0) % 12 == 10) control[channel] else 0
    }

    fun ioWrite(offset: Int, value: Int) {
        val channel = (offset - 0xB0) / 12
        when ((offset - 0xB0) % 12) {
            0 -> source[channel] = (source[channel] and 0xFFFF0000.toInt()) or value
            2 -> source[channel] = (source[channel] and 0xFFFF) or (value shl 16)
            4 -> destination[channel] = (destination[channel] and 0xFFFF0000.toInt()) or value
            6 -> destination[channel] = (destination[channel] and 0xFFFF) or (value shl 16)
            8 -> count[channel] = value
            10 -> {
                val wasEnabled = control[channel] and 0x8000 != 0
                control[channel] = value

                if (!wasEnabled && value and 0x8000 != 0) {
                    internalSource[channel] = source[channel]
                    internalDestination[channel] = destination[channel]

                    if ((value ushr 12) and 0x3 == 0) transfer(channel)
                }
            }
        }
    }

    fun onVBlank() {
        for (channel in 0 until 4) {
            if (control[channel] and 0x8000 == 0) continue
            if ((control[channel] ushr 12) and 0x3 == 1) transfer(channel)
        }
    }

    fun onHBlank() {
        for (channel in 0 until 4) {
            if (control[channel] and 0x8000 == 0) continue
            if ((control[channel] ushr 12) and 0x3 == 2) transfer(channel)
        }
    }

    fun onFifoRequest(fifoB: Boolean) {
        val address = if (fifoB) 0x040000A4 else 0x040000A0

        for (channel in 1..2) {
            if (control[channel] and 0x8000 == 0) continue
            if ((control[channel] ushr 12) and 0x3 != 3) continue
            if (destination[channel] != address) continue

            for (i in 0 until 4) {
                bus.apu.fifoWrite(fifoB, bus.read32(internalSource[channel] and 3.inv()))
                internalSource[channel] += sourceStep(channel) * 4
            }

            if (control[channel] and 0x4000 != 0) {
                interrupts.request(Interrupts.DMA0 shl channel)
            }
        }
    }

    private fun sourceStep(channel: Int): Int = when ((control[channel] ushr 7) and 0x3) {
        1 -> -1
        2 -> 0
        else -> 1
    }

    private fun transfer(channel: Int) {
        val ctrl = control[channel]
        val words = ctrl and 0x0400 != 0
        val unit = if (words) 4 else 2

        var length = count[channel] and (if (channel == 3) 0xFFFF else 0x3FFF)
        if (length == 0) length = if (channel == 3) 0x10000 else 0x4000

        val sourceAdjust = when ((ctrl ushr 7) and 0x3) {
            1 -> -unit
            2 -> 0
            else -> unit
        }

        val destAdjustMode = (ctrl ushr 5) and 0x3
        val destAdjust = when (destAdjustMode) {
            1 -> -unit
            2 -> 0
            else -> unit
        }

        var src = internalSource[channel] and (unit - 1).inv()
        var dst = internalDestination[channel] and (unit - 1).inv()

        for (i in 0 until length) {
            if (words) {
                bus.write32(dst, bus.read32(src))
            } else {
                bus.write16(dst, bus.read16(src))
            }
            src += sourceAdjust
            dst += destAdjust
        }

        internalSource[channel] = src
        if (destAdjustMode == 3) {
            internalDestination[channel] = destination[channel]
        } else {
            internalDestination[channel] = dst
        }

        if (ctrl and 0x4000 != 0) {
            interrupts.request(Interrupts.DMA0 shl channel)
        }

        if (ctrl and 0x0200 == 0) {
            control[channel] = ctrl and 0x8000.inv()
        }
    }
}
