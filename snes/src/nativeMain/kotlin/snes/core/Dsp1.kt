package snes.core

import kapi.state.StateReader
import kapi.state.StateWriter

class Dsp1(private val hiRom: Boolean) : CoProcessor {
    override val id = ChipId.DSP1

    var debugReads = 0
        private set

    var debugWrites = 0
        private set

    var debugCommands = 0
        private set

    var debugLastCommand = -1
        private set

    var debugRaster = 0

    val debugHistogram = IntArray(64)

    private var command = 0
    private var sr = DRC or RQM
    private var srLowByte = false
    private var dr = IDLE
    private var fsm = WAIT_COMMAND
    private var dataCounter = 0
    private var frozen = false

    private val parameters = IntArray(64)
    private val output = IntArray(2048)

    private val matrixA = Array(3) { IntArray(3) }
    private val matrixB = Array(3) { IntArray(3) }
    private val matrixC = Array(3) { IntArray(3) }

    private var sinAas = 0
    private var cosAas = 0
    private var sinAzs = 0
    private var cosAzs = 0
    private var sinAzsClipped = 0
    private var cosAzsClipped = 0
    private var nx = 0
    private var ny = 0
    private var nz = 0
    private var gx = 0
    private var gy = 0
    private var gz = 0
    private var centreX = 0
    private var centreY = 0
    private var lesCoefficient = 0
    private var lesExponent = 0
    private var lesRaw = 0
    private var planeCoefficient = 0
    private var planeExponent = 0
    private var secantC1 = 0
    private var secantE1 = 0
    private var secantC2 = 0
    private var secantE2 = 0
    private var vOffset = 0

    private var polarX = 0
    private var polarY = 0
    private var polarZ = 0

    override fun reset() {
        command = 0
        sr = DRC or RQM
        srLowByte = false
        dr = IDLE
        fsm = WAIT_COMMAND
        dataCounter = 0
        frozen = false
    }

    private fun status(addr: Int): Boolean =
        if (hiRom) addr and 0x1000 != 0 else addr and 0x4000 != 0

    override fun read(addr: Int): Int {
        debugReads++

        if (status(addr)) {
            srLowByte = !srLowByte
            return if (srLowByte) 0 else sr
        }

        return step(true, 0)
    }

    override fun write(addr: Int, value: Int) {
        if (status(addr)) return
        debugWrites++

        step(false, value)
    }

    private fun step(reading: Boolean, value: Int): Int {
        if (sr and RQM == 0) return IDLE

        var out = 0

        if (reading) {
            out = if (sr and DRS != 0) (dr ushr 8) and 0xFF else dr and 0xFF
        } else if (sr and DRS != 0) {
            dr = (dr and 0x00FF) or ((value and 0xFF) shl 8)
        } else {
            dr = (dr and 0xFF00) or (value and 0xFF)
        }

        when (fsm) {
            WAIT_COMMAND -> {
                val opcode = dr and 0xFF

                if (opcode and 0xC0 == 0) {
                    if (opcode == 0x1A || opcode == 0x2A || opcode == 0x3A) {
                        command = opcode
                        frozen = true
                    } else {
                        command = opcode
                        debugCommands++
                        debugHistogram[opcode]++
                        debugLastCommand = opcode

                        dataCounter = 0
                        fsm = READ_DATA
                        sr = sr and DRC.inv()
                    }
                }
            }

            READ_DATA -> {
                sr = sr xor DRS

                if (sr and DRS == 0) {
                    parameters[dataCounter++] = dr and 0xFFFF

                    if (dataCounter >= READS[command]) {
                        execute()

                        if (WRITES[command] != 0) {
                            dataCounter = 0
                            dr = output[0]
                            fsm = WRITE_DATA
                        } else {
                            finish()
                        }
                    }
                }
            }

            else -> {
                sr = sr xor DRS

                if (sr and DRS == 0) {
                    dataCounter++

                    if (dataCounter < WRITES[command]) {
                        dr = output[dataCounter]
                    } else if (command == 0x0A && dr != 0x8000) {
                        parameters[0] = (parameters[0] + 1) and 0xFFFF
                        execute()
                        dataCounter = 0
                        dr = output[0]
                    } else {
                        finish()
                    }
                }
            }
        }

        if (frozen) sr = sr and RQM.inv()

        return out
    }

