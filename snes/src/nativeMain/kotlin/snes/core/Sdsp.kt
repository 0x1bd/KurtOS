package snes.core

import kapi.state.StateReader
import kapi.state.StateWriter

class Sdsp(private val ram: ByteArray) {
    val regs = IntArray(128)

    var outLeft = 0
        private set

    var outRight = 0
        private set

    private val envelope = IntArray(VOICES)
    private val hiddenEnv = IntArray(VOICES)
    private val envMode = IntArray(VOICES)
    private val brrAddress = IntArray(VOICES)
    private val brrOffset = IntArray(VOICES)
    private val brrHeader = IntArray(VOICES)
    private val interpPos = IntArray(VOICES)
    private val bufferPos = IntArray(VOICES)
    private val konDelay = IntArray(VOICES)
    private val output = IntArray(VOICES)
    private val buffer = Array(VOICES) { IntArray(BUFFER * 2) }

    private var counter = 0
    private var noise = 0x4000
    private var everyOther = false
    private var konLatch = 0
    private var koffLatch = 0
    private var pendingKon = 0

    private var echoOffset = 0
    private var echoLength = 0
    private val echoHistory = Array(2) { IntArray(8) }
    private var echoPos = 0

    fun reset() {
        regs.fill(0)
        regs[FLG] = 0xE0
        counter = 0
        noise = 0x4000
        everyOther = false
        konLatch = 0
        koffLatch = 0
        pendingKon = 0
        echoOffset = 0
        echoPos = 0

        for (v in 0 until VOICES) {
            envelope[v] = 0
            hiddenEnv[v] = 0
            envMode[v] = RELEASE
            konDelay[v] = 0
            output[v] = 0
            interpPos[v] = 0
            bufferPos[v] = 0
            buffer[v].fill(0)
        }

        for (side in 0 until 2) echoHistory[side].fill(0)
    }

    fun read(address: Int): Int = regs[address and 0x7F]

    fun write(address: Int, value: Int) {
        val index = address and 0x7F

        if (index == KON) {
            pendingKon = value
            return
        }

        if (index == ENDX) {
            regs[ENDX] = 0
            return
        }

        regs[index] = value
    }

