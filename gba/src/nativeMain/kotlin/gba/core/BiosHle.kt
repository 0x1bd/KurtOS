package gba.core

object BiosHle {
    private val sineTable = IntArray(256) { index ->
        val angle = index.toDouble() * 2.0 * PI / 256.0
        (sin(angle) * 16384.0).toInt()
    }

    fun handle(cpu: Arm7, bus: Bus, comment: Int) {
        when (comment) {
            0x00 -> cpu.pc = 0x08000000
            0x01 -> registerRamReset(cpu, bus)
            0x02 -> cpu.halted = true
            0x03 -> cpu.halted = true

            0x04 -> cpu.requestIntrWait(cpu.r[0] != 0, cpu.r[1])
            0x05 -> cpu.requestIntrWait(true, Interrupts.VBLANK)

            0x06 -> {
                val number = cpu.r[0]
                val denominator = cpu.r[1]
                if (denominator != 0) {
                    val quotient = number / denominator
                    cpu.r[0] = quotient
                    cpu.r[1] = number % denominator
                    cpu.r[3] = if (quotient < 0) -quotient else quotient
                }
            }

            0x07 -> {
                val number = cpu.r[1]
                val denominator = cpu.r[0]
                if (denominator != 0) {
                    val quotient = number / denominator
                    cpu.r[0] = quotient
                    cpu.r[1] = number % denominator
                    cpu.r[3] = if (quotient < 0) -quotient else quotient
                }
            }

            0x08 -> cpu.r[0] = squareRoot(cpu.r[0].toLong() and 0xFFFFFFFFL)

            0x09 -> cpu.r[0] = arcTan(cpu.r[0])
            0x0A -> cpu.r[0] = arcTan2(cpu.r[0], cpu.r[1])

            0x0B -> cpuSet(cpu, bus)
            0x0C -> cpuFastSet(cpu, bus)

            0x0D -> cpu.r[0] = 0xBAAE187F.toInt()

            0x0E -> bgAffineSet(cpu, bus)
            0x0F -> objAffineSet(cpu, bus)

            0x10 -> bitUnpack(cpu, bus)
            0x11 -> lz77(cpu, bus, 1)
            0x12 -> lz77(cpu, bus, 2)
            0x14 -> runLength(cpu, bus, 1)
            0x15 -> runLength(cpu, bus, 2)
        }
    }

    private fun registerRamReset(cpu: Arm7, bus: Bus) {
        val flags = cpu.r[0]

        if (flags and 0x01 != 0) bus.ewram.fill(0)
        if (flags and 0x02 != 0) bus.iwram.fill(0, 0, 0x7E00)
        if (flags and 0x04 != 0) bus.ppu.palette.fill(0)
        if (flags and 0x08 != 0) bus.ppu.vram.fill(0)
        if (flags and 0x10 != 0) bus.ppu.oam.fill(0)

        bus.write16(0x04000000, 0x0080)
    }

    private fun squareRoot(value: Long): Int {
        if (value <= 0) return 0
        var root = 0L
        var bit = 1L shl 30
        var remainder = value
        while (bit > remainder) bit = bit shr 2
        while (bit != 0L) {
            if (remainder >= root + bit) {
                remainder -= root + bit
                root = (root shr 1) + bit
            } else {
                root = root shr 1
            }
            bit = bit shr 2
        }
        return root.toInt()
    }

    private fun arcTan(x: Int): Int {
        var a = -(x * x) shr 14
        var b = ((0xA9 * a) shr 14) + 0x390
        b = ((b * a) shr 14) + 0x91C
        b = ((b * a) shr 14) + 0xFB6
        b = ((b * a) shr 14) + 0x16AA
        b = ((b * a) shr 14) + 0x2081
        b = ((b * a) shr 14) + 0x3651
        b = ((b * a) shr 14) + 0xA2F9
        return (x * b) shr 16
    }

    private fun arcTan2(x: Int, y: Int): Int {
        if (y == 0) return if (x < 0) 0x8000 else 0
        if (x == 0) return if (y < 0) 0xC000 else 0x4000

        return when {
            y >= 0 && x >= 0 && x >= y -> arcTan((y shl 14) / x)
            x < 0 && -x >= (if (y < 0) -y else y) -> 0x8000 + arcTan((y shl 14) / x)
            y >= 0 -> 0x4000 - arcTan((x shl 14) / y)
            else -> 0xC000 - arcTan((x shl 14) / y)
        } and 0xFFFF
    }