    private fun finish() {
        dr = IDLE
        fsm = WAIT_COMMAND
        sr = sr or DRC
    }

    private fun parameter(index: Int): Int = signed(parameters[index])

    private fun emit(index: Int, value: Int) {
        output[index] = value and 0xFFFF
    }

    private fun execute() {
        when (command) {
            0x00, 0x20 -> {
                var result = (parameter(0) * parameter(1)) shr 15
                if (command == 0x20) result++
                emit(0, result)
            }

            0x10, 0x30 -> {
                inverse(parameter(0), parameter(1))
                emit(0, inverseCoefficient)
                emit(1, inverseExponent)
            }

            0x04, 0x24, 0x44, 0x64 -> {
                val angle = parameter(0)
                val radius = parameter(1)
                emit(0, (sin(angle) * radius) shr 15)
                emit(1, (cos(angle) * radius) shr 15)
            }

            0x08 -> {
                val x = parameter(0)
                val y = parameter(1)
                val z = parameter(2)
                val size = (x * x + y * y + z * z) shl 1
                emit(0, size and 0xFFFF)
                emit(1, (size shr 16) and 0xFFFF)
            }

            0x18, 0x38 -> {
                val x = parameter(0)
                val y = parameter(1)
                val z = parameter(2)
                val r = parameter(3)
                var d = (x * x + y * y + z * z - r * r) shr 15
                if (command == 0x38) d++
                emit(0, d)
            }

            0x28 -> emit(0, distance(parameter(0), parameter(1), parameter(2)))

            0x0C, 0x2C -> {
                val angle = parameter(0)
                val x = parameter(1)
                val y = parameter(2)
                emit(0, ((y * sin(angle)) shr 15) + ((x * cos(angle)) shr 15))
                emit(1, ((y * cos(angle)) shr 15) - ((x * sin(angle)) shr 15))
            }

            0x1C, 0x3C -> polar()

            0x02, 0x12, 0x22, 0x32 -> setupProjection()

            0x0A, 0x1A -> rasterMatrix()

            0x06, 0x16, 0x26, 0x36 -> project()

            0x0E, 0x1E, 0x2E, 0x3E -> target()

            0x01, 0x05, 0x31, 0x35 -> attitude(matrixA)
            0x11, 0x15 -> attitude(matrixB)
            0x21, 0x25 -> attitude(matrixC)

            0x0D, 0x09, 0x39, 0x3D -> objective(matrixA)
            0x1D, 0x19 -> objective(matrixB)
            0x2D, 0x29 -> objective(matrixC)

            0x03, 0x33 -> subjective(matrixA)
            0x13 -> subjective(matrixB)
            0x23 -> subjective(matrixC)

            0x0B, 0x3B -> scalar(matrixA)
            0x1B -> scalar(matrixB)
            0x2B -> scalar(matrixC)

            0x14, 0x34 -> gyrate()

            0x0F, 0x17, 0x1F, 0x37 -> emit(0, 0x0000)

            0x27, 0x2F -> emit(0, 0x0100)

            else -> emit(0, 0)
        }
    }

    private fun attitude(matrix: Array<IntArray>) {
        val m = parameter(0) shr 1
        val z = parameter(1)
        val y = parameter(2)
        val x = parameter(3)

        val sz = sin(z)
        val cz = cos(z)
        val sy = sin(y)
        val cy = cos(y)
        val sx = sin(x)
        val cx = cos(x)

        matrix[0][0] = (((m * cz) shr 15) * cy) shr 15
        matrix[0][1] = -((((m * sz) shr 15) * cy) shr 15)
        matrix[0][2] = (m * sy) shr 15

        matrix[1][0] = ((((m * sz) shr 15) * cx) shr 15) + (((((m * cz) shr 15) * sx) shr 15) * sy shr 15)
        matrix[1][1] = ((((m * cz) shr 15) * cx) shr 15) - (((((m * sz) shr 15) * sx) shr 15) * sy shr 15)
        matrix[1][2] = -((((m * sx) shr 15) * cy) shr 15)

        matrix[2][0] = ((((m * sz) shr 15) * sx) shr 15) - (((((m * cz) shr 15) * cx) shr 15) * sy shr 15)
        matrix[2][1] = ((((m * cz) shr 15) * sx) shr 15) + (((((m * sz) shr 15) * cx) shr 15) * sy shr 15)
        matrix[2][2] = (((m * cx) shr 15) * cy) shr 15
    }

