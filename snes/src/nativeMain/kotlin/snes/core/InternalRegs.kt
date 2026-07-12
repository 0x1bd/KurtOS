package snes.core

import kapi.state.StateReader
import kapi.state.StateWriter

class InternalRegs(private val bus: Bus, private val joypad: Joypad) {
    var nmitimen = 0
        private set

    var htime = 0x1FF
        private set

    var vtime = 0x1FF
        private set

    var nmiFlag = false
    var irqFlag = false

    var vblank = false
    var hblank = false
    var autoBusy = false

    var dmaPending = 0
    var hdmaEnabled = 0

    private var wrio = 0xFF
    private var multiplicand = 0xFF
    private var dividend = 0xFFFF
    private var product = 0
    private var quotient = 0

    val debugNmitimen get() = nmitimen
    var debugHdmaWrites = 0
    var debugHdmaNonZero = 0
    var debugGpdmaWrites = 0
    var debugLastHdma = -1
    var debugLine = 0
    var debugTrace = false

    val nmiEnabled get() = nmitimen and 0x80 != 0
    val irqMode get() = (nmitimen ushr 4) and 3
    val autoJoypad get() = nmitimen and 1 != 0

    fun reset() {
        nmitimen = 0
        htime = 0x1FF
        vtime = 0x1FF
        nmiFlag = false
        irqFlag = false
        vblank = false
        hblank = false
        autoBusy = false
        dmaPending = 0
        hdmaEnabled = 0
        wrio = 0xFF
        multiplicand = 0xFF
        dividend = 0xFFFF
        product = 0
        quotient = 0
    }

    fun read(addr: Int): Int = when (addr) {
        0x4210 -> {
            val value = (if (nmiFlag) 0x80 else 0) or CPU_VERSION or (bus.mdr and 0x70)
            nmiFlag = false
            value
        }

        0x4211 -> {
            val value = (if (irqFlag) 0x80 else 0) or (bus.mdr and 0x7F)
            irqFlag = false
            value
        }

        0x4212 -> {
            (if (vblank) 0x80 else 0) or
                (if (hblank) 0x40 else 0) or
                (if (autoBusy) 0x01 else 0) or
                (bus.mdr and 0x3E)
        }

        0x4213 -> wrio
        0x4214 -> quotient and 0xFF
        0x4215 -> (quotient ushr 8) and 0xFF
        0x4216 -> product and 0xFF
        0x4217 -> (product ushr 8) and 0xFF
        in 0x4218..0x421F -> joypad.readAuto(addr)
        else -> -1
    }

    fun write(addr: Int, value: Int) {
        when (addr) {
            0x4200 -> {
                if (debugTrace) println("[w] line=$debugLine \$4200 = 0x${value.toString(16)}")
                nmitimen = value
                if (!nmiEnabled) nmiFlag = false
                if (irqMode == 0) irqFlag = false
            }

            0x4201 -> wrio = value
            0x4202 -> multiplicand = value

            0x4203 -> {
                product = multiplicand * value
                quotient = value or (value shl 8)
            }

            0x4204 -> dividend = (dividend and 0xFF00) or value
            0x4205 -> dividend = (dividend and 0x00FF) or (value shl 8)

            0x4206 -> {
                if (value == 0) {
                    quotient = 0xFFFF
                    product = dividend
                } else {
                    quotient = dividend / value
                    product = dividend % value
                }
            }

            0x4207 -> htime = (htime and 0x100) or value
            0x4208 -> htime = (htime and 0x0FF) or ((value and 1) shl 8)
            0x4209 -> vtime = (vtime and 0x100) or value
            0x420A -> vtime = (vtime and 0x0FF) or ((value and 1) shl 8)
            0x420B -> {
                debugGpdmaWrites++
                dmaPending = value
            }

            0x420C -> {
                if (debugTrace) {
                    val c = bus.cpu
                    val at = if (c != null) "${c.pbr.toString(16)}:${c.pc.toString(16)}" else "?"
                    println("[w] line=$debugLine \$420C = 0x${value.toString(16)} from $at irqLine=${c?.irqLine} p=${c?.p?.toString(16)}")
                }
                debugHdmaWrites++
                if (value != 0) debugHdmaNonZero++
                debugLastHdma = value
                hdmaEnabled = value
            }
            0x420D -> bus.fastRom = value and 1 != 0
        }
    }

    fun save(writer: StateWriter) {
        writer.int(nmitimen)
        writer.int(htime)
        writer.int(vtime)
        writer.bool(nmiFlag)
        writer.bool(irqFlag)
        writer.bool(vblank)
        writer.bool(hblank)
        writer.bool(autoBusy)
        writer.int(dmaPending)
        writer.int(hdmaEnabled)
        writer.int(wrio)
        writer.int(multiplicand)
        writer.int(dividend)
        writer.int(product)
        writer.int(quotient)
    }

    fun load(reader: StateReader) {
        nmitimen = reader.int()
        htime = reader.int()
        vtime = reader.int()
        nmiFlag = reader.bool()
        irqFlag = reader.bool()
        vblank = reader.bool()
        hblank = reader.bool()
        autoBusy = reader.bool()
        dmaPending = reader.int()
        hdmaEnabled = reader.int()
        wrio = reader.int()
        multiplicand = reader.int()
        dividend = reader.int()
        product = reader.int()
        quotient = reader.int()
    }

    companion object {
        private const val CPU_VERSION = 2
    }
}
