package snes.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Dsp1Test {
    private val dsp = Dsp1(hiRom = false)

    private val dataRegister = 0x308000
    private val statusRegister = 0x30C000

    private val drc = 0x04
    private val rqm = 0x80

    private fun command(id: Int, vararg words: Int): IntArray {
        dsp.write(dataRegister, id)

        for (word in words) {
            dsp.write(dataRegister, word and 0xFF)
            dsp.write(dataRegister, (word shr 8) and 0xFF)
        }

        val results = IntArray(OUT[id])

        for (i in results.indices) {
            val low = dsp.read(dataRegister)
            val high = dsp.read(dataRegister)
            results[i] = low or (high shl 8)
        }

        return results
    }

    private fun signed(value: Int): Int = if (value and 0x8000 != 0) value - 0x10000 else value

    @Test
    fun statusRegisterReportsReady() {
        assertEquals(0x00, dsp.read(statusRegister))
        assertEquals(rqm or drc, dsp.read(statusRegister))
    }

    @Test
    fun multiplyReturnsFixedPointProduct() {
        val result = signed(command(0x00, 0x4000, 0x4000)[0])
        assertEquals(0x2000, result)

        val negative = signed(command(0x00, 0xC000, 0x4000)[0])
        assertEquals(-0x2000, negative)
    }

    @Test
    fun multiplyTwoAddsOne() {
        val result = signed(command(0x20, 0x4000, 0x4000)[0])
        assertEquals(0x2001, result)
    }

    @Test
    fun inverseProducesReciprocal() {
        val result = command(0x10, 0x4000, 0x0000)

        val coefficient = signed(result[0])
        val exponent = signed(result[1])

        val value = coefficient.toDouble() * pow2(exponent - 15)

        assertTrue(abs(value - 2.0) < 0.01, "1/0.5 gave $value")
    }

    @Test
    fun inverseOfZeroSaturates() {
        val result = command(0x10, 0x0000, 0x0000)

        assertEquals(0x7FFF, result[0])
        assertEquals(0x002F, result[1])
    }

    @Test
    fun triangleMatchesTrigonometry() {
        for (degrees in intArrayOf(0, 30, 45, 90, 135, 180, 270)) {
            val angle = ((degrees / 360.0) * 65536.0).toInt() and 0xFFFF
            val radius = 0x4000

            val result = command(0x04, angle, radius)

            val gotSin = signed(result[0]).toDouble() / radius
            val gotCos = signed(result[1]).toDouble() / radius

            val radians = degrees * PI / 180.0

            assertTrue(abs(gotSin - sin(radians)) < 0.01, "sin($degrees) = $gotSin")
            assertTrue(abs(gotCos - cos(radians)) < 0.01, "cos($degrees) = $gotCos")
        }
    }

    @Test
    fun radiusReturnsSquaredLength() {
        val result = command(0x08, 0x1000, 0x1000, 0x1000)

        val low = result[0]
        val high = result[1]
        val size = (high shl 16) or low

        val expected = (0x1000 * 0x1000 * 3) shl 1

        assertEquals(expected, size)
    }

    @Test
    fun distanceApproximatesEuclideanNorm() {
        val result = signed(command(0x28, 0x3000, 0x4000, 0x0000)[0])

        val expected = 0x5000

        assertTrue(
            abs(result - expected) < 0x100,
            "distance $result expected about $expected",
        )
    }

    @Test
    fun rotateAppliesTwoDimensionalRotation() {
        val quarter = 0x4000

        val result = command(0x0C, quarter, 0x2000, 0x0000)

        val x = signed(result[0])
        val y = signed(result[1])

        assertTrue(abs(x) < 0x40, "x should be near zero, got $x")
        assertTrue(abs(y + 0x2000) < 0x40, "y should be near -0x2000, got $y")
    }

    @Test
    fun attitudeWithoutRotationHalvesVector() {
        command(0x01, 0x7FFF, 0x0000, 0x0000, 0x0000)

        val result = command(0x0D, 0x2000, 0x1000, 0x0800)

        assertTrue(abs(signed(result[0]) - 0x1000) < 8, "f ${signed(result[0])}")
        assertTrue(abs(signed(result[1]) - 0x0800) < 8, "l ${signed(result[1])}")
        assertTrue(abs(signed(result[2]) - 0x0400) < 8, "u ${signed(result[2])}")
    }

    @Test
    fun attitudeAndSubjectiveInvertObjectiveWithMatrixScale() {
        command(0x01, 0x7FFF, 0x1000, 0x0800, 0x0400)

        val forward = command(0x0D, 0x2000, 0x1000, 0x0800)
        val back = command(0x03, forward[0], forward[1], forward[2])

        assertTrue(abs(signed(back[0]) - 0x0800) < 0x40, "x round trip ${signed(back[0])}")
        assertTrue(abs(signed(back[1]) - 0x0400) < 0x40, "y round trip ${signed(back[1])}")
        assertTrue(abs(signed(back[2]) - 0x0200) < 0x40, "z round trip ${signed(back[2])}")
    }

    @Test
    fun projectionSetupProducesCentre() {
        val result = command(0x02, 0x0000, 0x0000, 0x0100, 0x0000, 0x0100, 0x0000, 0x2000)

        assertEquals(4, result.size)
    }

    @Test
    fun rasterProducesMatrixPerScanline() {
        command(0x02, 0x0000, 0x0000, 0x0100, 0x0000, 0x0100, 0x0000, 0x2000)

        val first = command(0x0A, 0x0000)

        assertEquals(4, first.size)

        var nonZero = 0
        for (value in first) if (value != 0) nonZero++

        assertTrue(nonZero > 0, "raster matrix was all zero")
    }

    @Test
    fun cartridgeMapsChipRegisters() {
        val image = ByteArray(0x80000)
        val header = 0x7FC0

        for (i in 0 until 21) image[header + i] = 0x20
        image[header + 0x15] = 0x20
        image[header + 0x16] = 0x03
        image[header + 0x17] = 0x0A
        image[header + 0x1E] = 0xFF.toByte()
        image[header + 0x1F] = 0xFF.toByte()
        image[header + 0x3C] = 0x00
        image[header + 0x3D] = 0x80.toByte()
        image[0] = 0x78

        val cartridge = Cartridge(image)

        assertEquals(ChipId.DSP1, cartridge.chip)
        assertEquals(0x00, cartridge.read(0x30C000))
        assertEquals(rqm or drc, cartridge.read(0x30C000))
    }

    private fun pow2(exponent: Int): Double {
        var value = 1.0
        var count = exponent

        while (count > 0) {
            value *= 2.0
            count--
        }

        while (count < 0) {
            value /= 2.0
            count++
        }

        return value
    }

    companion object {
        private val OUT = IntArray(64).apply {
            this[0x00] = 1; this[0x20] = 1; this[0x10] = 2; this[0x04] = 2
            this[0x08] = 2; this[0x28] = 1; this[0x0C] = 2; this[0x02] = 4
            this[0x0A] = 4; this[0x0D] = 3; this[0x03] = 3; this[0x01] = 3
        }
    }
}