    private fun objective(matrix: Array<IntArray>) {
        val x = parameter(0)
        val y = parameter(1)
        val z = parameter(2)

        for (row in 0 until 3) {
            emit(row, ((x * matrix[row][0]) shr 15) + ((y * matrix[row][1]) shr 15) + ((z * matrix[row][2]) shr 15))
        }
    }

    private fun subjective(matrix: Array<IntArray>) {
        val f = parameter(0)
        val l = parameter(1)
        val u = parameter(2)

        for (column in 0 until 3) {
            emit(
                column,
                ((f * matrix[0][column]) shr 15) +
                    ((l * matrix[1][column]) shr 15) +
                    ((u * matrix[2][column]) shr 15),
            )
        }
    }

    private fun scalar(matrix: Array<IntArray>) {
        val x = parameter(0)
        val y = parameter(1)
        val z = parameter(2)

        emit(0, (x * matrix[0][0] + y * matrix[0][1] + z * matrix[0][2]) shr 15)
    }

    private fun polar() {
        val z = parameter(0)
        val y = parameter(1)
        val x = parameter(2)

        var bx = parameter(3)
        var by = parameter(4)
        var bz = parameter(5)

        var x1 = ((by * sin(z)) shr 15) + ((bx * cos(z)) shr 15)
        var y1 = ((by * cos(z)) shr 15) - ((bx * sin(z)) shr 15)

        bx = x1
        by = y1

        val z1 = ((bx * sin(y)) shr 15) + ((bz * cos(y)) shr 15)
        x1 = ((bx * cos(y)) shr 15) - ((bz * sin(y)) shr 15)

        polarX = x1
        bz = z1

        y1 = ((bz * sin(x)) shr 15) + ((by * cos(x)) shr 15)
        val z2 = ((bz * cos(x)) shr 15) - ((by * sin(x)) shr 15)

        polarY = y1
        polarZ = z2

        emit(0, polarX)
        emit(1, polarY)
        emit(2, polarZ)
    }

    private fun gyrate() {
        val zr = parameter(0)
        val yr = parameter(1)
        val xr = parameter(2)
        val u = parameter(3)
        val f = parameter(4)
        val l = parameter(5)

        inverse(cos(xr), 0)
        val secantC = inverseCoefficient
        val secantE = inverseExponent

        normalizeDouble(u * cos(yr) - f * sin(yr))
        var e = secantE - normalizedExponent
        normalize((normalizedCoefficient * secantC) shr 15, e)

        val zrr = zr + truncate(normalizedCoefficient, normalizedExponent)
        val xrr = xr + ((u * sin(yr)) shr 15) + ((f * cos(yr)) shr 15)

        normalizeDouble(u * cos(yr) + f * sin(yr))
        e = secantE - normalizedExponent

        val saved = normalizedCoefficient
        normalize(sin(xr), e)
        val tangent = (secantC * normalizedCoefficient) shr 15

        normalize(-((saved * tangent) shr 15), normalizedExponent)
        val yrr = yr + truncate(normalizedCoefficient, normalizedExponent) + l

        emit(0, zrr)
        emit(1, yrr)
        emit(2, xrr)
    }