    fun sample() {
        if (--counter < 0) counter = DspTables.COUNTER_RANGE - 1

        everyOther = !everyOther

        if (everyOther) {
            konLatch = pendingKon
            koffLatch = regs[KOFF]
            pendingKon = 0
        }

        val flags = regs[FLG]
        val reset = flags and 0x80 != 0
        val mute = flags and 0x40 != 0
        val echoDisabled = flags and 0x20 != 0

        if (!tick(flags and 0x1F)) {
            val feedback = (noise shl 13) xor (noise shl 14)
            noise = (feedback and 0x4000) or (noise ushr 1)
        }

        var mainLeft = 0
        var mainRight = 0
        var echoLeft = 0
        var echoRight = 0

        val pmon = regs[PMON]
        val non = regs[NON]
        val eon = regs[EON]

        var previous = 0

        for (v in 0 until VOICES) {
            val bit = 1 shl v
            val base = v shl 4

            if (everyOther) {
                if (konLatch and bit != 0) {
                    konDelay[v] = 5
                    envMode[v] = ATTACK
                }

                if (koffLatch and bit != 0) envMode[v] = RELEASE
            }

            if (reset) {
                envMode[v] = RELEASE
                envelope[v] = 0
            }

            var pitch = (regs[base + PITCH_LOW] or (regs[base + PITCH_HIGH] shl 8)) and 0x3FFF

            if (pmon and bit != 0 && v > 0) {
                pitch += ((previous shr 5) * pitch) shr 10
            }

            if (konDelay[v] != 0) {
                if (konDelay[v] == 5) {
                    brrAddress[v] = source(regs[base + SRCN], false)
                    brrOffset[v] = 1
                    bufferPos[v] = 0
                    brrHeader[v] = ram[brrAddress[v] and 0xFFFF].toInt() and 0xFF
                    regs[ENDX] = regs[ENDX] and bit.inv()
                }

                envelope[v] = 0
                hiddenEnv[v] = 0

                interpPos[v] = 0
                konDelay[v]--
                if (konDelay[v] and 3 != 0) interpPos[v] = 0x4000

                pitch = 0
            }

            if (interpPos[v] >= 0x4000) {
                interpPos[v] -= 0x4000
                decodeBrr(v)
            }

            val sample = if (non and bit != 0) {
                (noise shl 1).toShort().toInt()
            } else {
                interpolate(v)
            }

            runEnvelope(v)

            val level = envelope[v]
            val value = (sample * level) shr 11

            output[v] = value
            previous = value

            regs[base + ENVX] = (level shr 4) and 0xFF
            regs[base + OUTX] = (value shr 8) and 0xFF

            val left = (value * signed(regs[base + VOL_LEFT])) shr 7
            val right = (value * signed(regs[base + VOL_RIGHT])) shr 7

            mainLeft = clamp16(mainLeft + left)
            mainRight = clamp16(mainRight + right)

            if (eon and bit != 0) {
                echoLeft = clamp16(echoLeft + left)
                echoRight = clamp16(echoRight + right)
            }

            interpPos[v] += pitch
        }

        if (echoOffset == 0) echoLength = (regs[EDL] and 0x0F) * 2048

        val pointer = ((regs[ESA] shl 8) + echoOffset) and 0xFFFF

        val inLeft = readEcho(pointer)
        val inRight = readEcho((pointer + 2) and 0xFFFF)

        echoPos = (echoPos + 1) and 7
        echoHistory[0][echoPos] = inLeft shr 1
        echoHistory[1][echoPos] = inRight shr 1

        val firLeft = fir(0)
        val firRight = fir(1)

        val feedback = signed(regs[EFB])

        val writeLeft = clamp16(echoLeft + ((firLeft * feedback) shr 7))
        val writeRight = clamp16(echoRight + ((firRight * feedback) shr 7))

        if (!echoDisabled) {
            writeEcho(pointer, writeLeft and 0xFFFE)
            writeEcho((pointer + 2) and 0xFFFF, writeRight and 0xFFFE)
        }

        echoOffset += 4
        if (echoOffset >= echoLength) echoOffset = 0

        var left = ((mainLeft * signed(regs[MVOL_LEFT])) shr 7) + ((firLeft * signed(regs[EVOL_LEFT])) shr 7)
        var right = ((mainRight * signed(regs[MVOL_RIGHT])) shr 7) + ((firRight * signed(regs[EVOL_RIGHT])) shr 7)

        left = clamp16(left)
        right = clamp16(right)

        if (mute) {
            left = 0
            right = 0
        }

        outLeft = left
        outRight = right
    }

    private fun fir(side: Int): Int {
        val history = echoHistory[side]

        var sum = 0

        for (tap in 0 until 8) {
            val index = (echoPos + 1 + tap) and 7
            sum += (history[index] * signed(regs[(tap shl 4) + FIR])) shr 6
        }

        return clamp16(sum)
    }

    private fun readEcho(address: Int): Int {
        val low = ram[address].toInt() and 0xFF
        val high = ram[(address + 1) and 0xFFFF].toInt() and 0xFF
        return ((high shl 8) or low).toShort().toInt()
    }

    private fun writeEcho(address: Int, value: Int) {
        ram[address] = (value and 0xFF).toByte()
        ram[(address + 1) and 0xFFFF] = ((value shr 8) and 0xFF).toByte()
    }

    private fun source(index: Int, loop: Boolean): Int {
        val entry = ((regs[DIR] shl 8) + index * 4) and 0xFFFF
        val offset = if (loop) 2 else 0

        val low = ram[(entry + offset) and 0xFFFF].toInt() and 0xFF
        val high = ram[(entry + offset + 1) and 0xFFFF].toInt() and 0xFF

        return (high shl 8) or low
    }