    private fun cpuSet(cpu: Arm7, bus: Bus) {
        var source = cpu.r[0]
        var destination = cpu.r[1]
        val control = cpu.r[2]
        val count = control and 0x1FFFFF
        val fill = control and 0x01000000 != 0
        val words = control and 0x04000000 != 0

        if (words) {
            source = source and 3.inv()
            destination = destination and 3.inv()
            if (fill) {
                val value = bus.read32(source)
                for (i in 0 until count) {
                    bus.write32(destination, value)
                    destination += 4
                }
            } else {
                for (i in 0 until count) {
                    bus.write32(destination, bus.read32(source))
                    source += 4
                    destination += 4
                }
            }
        } else {
            source = source and 1.inv()
            destination = destination and 1.inv()
            if (fill) {
                val value = bus.read16(source)
                for (i in 0 until count) {
                    bus.write16(destination, value)
                    destination += 2
                }
            } else {
                for (i in 0 until count) {
                    bus.write16(destination, bus.read16(source))
                    source += 2
                    destination += 2
                }
            }
        }
    }

    private fun cpuFastSet(cpu: Arm7, bus: Bus) {
        var source = cpu.r[0] and 3.inv()
        var destination = cpu.r[1] and 3.inv()
        val control = cpu.r[2]
        val count = ((control and 0x1FFFFF) + 7) and 7.inv()
        val fill = control and 0x01000000 != 0

        if (fill) {
            val value = bus.read32(source)
            for (i in 0 until count) {
                bus.write32(destination, value)
                destination += 4
            }
        } else {
            for (i in 0 until count) {
                bus.write32(destination, bus.read32(source))
                source += 4
                destination += 4
            }
        }
    }

    private fun bgAffineSet(cpu: Arm7, bus: Bus) {
        var source = cpu.r[0]
        var destination = cpu.r[1]
        val count = cpu.r[2]

        for (i in 0 until count) {
            val originX = bus.read32(source)
            val originY = bus.read32(source + 4)
            val displayX = bus.read16(source + 8).toShort().toInt()
            val displayY = bus.read16(source + 10).toShort().toInt()
            val scaleX = bus.read16(source + 12).toShort().toInt()
            val scaleY = bus.read16(source + 14).toShort().toInt()
            val angle = (bus.read16(source + 16) ushr 8) and 0xFF
            source += 20

            val sinValue = sineTable[angle]
            val cosValue = sineTable[(angle + 64) and 0xFF]

            val pa = (scaleX * cosValue) shr 14
            val pb = -((scaleX * sinValue) shr 14)
            val pc = (scaleY * sinValue) shr 14
            val pd = (scaleY * cosValue) shr 14

            bus.write16(destination, pa and 0xFFFF)
            bus.write16(destination + 2, pb and 0xFFFF)
            bus.write16(destination + 4, pc and 0xFFFF)
            bus.write16(destination + 6, pd and 0xFFFF)
            bus.write32(destination + 8, originX - pa * displayX - pb * displayY)
            bus.write32(destination + 12, originY - pc * displayX - pd * displayY)
            destination += 16
        }
    }

    private fun objAffineSet(cpu: Arm7, bus: Bus) {
        var source = cpu.r[0]
        var destination = cpu.r[1]
        val count = cpu.r[2]
        val stride = cpu.r[3]

        for (i in 0 until count) {
            val scaleX = bus.read16(source).toShort().toInt()
            val scaleY = bus.read16(source + 2).toShort().toInt()
            val angle = (bus.read16(source + 4) ushr 8) and 0xFF
            source += 8

            val sinValue = sineTable[angle]
            val cosValue = sineTable[(angle + 64) and 0xFF]

            bus.write16(destination, ((scaleX * cosValue) shr 14) and 0xFFFF)
            bus.write16(destination + stride, (-((scaleX * sinValue) shr 14)) and 0xFFFF)
            bus.write16(destination + stride * 2, ((scaleY * sinValue) shr 14) and 0xFFFF)
            bus.write16(destination + stride * 3, ((scaleY * cosValue) shr 14) and 0xFFFF)
            destination += stride * 4
        }
    }