    private fun setupProjection() {
        val fx = parameter(0)
        val fy = parameter(1)
        val fz = parameter(2)
        val lfe = parameter(3)
        val les = parameter(4)
        val aas = parameter(5)
        var azs = parameter(6)

        var clipped = azs

        sinAas = sin(aas)
        cosAas = cos(aas)
        sinAzs = sin(azs)
        cosAzs = cos(azs)

        nx = (sinAzs * -sinAas) shr 15
        ny = (sinAzs * cosAas) shr 15
        nz = (cosAzs * 0x7FFF) shr 15

        centreX = fx + ((lfe * nx) shr 15)
        centreY = fy + ((lfe * ny) shr 15)

        val centreZ = fz + ((lfe * nz) shr 15)

        gx = centreX - ((les * nx) shr 15)
        gy = centreY - ((les * ny) shr 15)
        gz = centreZ - ((les * nz) shr 15)

        normalize(les, 0)
        lesCoefficient = normalizedCoefficient
        lesExponent = normalizedExponent
        lesRaw = les

        normalize(centreZ, 0)
        planeCoefficient = normalizedCoefficient
        planeExponent = normalizedExponent

        var maxAzs = MAX_AZS[-planeExponent and 0x0F]

        if (clipped < 0) {
            maxAzs = -maxAzs
            if (clipped < maxAzs + 1) clipped = maxAzs + 1
        } else {
            if (clipped > maxAzs) clipped = maxAzs
        }

        sinAzsClipped = sin(clipped)
        cosAzsClipped = cos(clipped)

        inverse(cosAzsClipped, 0)
        secantC1 = inverseCoefficient
        secantE1 = inverseExponent

        normalize((planeCoefficient * secantC1) shr 15, planeExponent)
        var c = truncate(normalizedCoefficient, normalizedExponent + secantE1)
        c = (c * sinAzsClipped) shr 15

        centreX += (c * sinAas) shr 15
        centreY -= (c * cosAas) shr 15

        var vof = 0

        if (azs != clipped || azs == maxAzs) {
            if (azs == -32768) azs = -32767

            var value = azs - maxAzs
            if (value >= 0) value--

            val aux = signed16((value shl 2).inv())

            var t = (aux * Dsp1Tables.ROM[0x0328]) shr 15
            t = ((t * aux) shr 15) + Dsp1Tables.ROM[0x0327]
            vof -= (((t * aux) shr 15) * lesRaw) shr 15

            t = (aux * aux) shr 15
            val adjust = ((t * Dsp1Tables.ROM[0x0324]) shr 15) + Dsp1Tables.ROM[0x0325]
            cosAzsClipped += (((t * adjust) shr 15) * cosAzsClipped) shr 15
        }

        vOffset = (lesRaw * cosAzsClipped) shr 15

        inverse(sinAzsClipped, 0)
        val secant = inverseCoefficient

        normalize(vOffset, inverseExponent)
        normalize((normalizedCoefficient * secant) shr 15, normalizedExponent)

        if (normalizedCoefficient == -32768) {
            normalizedCoefficient = normalizedCoefficient shr 1
            normalizedExponent++
        }

        val vva = truncate(-normalizedCoefficient, normalizedExponent)

        inverse(cosAzsClipped, 0)
        secantC2 = inverseCoefficient
        secantE2 = inverseExponent

        emit(0, vof)
        emit(1, vva)
        emit(2, centreX)
        emit(3, centreY)
    }

    private fun rasterMatrix() {
        debugRaster++

        val line = parameter(0)

        inverse(((line * sinAzs) shr 15) + vOffset, 7)

        var e = inverseExponent + planeExponent
        val c1 = (inverseCoefficient * planeCoefficient) shr 15
        val e1 = e + secantE2

        normalize(c1, e)
        var c = truncate(normalizedCoefficient, normalizedExponent)

        emit(0, (c * cosAas) shr 15)
        emit(2, (c * sinAas) shr 15)

        normalize((c1 * secantC2) shr 15, e1)
        c = truncate(normalizedCoefficient, normalizedExponent)

        emit(1, (c * -sinAas) shr 15)
        emit(3, (c * cosAas) shr 15)

    }