    private fun decodeBrr(voice: Int) {
        val address = brrAddress[voice]
        val offset = brrOffset[voice]

        val header = brrHeader[voice]
        val shift = (header ushr 4) and 0x0F
        val filter = header and 0x0C

        val high = ram[(address + offset) and 0xFFFF].toInt() and 0xFF
        val low = ram[(address + offset + 1) and 0xFFFF].toInt() and 0xFF

        var nybbles = (high shl 8) or low

        val history = buffer[voice]
        var position = bufferPos[voice]

        bufferPos[voice] = (position + 4) % BUFFER

        for (i in 0 until 4) {
            var sample = nybbles.toShort().toInt() shr 12
            nybbles = (nybbles shl 4) and 0xFFFF

            sample = if (shift > 12) (sample shr 3) and 0x7FF.inv() else (sample shl shift) shr 1

            val p1 = history[(position + BUFFER - 1) % (BUFFER * 2)]
            val p2 = history[(position + BUFFER - 2) % (BUFFER * 2)] shr 1

            when {
                filter >= 8 -> {
                    sample += p1
                    sample -= p2

                    if (filter == 8) {
                        sample += p2 shr 4
                        sample += (p1 * -3) shr 6
                    } else {
                        sample += (p1 * -13) shr 7
                        sample += (p2 * 3) shr 4
                    }
                }

                filter != 0 -> {
                    sample += p1 shr 1
                    sample += (-p1) shr 5
                }
            }

            sample = clamp16(sample)
            sample = (sample * 2).toShort().toInt()

            history[position] = sample
            history[position + BUFFER] = sample

            position++
        }

        brrOffset[voice] = offset + 2

        if (brrOffset[voice] >= 9) {
            brrOffset[voice] = 1

            if (header and 1 != 0) {
                regs[ENDX] = regs[ENDX] or (1 shl voice)

                if (header and 2 != 0) {
                    brrAddress[voice] = source(regs[(voice shl 4) + SRCN], true)
                } else {
                    envMode[voice] = RELEASE
                    envelope[voice] = 0
                }
            } else {
                brrAddress[voice] = (brrAddress[voice] + 9) and 0xFFFF
            }

            brrHeader[voice] = ram[brrAddress[voice] and 0xFFFF].toInt() and 0xFF
        }
    }

    private fun interpolate(voice: Int): Int {
        val position = interpPos[voice]
        val offset = (position shr 4) and 0xFF
        val history = buffer[voice]
        val index = bufferPos[voice] + (position shr 12)

        val s0 = history[index]
        val s1 = history[index + 1]
        val s2 = history[index + 2]
        val s3 = history[index + 3]

        var value = (DspTables.GAUSS[255 - offset] * s0) shr 11
        value += (DspTables.GAUSS[511 - offset] * s1) shr 11
        value += (DspTables.GAUSS[256 + offset] * s2) shr 11
        value = value.toShort().toInt()
        value += (DspTables.GAUSS[offset] * s3) shr 11

        value = clamp16(value)

        return value and 1.inv()
    }

    private fun runEnvelope(voice: Int) {
        var env = envelope[voice]

        if (envMode[voice] == RELEASE) {
            env -= 8
            if (env < 0) env = 0
            envelope[voice] = env
            return
        }

        val base = voice shl 4
        val adsr0 = regs[base + ADSR0]
        var data = regs[base + ADSR1]
        val rate: Int

        if (adsr0 and 0x80 != 0) {
            if (envMode[voice] >= DECAY) {
                env--
                env -= env shr 8
                rate = if (envMode[voice] == DECAY) {
                    ((adsr0 shr 3) and 0x0E) + 0x10
                } else {
                    data and 0x1F
                }
            } else {
                rate = (adsr0 and 0x0F) * 2 + 1
                env += if (rate < 31) 0x20 else 0x400
            }
        } else {
            data = regs[base + GAIN]
            val mode = data shr 5

            if (mode < 4) {
                env = data * 0x10
                rate = 31
            } else {
                rate = data and 0x1F

                when {
                    mode == 4 -> env -= 0x20
                    mode < 6 -> {
                        env--
                        env -= env shr 8
                    }

                    else -> {
                        env += 0x20
                        if (mode > 6 && hiddenEnv[voice] >= 0x600) env += 8 - 0x20
                    }
                }
            }
        }

        if ((env shr 8) == (data shr 5) && envMode[voice] == DECAY) {
            envMode[voice] = SUSTAIN
        }

        hiddenEnv[voice] = env

        if (env < 0 || env > 0x7FF) {
            env = if (env < 0) 0 else 0x7FF
            if (envMode[voice] == ATTACK) envMode[voice] = DECAY
        }

        if (!tick(rate)) envelope[voice] = env
    }