    private fun bitUnpack(cpu: Arm7, bus: Bus) {
        var source = cpu.r[0]
        var destination = cpu.r[1]
        val info = cpu.r[2]

        val length = bus.read16(info)
        val sourceWidth = bus.read8(info + 2)
        val targetWidth = bus.read8(info + 3)
        val extra = bus.read32(info + 4)
        val offset = extra and 0x7FFFFFFF
        val offsetZero = extra < 0

        var outBuffer = 0
        var outBits = 0

        for (i in 0 until length) {
            val byte = bus.read8(source)
            source++

            var bit = 0
            while (bit < 8) {
                val mask = (1 shl sourceWidth) - 1
                var value = (byte ushr bit) and mask
                if (value != 0 || offsetZero) value += offset

                outBuffer = outBuffer or (value shl outBits)
                outBits += targetWidth

                if (outBits >= 32) {
                    bus.write32(destination, outBuffer)
                    destination += 4
                    outBuffer = 0
                    outBits = 0
                }

                bit += sourceWidth
            }
        }

        if (outBits > 0) bus.write32(destination, outBuffer)
    }

    private fun lz77(cpu: Arm7, bus: Bus, unit: Int) {
        var source = cpu.r[0]
        var destination = cpu.r[1]

        val header = bus.read32(source)
        var remaining = header ushr 8
        source += 4

        var halfword = 0
        var halfwordShift = 0

        fun writeByte(value: Int) {
            if (unit == 2) {
                halfword = halfword or (value shl halfwordShift)
                halfwordShift += 8
                if (halfwordShift == 16) {
                    bus.write16(destination and 1.inv(), halfword)
                    destination += 2
                    halfword = 0
                    halfwordShift = 0
                }
            } else {
                bus.write8(destination, value)
                destination++
            }
        }

        fun readBack(distance: Int): Int {
            val address = destination - distance + (if (unit == 2 && halfwordShift == 8) 1 else 0)
            return if (unit == 2) {
                val aligned = bus.read16(address and 1.inv())
                if (address and 1 == 0) aligned and 0xFF else (aligned ushr 8) and 0xFF
            } else {
                bus.read8(address)
            }
        }

        while (remaining > 0) {
            val flags = bus.read8(source)
            source++

            for (block in 7 downTo 0) {
                if (remaining <= 0) break

                if (flags and (1 shl block) == 0) {
                    writeByte(bus.read8(source))
                    source++
                    remaining--
                } else {
                    val first = bus.read8(source)
                    val second = bus.read8(source + 1)
                    source += 2

                    val length = (first ushr 4) + 3
                    val distance = ((first and 0xF) shl 8 or second) + 1

                    for (i in 0 until length) {
                        if (remaining <= 0) break
                        writeByte(readBack(distance))
                        remaining--
                    }
                }
            }
        }
    }

    private fun runLength(cpu: Arm7, bus: Bus, unit: Int) {
        var source = cpu.r[0]
        var destination = cpu.r[1]

        val header = bus.read32(source)
        var remaining = header ushr 8
        source += 4

        var halfword = 0
        var halfwordShift = 0

        fun writeByte(value: Int) {
            if (unit == 2) {
                halfword = halfword or (value shl halfwordShift)
                halfwordShift += 8
                if (halfwordShift == 16) {
                    bus.write16(destination and 1.inv(), halfword)
                    destination += 2
                    halfword = 0
                    halfwordShift = 0
                }
            } else {
                bus.write8(destination, value)
                destination++
            }
        }

        while (remaining > 0) {
            val control = bus.read8(source)
            source++

            if (control and 0x80 != 0) {
                val length = (control and 0x7F) + 3
                val value = bus.read8(source)
                source++
                for (i in 0 until length) {
                    if (remaining <= 0) break
                    writeByte(value)
                    remaining--
                }
            } else {
                val length = (control and 0x7F) + 1
                for (i in 0 until length) {
                    if (remaining <= 0) break
                    writeByte(bus.read8(source))
                    source++
                    remaining--
                }
            }
        }
    }

    private const val PI = 3.141592653589793

    private fun sin(x: Double): Double {
        var value = x
        while (value > PI) value -= 2.0 * PI
        while (value < -PI) value += 2.0 * PI

        val square = value * value
        var result = value
        var term = value

        term *= -square / 6.0
        result += term
        term *= -square / 20.0
        result += term
        term *= -square / 42.0
        result += term
        term *= -square / 72.0
        result += term

        return result
    }
}