    private fun project() {
        val x = parameter(0)
        val y = parameter(1)
        val z = parameter(2)

        normalizeDouble(x - gx)
        var px = normalizedCoefficient
        var e4 = normalizedExponent

        normalizeDouble(y - gy)
        var py = normalizedCoefficient
        var e = normalizedExponent

        normalizeDouble(z - gz)
        var pz = normalizedCoefficient
        var e3 = normalizedExponent

        px = px shr 1
        e4--
        py = py shr 1
        e--
        pz = pz shr 1
        e3--

        var ref = if (e < e3) e else e3
        if (e4 < ref) ref = e4

        px = shiftRight(px, e4 - ref)
        py = shiftRight(py, e - ref)
        pz = shiftRight(pz, e3 - ref)

        val c12 = -((px * nx) shr 15) - ((py * ny) shr 15) - ((pz * nz) shr 15)

        var aux4 = c12
        ref = 16 - ref

        aux4 = if (ref >= 0) aux4 shl ref else aux4 shr -ref
        if (aux4 == -1) aux4 = 0
        aux4 = aux4 shr 1

        val aux = (lesRaw and 0xFFFF) + aux4

        normalizeDouble(aux)
        val c10 = normalizedCoefficient
        val e2 = 15 - normalizedExponent

        inverse(c10, 0)
        val c2 = (inverseCoefficient * lesCoefficient) shr 15

        val c17 = ((px * ((cosAas * 0x7FFF) shr 15)) shr 15) + ((py * ((sinAas * 0x7FFF) shr 15)) shr 15)
        normalize((c17 * c2) shr 15, 0)
        emit(0, truncate(normalizedCoefficient, lesExponent - e2 + ref + normalizedExponent))

        val c24 = ((px * ((cosAzs * -sinAas) shr 15)) shr 15) +
            ((py * ((cosAzs * cosAas) shr 15)) shr 15) +
            ((pz * ((-sinAzs * 0x7FFF) shr 15)) shr 15)

        normalize((c24 * c2) shr 15, 0)
        emit(1, truncate(normalizedCoefficient, lesExponent - e2 + ref + normalizedExponent))

        normalize(c2, inverseExponent)
        emit(2, truncate(normalizedCoefficient, normalizedExponent + lesExponent - e2 - 7))
    }

    private fun target() {
        val h = parameter(0)
        val v = parameter(1)

        inverse(((v * sinAzs) shr 15) + vOffset, 8)

        val e = inverseExponent + planeExponent
        val c1 = (inverseCoefficient * planeCoefficient) shr 15
        val e1 = e + secantE1

        normalize(c1, e)
        var c = truncate(normalizedCoefficient, normalizedExponent)
        c = (c * (h shl 8)) shr 15

        var x = centreX + ((c * cosAas) shr 15)
        var y = centreY - ((c * sinAas) shr 15)

        normalize((c1 * secantC1) shr 15, e1)
        c = truncate(normalizedCoefficient, normalizedExponent)
        c = (c * (v shl 8)) shr 15

        x += (c * -sinAas) shr 15
        y += (c * cosAas) shr 15

        emit(0, x)
        emit(1, y)
    }

    private var inverseCoefficient = 0
    private var inverseExponent = 0
    private var normalizedCoefficient = 0
    private var normalizedExponent = 0

    private fun inverse(coefficient: Int, exponent: Int) {
        if (coefficient == 0) {
            inverseCoefficient = 0x7FFF
            inverseExponent = 0x002F
            return
        }

        var value = coefficient
        var e = exponent
        var sign = 1

        if (value < 0) {
            if (value < -32767) value = -32767
            value = -value
            sign = -1
        }

        while (value < 0x4000) {
            value = value shl 1
            e--
        }

        if (value == 0x4000) {
            if (sign == 1) {
                inverseCoefficient = 0x7FFF
            } else {
                inverseCoefficient = -0x4000
                e--
            }
        } else {
            var i = signed16(Dsp1Tables.ROM[((value - 0x4000) shr 7) + 0x0065])

            i = (i + signed16((-i * signed16((value * i) shr 15)) shr 15)) shl 1
            i = signed16(i)
            i = (i + signed16((-i * signed16((value * i) shr 15)) shr 15)) shl 1
            i = signed16(i)

            inverseCoefficient = signed16(i * sign)
        }

        inverseExponent = 1 - e
    }