    private fun tick(rate: Int): Boolean {
        if (rate == 0) return true
        return (counter + DspTables.COUNTER_OFFSETS[rate]) % DspTables.COUNTER_RATES[rate] != 0
    }

    private fun signed(value: Int): Int = value.toByte().toInt()

    private fun clamp16(value: Int): Int = when {
        value > 32767 -> 32767
        value < -32768 -> -32768
        else -> value
    }

    fun save(writer: StateWriter) {
        writer.ints(regs)
        writer.ints(envelope)
        writer.ints(hiddenEnv)
        writer.ints(envMode)
        writer.ints(brrAddress)
        writer.ints(brrOffset)
        writer.ints(brrHeader)
        writer.ints(interpPos)
        writer.ints(bufferPos)
        writer.ints(konDelay)
        writer.ints(output)

        for (v in 0 until VOICES) writer.ints(buffer[v])
        for (side in 0 until 2) writer.ints(echoHistory[side])

        writer.int(counter)
        writer.int(noise)
        writer.bool(everyOther)
        writer.int(konLatch)
        writer.int(koffLatch)
        writer.int(pendingKon)
        writer.int(echoOffset)
        writer.int(echoLength)
        writer.int(echoPos)
    }

    fun load(reader: StateReader) {
        reader.ints(regs)
        reader.ints(envelope)
        reader.ints(hiddenEnv)
        reader.ints(envMode)
        reader.ints(brrAddress)
        reader.ints(brrOffset)
        reader.ints(brrHeader)
        reader.ints(interpPos)
        reader.ints(bufferPos)
        reader.ints(konDelay)
        reader.ints(output)

        for (v in 0 until VOICES) reader.ints(buffer[v])
        for (side in 0 until 2) reader.ints(echoHistory[side])

        counter = reader.int()
        noise = reader.int()
        everyOther = reader.bool()
        konLatch = reader.int()
        koffLatch = reader.int()
        pendingKon = reader.int()
        echoOffset = reader.int()
        echoLength = reader.int()
        echoPos = reader.int()
    }

    companion object {
        const val VOICES = 8
        const val BUFFER = 12

        const val VOL_LEFT = 0x00
        const val VOL_RIGHT = 0x01
        const val PITCH_LOW = 0x02
        const val PITCH_HIGH = 0x03
        const val SRCN = 0x04
        const val ADSR0 = 0x05
        const val ADSR1 = 0x06
        const val GAIN = 0x07
        const val ENVX = 0x08
        const val OUTX = 0x09
        const val FIR = 0x0F

        const val MVOL_LEFT = 0x0C
        const val MVOL_RIGHT = 0x1C
        const val EVOL_LEFT = 0x2C
        const val EVOL_RIGHT = 0x3C
        const val KON = 0x4C
        const val KOFF = 0x5C
        const val FLG = 0x6C
        const val ENDX = 0x7C

        const val EFB = 0x0D
        const val PMON = 0x2D
        const val NON = 0x3D
        const val EON = 0x4D
        const val DIR = 0x5D
        const val ESA = 0x6D
        const val EDL = 0x7D

        const val RELEASE = 0
        const val ATTACK = 1
        const val DECAY = 2
        const val SUSTAIN = 3
    }
}
