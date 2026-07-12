package gba.core

import kapi.state.StateReader
import kapi.state.StateWriter

class Sio(private val interrupts: Interrupts) {
    private val multi = IntArray(4) { 0xFFFF }
    private var control = 0
    private var send = 0xFFFF
    private var data32Low = 0xFFFF
    private var data32High = 0xFFFF
    private var rcnt = 0

    fun save(writer: StateWriter) {
        writer.ints(multi)
        writer.int(control)
        writer.int(send)
        writer.int(data32Low)
        writer.int(data32High)
        writer.int(rcnt)
    }

    fun load(reader: StateReader) {
        reader.ints(multi)
        control = reader.int()
        send = reader.int()
        data32Low = reader.int()
        data32High = reader.int()
        rcnt = reader.int()
    }

    fun ioRead(offset: Int): Int = when (offset) {
        0x120 -> if (mode() == MODE_MULTI) multi[0] else data32Low
        0x122 -> if (mode() == MODE_MULTI) multi[1] else data32High
        0x124 -> multi[2]
        0x126 -> multi[3]
        0x128 -> control
        0x12A -> send
        0x134 -> readRcnt()
        else -> 0
    }

    fun ioWrite(offset: Int, value: Int) {
        when (offset) {
            0x120 -> data32Low = value
            0x122 -> data32High = value
            0x128 -> writeControl(value)
            0x12A -> send = value
            0x134 -> rcnt = value
        }
    }

    private fun mode(): Int = when {
        rcnt and 0x8000 != 0 -> MODE_GPIO
        control and 0x3000 == 0x2000 -> MODE_MULTI
        control and 0x3000 == 0x3000 -> MODE_UART
        else -> MODE_NORMAL
    }

    private fun readRcnt(): Int {
        if (rcnt and 0x8000 == 0) return rcnt

        val outputs = (rcnt ushr 4) and 0xF
        return (rcnt and 0xFFF0) or (rcnt and outputs)
    }

    private fun writeControl(value: Int) {
        control = value

        if (value and 0x0080 == 0) return

        when (mode()) {
            MODE_MULTI -> {
                multi[0] = send
                multi[1] = 0xFFFF
                multi[2] = 0xFFFF
                multi[3] = 0xFFFF
                control = control and 0x0080.inv() and 0x0030.inv()
            }

            MODE_NORMAL -> {
                data32Low = 0xFFFF
                data32High = 0xFFFF
                control = control and 0x0080.inv()
            }

            else -> return
        }

        if (value and 0x4000 != 0) interrupts.request(Interrupts.SERIAL)
    }

    private companion object {
        const val MODE_NORMAL = 0
        const val MODE_MULTI = 1
        const val MODE_UART = 2
        const val MODE_GPIO = 3
    }
}