    private fun normalize(m: Int, exponent: Int) {
        val value = signed16(m)

        var e = 0
        var i = 0x4000

        if (value < 0) {
            while (value and i != 0 && i != 0) {
                i = i shr 1
                e++
            }
        } else {
            while (value and i == 0 && i != 0) {
                i = i shr 1
                e++
            }
        }

        normalizedCoefficient = if (e > 0) signed16((value * Dsp1Tables.ROM[0x21 + e]) shl 1) else value
        normalizedExponent = exponent - e
    }

    private fun normalizeDouble(product: Int) {
        val n = product and 0x7FFF
        val m = signed16(product shr 15)

        var e = 0
        var i = 0x4000

        if (m < 0) {
            while (m and i != 0 && i != 0) {
                i = i shr 1
                e++
            }
        } else {
            while (m and i == 0 && i != 0) {
                i = i shr 1
                e++
            }
        }

        if (e > 0) {
            var coefficient = signed16((m * Dsp1Tables.ROM[0x0021 + e]) shl 1)

            if (e < 15) {
                coefficient = signed16(coefficient + ((n * Dsp1Tables.ROM[0x0040 - e]) shr 15))
            } else {
                i = 0x4000

                if (m < 0) {
                    while (n and i != 0 && i != 0) {
                        i = i shr 1
                        e++
                    }
                } else {
                    while (n and i == 0 && i != 0) {
                        i = i shr 1
                        e++
                    }
                }

                coefficient = if (e > 15) {
                    signed16((n * Dsp1Tables.ROM[0x0012 + e]) shl 1)
                } else {
                    signed16(coefficient + n)
                }
            }

            normalizedCoefficient = coefficient
        } else {
            normalizedCoefficient = m
        }

        normalizedExponent = e
    }

    private fun truncate(c: Int, e: Int): Int {
        if (e > 0) {
            if (c > 0) return 32767
            if (c < 0) return -32767
            return c
        }

        if (e < 0) return shiftRight(c, e)

        return c
    }

    private fun shiftRight(c: Int, e: Int): Int {
        val index = 0x31 + e
        if (index < 0 || index >= Dsp1Tables.ROM.size) return 0
        return (c * Dsp1Tables.ROM[index]) shr 15
    }

    private fun sin(angle: Int): Int {
        val a = signed16(angle)

        if (a < 0) {
            if (a == -32768) return 0
            return -sin(-a)
        }

        var s = Dsp1Tables.SIN[a shr 8] + ((Dsp1Tables.MUL[a and 0xFF] * Dsp1Tables.SIN[0x40 + (a shr 8)]) shr 15)
        if (s > 32767) s = 32767

        return signed16(s)
    }

    private fun cos(angle: Int): Int {
        var a = signed16(angle)

        if (a < 0) {
            if (a == -32768) return -32768
            a = -a
        }

        var s = Dsp1Tables.SIN[0x40 + (a shr 8)] - ((Dsp1Tables.MUL[a and 0xFF] * Dsp1Tables.SIN[a shr 8]) shr 15)
        if (s < -32768) s = -32767

        return signed16(s)
    }

    private fun distance(x: Int, y: Int, z: Int): Int {
        val radius = x * x + y * y + z * z
        if (radius == 0) return 0

        normalizeDouble(radius)

        var c = normalizedCoefficient
        val e = normalizedExponent

        if (e and 1 != 0) c = (c * 0x4000) shr 15

        val position = (c * 0x0040) shr 15

        val node1 = signed16(Dsp1Tables.ROM[0x00D5 + position])
        val node2 = signed16(Dsp1Tables.ROM[0x00D6 + position])

        var result = (((node2 - node1) * (c and 0x1FF)) shr 9) + node1
        result = result shr (e shr 1)

        return signed16(result)
    }

    private fun signed(value: Int): Int = signed16(value)

    private fun signed16(value: Int): Int {
        val masked = value and 0xFFFF
        return if (masked and 0x8000 != 0) masked - 0x10000 else masked
    }

    override fun save(writer: StateWriter) {
        writer.int(command)
        writer.int(sr)
        writer.int(dr)
        writer.int(fsm)
        writer.int(dataCounter)
        writer.bool(srLowByte)
        writer.bool(frozen)
        writer.ints(parameters)
        writer.ints(output)

        for (row in 0 until 3) {
            writer.ints(matrixA[row])
            writer.ints(matrixB[row])
            writer.ints(matrixC[row])
        }

        writer.ints(
            intArrayOf(
                sinAas, cosAas, sinAzs, cosAzs, sinAzsClipped, cosAzsClipped,
                nx, ny, nz, gx, gy, gz, centreX, centreY,
                lesCoefficient, lesExponent, lesRaw, planeCoefficient, planeExponent,
                secantC1, secantE1, secantC2, secantE2, vOffset,
                polarX, polarY, polarZ,
            ),
        )
    }

    override fun load(reader: StateReader) {
        command = reader.int()
        sr = reader.int()
        dr = reader.int()
        fsm = reader.int()
        dataCounter = reader.int()
        srLowByte = reader.bool()
        frozen = reader.bool()
        reader.ints(parameters)
        reader.ints(output)

        for (row in 0 until 3) {
            reader.ints(matrixA[row])
            reader.ints(matrixB[row])
            reader.ints(matrixC[row])
        }

        val state = IntArray(27)
        reader.ints(state)

        sinAas = state[0]
        cosAas = state[1]
        sinAzs = state[2]
        cosAzs = state[3]
        sinAzsClipped = state[4]
        cosAzsClipped = state[5]
        nx = state[6]
        ny = state[7]
        nz = state[8]
        gx = state[9]
        gy = state[10]
        gz = state[11]
        centreX = state[12]
        centreY = state[13]
        lesCoefficient = state[14]
        lesExponent = state[15]
        lesRaw = state[16]
        planeCoefficient = state[17]
        planeExponent = state[18]
        secantC1 = state[19]
        secantE1 = state[20]
        secantC2 = state[21]
        secantE2 = state[22]
        vOffset = state[23]
        polarX = state[24]
        polarY = state[25]
        polarZ = state[26]
    }

    companion object {
        private const val DRC = 0x04
        private const val DRS = 0x10
        private const val RQM = 0x80
        private const val IDLE = 0x0080

        private const val WAIT_COMMAND = 0
        private const val READ_DATA = 1
        private const val WRITE_DATA = 2

        private val MAX_AZS = intArrayOf(
            0x38B4, 0x38B7, 0x38BA, 0x38BE, 0x38C0, 0x38C4, 0x38C7, 0x38CA,
            0x38CE, 0x38D0, 0x38D4, 0x38D7, 0x38DA, 0x38DD, 0x38E0, 0x38E4,
        )

        private val READS = intArrayOf(
            2, 4, 7, 3, 2, 4, 3, 1,
            3, 3, 1, 3, 3, 3, 2, 1,
            2, 4, 7, 3, 6, 4, 3, 1,
            4, 3, 0, 3, 6, 3, 2, 1,
            2, 4, 7, 3, 2, 4, 3, 1,
            3, 3, 0, 3, 3, 3, 2, 1,
            2, 4, 7, 3, 6, 4, 3, 1,
            4, 3, 0, 3, 6, 3, 2, 1,
        )

        private val WRITES = intArrayOf(
            1, 0, 4, 3, 2, 0, 3, 1,
            2, 3, 4, 1, 2, 3, 2, 1,
            2, 0, 4, 3, 3, 0, 3, 1024,
            1, 3, 0, 1, 3, 3, 2, 1024,
            1, 0, 4, 3, 2, 0, 3, 1,
            1, 3, 0, 1, 2, 3, 2, 1,
            2, 0, 4, 3, 3, 0, 3, 1024,
            1, 3, 0, 1, 3, 3, 2, 1024,
        )
    }
}
