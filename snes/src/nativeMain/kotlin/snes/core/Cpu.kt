package snes.core

import kapi.state.StateReader
import kapi.state.StateWriter

class Cpu(private val mem: Memory) {
    var a = 0
    var x = 0
    var y = 0
    var s = 0x01FF
    var d = 0
    var dbr = 0
    var pbr = 0
    var pc = 0
    var p = 0x34
    var emulation = true

    var waiting = false
    var stopped = false

    var nmiPending = false
    var irqLine = false

    private var cycles = 0

    private val flagC get() = p and 0x01 != 0
    private val flagD get() = p and 0x08 != 0
    private val flagX get() = p and 0x10 != 0
    private val flagM get() = p and 0x20 != 0

    fun reset() {
        emulation = true
        p = 0x34
        a = 0
        x = 0
        y = 0
        d = 0
        dbr = 0
        pbr = 0
        s = 0x01FF
        waiting = false
        stopped = false
        nmiPending = false
        irqLine = false
        cycles = 0
        pc = readVector(0xFFFC)
    }

    fun step(): Int {
        cycles = 0

        if (emulation) s = 0x0100 or (s and 0xFF)

        if (stopped) {
            idle()
            return cycles
        }

        if (waiting) {
            if (nmiPending || irqLine) {
                waiting = false
                idle()
            } else {
                idle()
                return cycles
            }
        }

        if (nmiPending) {
            nmiPending = false
            idle()
            idle()
            interrupt(if (emulation) 0xFFFA else 0xFFEA, false)
            return cycles
        }

        if (irqLine && p and 0x04 == 0) {
            idle()
            idle()
            interrupt(if (emulation) 0xFFFE else 0xFFEE, false)
            return cycles
        }

        execute(fetch8())

        if (emulation) s = 0x0100 or (s and 0xFF)

        return cycles
    }

    private fun execute(opcode: Int) {
        when (opcode) {
            0x00 -> {
                fetch8()
                interrupt(if (emulation) 0xFFFE else 0xFFE6, true)
            }

            0x02 -> {
                fetch8()
                interrupt(if (emulation) 0xFFF4 else 0xFFE4, true)
            }

            0x01 -> ora(dpXInd(), WRAP_LONG)
            0x03 -> ora(sr(), WRAP_BANK)
            0x05 -> ora(dp(), WRAP_DP)
            0x07 -> ora(dpIndLong(), WRAP_LONG)
            0x09 -> ora(imm(!flagM), WRAP_BANK)
            0x0D -> ora(abs(), WRAP_LONG)
            0x0F -> ora(absLong(), WRAP_LONG)
            0x11 -> ora(dpIndY(false), WRAP_LONG)
            0x12 -> ora(dpInd(), WRAP_LONG)
            0x13 -> ora(srIndY(), WRAP_LONG)
            0x15 -> ora(dpX(), WRAP_DP)
            0x17 -> ora(dpIndLongY(), WRAP_LONG)
            0x19 -> ora(absY(false), WRAP_LONG)
            0x1D -> ora(absX(false), WRAP_LONG)
            0x1F -> ora(absLongX(), WRAP_LONG)

            0x21 -> and(dpXInd(), WRAP_LONG)
            0x23 -> and(sr(), WRAP_BANK)
            0x25 -> and(dp(), WRAP_DP)
            0x27 -> and(dpIndLong(), WRAP_LONG)
            0x29 -> and(imm(!flagM), WRAP_BANK)
            0x2D -> and(abs(), WRAP_LONG)
            0x2F -> and(absLong(), WRAP_LONG)
            0x31 -> and(dpIndY(false), WRAP_LONG)
            0x32 -> and(dpInd(), WRAP_LONG)
            0x33 -> and(srIndY(), WRAP_LONG)
            0x35 -> and(dpX(), WRAP_DP)
            0x37 -> and(dpIndLongY(), WRAP_LONG)
            0x39 -> and(absY(false), WRAP_LONG)
            0x3D -> and(absX(false), WRAP_LONG)
            0x3F -> and(absLongX(), WRAP_LONG)

            0x41 -> eor(dpXInd(), WRAP_LONG)
            0x43 -> eor(sr(), WRAP_BANK)
            0x45 -> eor(dp(), WRAP_DP)
            0x47 -> eor(dpIndLong(), WRAP_LONG)
            0x49 -> eor(imm(!flagM), WRAP_BANK)
            0x4D -> eor(abs(), WRAP_LONG)
            0x4F -> eor(absLong(), WRAP_LONG)
            0x51 -> eor(dpIndY(false), WRAP_LONG)
            0x52 -> eor(dpInd(), WRAP_LONG)
            0x53 -> eor(srIndY(), WRAP_LONG)
            0x55 -> eor(dpX(), WRAP_DP)
            0x57 -> eor(dpIndLongY(), WRAP_LONG)
            0x59 -> eor(absY(false), WRAP_LONG)
            0x5D -> eor(absX(false), WRAP_LONG)
            0x5F -> eor(absLongX(), WRAP_LONG)

            0x61 -> adc(dpXInd(), WRAP_LONG)
            0x63 -> adc(sr(), WRAP_BANK)
            0x65 -> adc(dp(), WRAP_DP)
            0x67 -> adc(dpIndLong(), WRAP_LONG)
            0x69 -> adc(imm(!flagM), WRAP_BANK)
            0x6D -> adc(abs(), WRAP_LONG)
            0x6F -> adc(absLong(), WRAP_LONG)
            0x71 -> adc(dpIndY(false), WRAP_LONG)
            0x72 -> adc(dpInd(), WRAP_LONG)
            0x73 -> adc(srIndY(), WRAP_LONG)
            0x75 -> adc(dpX(), WRAP_DP)
            0x77 -> adc(dpIndLongY(), WRAP_LONG)
            0x79 -> adc(absY(false), WRAP_LONG)
            0x7D -> adc(absX(false), WRAP_LONG)
            0x7F -> adc(absLongX(), WRAP_LONG)

            0xE1 -> sbc(dpXInd(), WRAP_LONG)
            0xE3 -> sbc(sr(), WRAP_BANK)
            0xE5 -> sbc(dp(), WRAP_DP)
            0xE7 -> sbc(dpIndLong(), WRAP_LONG)
            0xE9 -> sbc(imm(!flagM), WRAP_BANK)
            0xED -> sbc(abs(), WRAP_LONG)
            0xEF -> sbc(absLong(), WRAP_LONG)
            0xF1 -> sbc(dpIndY(false), WRAP_LONG)
            0xF2 -> sbc(dpInd(), WRAP_LONG)
            0xF3 -> sbc(srIndY(), WRAP_LONG)
            0xF5 -> sbc(dpX(), WRAP_DP)
            0xF7 -> sbc(dpIndLongY(), WRAP_LONG)
            0xF9 -> sbc(absY(false), WRAP_LONG)
            0xFD -> sbc(absX(false), WRAP_LONG)
            0xFF -> sbc(absLongX(), WRAP_LONG)

            0xC1 -> cmp(dpXInd(), WRAP_LONG)
            0xC3 -> cmp(sr(), WRAP_BANK)
            0xC5 -> cmp(dp(), WRAP_DP)
            0xC7 -> cmp(dpIndLong(), WRAP_LONG)
            0xC9 -> cmp(imm(!flagM), WRAP_BANK)
            0xCD -> cmp(abs(), WRAP_LONG)
            0xCF -> cmp(absLong(), WRAP_LONG)
            0xD1 -> cmp(dpIndY(false), WRAP_LONG)
            0xD2 -> cmp(dpInd(), WRAP_LONG)
            0xD3 -> cmp(srIndY(), WRAP_LONG)
            0xD5 -> cmp(dpX(), WRAP_DP)
            0xD7 -> cmp(dpIndLongY(), WRAP_LONG)
            0xD9 -> cmp(absY(false), WRAP_LONG)
            0xDD -> cmp(absX(false), WRAP_LONG)
            0xDF -> cmp(absLongX(), WRAP_LONG)

            0xE0 -> cpx(imm(!flagX), WRAP_BANK)
            0xE4 -> cpx(dp(), WRAP_DP)
            0xEC -> cpx(abs(), WRAP_LONG)

            0xC0 -> cpy(imm(!flagX), WRAP_BANK)
            0xC4 -> cpy(dp(), WRAP_DP)
            0xCC -> cpy(abs(), WRAP_LONG)

            0xA1 -> lda(dpXInd(), WRAP_LONG)
            0xA3 -> lda(sr(), WRAP_BANK)
            0xA5 -> lda(dp(), WRAP_DP)
            0xA7 -> lda(dpIndLong(), WRAP_LONG)
            0xA9 -> lda(imm(!flagM), WRAP_BANK)
            0xAD -> lda(abs(), WRAP_LONG)
            0xAF -> lda(absLong(), WRAP_LONG)
            0xB1 -> lda(dpIndY(false), WRAP_LONG)
            0xB2 -> lda(dpInd(), WRAP_LONG)
            0xB3 -> lda(srIndY(), WRAP_LONG)
            0xB5 -> lda(dpX(), WRAP_DP)
            0xB7 -> lda(dpIndLongY(), WRAP_LONG)
            0xB9 -> lda(absY(false), WRAP_LONG)
            0xBD -> lda(absX(false), WRAP_LONG)
            0xBF -> lda(absLongX(), WRAP_LONG)

            0xA2 -> ldx(imm(!flagX), WRAP_BANK)
            0xA6 -> ldx(dp(), WRAP_DP)
            0xAE -> ldx(abs(), WRAP_LONG)
            0xB6 -> ldx(dpY(), WRAP_DP)
            0xBE -> ldx(absY(false), WRAP_LONG)

            0xA0 -> ldy(imm(!flagX), WRAP_BANK)
            0xA4 -> ldy(dp(), WRAP_DP)
            0xAC -> ldy(abs(), WRAP_LONG)
            0xB4 -> ldy(dpX(), WRAP_DP)
            0xBC -> ldy(absX(false), WRAP_LONG)

            0x81 -> sta(dpXInd(), WRAP_LONG)
            0x83 -> sta(sr(), WRAP_BANK)
            0x85 -> sta(dp(), WRAP_DP)
            0x87 -> sta(dpIndLong(), WRAP_LONG)
            0x8D -> sta(abs(), WRAP_LONG)
            0x8F -> sta(absLong(), WRAP_LONG)
            0x91 -> sta(dpIndY(true), WRAP_LONG)
            0x92 -> sta(dpInd(), WRAP_LONG)
            0x93 -> sta(srIndY(), WRAP_LONG)
            0x95 -> sta(dpX(), WRAP_DP)
            0x97 -> sta(dpIndLongY(), WRAP_LONG)
            0x99 -> sta(absY(true), WRAP_LONG)
            0x9D -> sta(absX(true), WRAP_LONG)
            0x9F -> sta(absLongX(), WRAP_LONG)

            0x86 -> stx(dp(), WRAP_DP)
            0x8E -> stx(abs(), WRAP_LONG)
            0x96 -> stx(dpY(), WRAP_DP)

            0x84 -> sty(dp(), WRAP_DP)
            0x8C -> sty(abs(), WRAP_LONG)
            0x94 -> sty(dpX(), WRAP_DP)

            0x64 -> stz(dp(), WRAP_DP)
            0x74 -> stz(dpX(), WRAP_DP)
            0x9C -> stz(abs(), WRAP_LONG)
            0x9E -> stz(absX(true), WRAP_LONG)

            0x06 -> rmw(dp(), WRAP_DP, ASL)
            0x0A -> aslA()
            0x0E -> rmw(abs(), WRAP_LONG, ASL)
            0x16 -> rmw(dpX(), WRAP_DP, ASL)
            0x1E -> rmw(absX(true), WRAP_LONG, ASL)

            0x46 -> rmw(dp(), WRAP_DP, LSR)
            0x4A -> lsrA()
            0x4E -> rmw(abs(), WRAP_LONG, LSR)
            0x56 -> rmw(dpX(), WRAP_DP, LSR)
            0x5E -> rmw(absX(true), WRAP_LONG, LSR)

            0x26 -> rmw(dp(), WRAP_DP, ROL)
            0x2A -> rolA()
            0x2E -> rmw(abs(), WRAP_LONG, ROL)
            0x36 -> rmw(dpX(), WRAP_DP, ROL)
            0x3E -> rmw(absX(true), WRAP_LONG, ROL)

            0x66 -> rmw(dp(), WRAP_DP, ROR)
            0x6A -> rorA()
            0x6E -> rmw(abs(), WRAP_LONG, ROR)
            0x76 -> rmw(dpX(), WRAP_DP, ROR)
            0x7E -> rmw(absX(true), WRAP_LONG, ROR)

            0xE6 -> rmw(dp(), WRAP_DP, INC)
            0xEE -> rmw(abs(), WRAP_LONG, INC)
            0xF6 -> rmw(dpX(), WRAP_DP, INC)
            0xFE -> rmw(absX(true), WRAP_LONG, INC)
            0x1A -> incA()

            0xC6 -> rmw(dp(), WRAP_DP, DEC)
            0xCE -> rmw(abs(), WRAP_LONG, DEC)
            0xD6 -> rmw(dpX(), WRAP_DP, DEC)
            0xDE -> rmw(absX(true), WRAP_LONG, DEC)
            0x3A -> decA()

            0x04 -> rmw(dp(), WRAP_DP, TSB)
            0x0C -> rmw(abs(), WRAP_LONG, TSB)
            0x14 -> rmw(dp(), WRAP_DP, TRB)
            0x1C -> rmw(abs(), WRAP_LONG, TRB)

            0x24 -> bit(dp(), WRAP_DP)
            0x2C -> bit(abs(), WRAP_LONG)
            0x34 -> bit(dpX(), WRAP_DP)
            0x3C -> bit(absX(false), WRAP_LONG)
            0x89 -> bitImmediate()

            0x10 -> branch(p and 0x80 == 0)
            0x30 -> branch(p and 0x80 != 0)
            0x50 -> branch(p and 0x40 == 0)
            0x70 -> branch(p and 0x40 != 0)
            0x90 -> branch(p and 0x01 == 0)
            0xB0 -> branch(p and 0x01 != 0)
            0xD0 -> branch(p and 0x02 == 0)
            0xF0 -> branch(p and 0x02 != 0)
            0x80 -> branch(true)

            0x82 -> {
                val offset = fetch16()
                idle()
                pc = (pc + signed16(offset)) and 0xFFFF
            }

            0x4C -> pc = fetch16()

            0x5C -> {
                val target = fetch16()
                pbr = fetch8()
                pc = target
            }

            0x6C -> {
                val pointer = fetch16()
                pc = readWord(pointer, WRAP_BANK)
            }

            0x7C -> {
                val pointer = fetch16()
                idle()
                pc = readWord((pbr shl 16) or ((pointer + x) and 0xFFFF), WRAP_DP_NONE)
            }

            0xDC -> {
                val pointer = fetch16()
                val target = readWord(pointer, WRAP_BANK)
                pbr = read((pointer + 2) and 0xFFFF)
                pc = target
            }

            0x20 -> {
                val target = fetch16()
                idle()
                push16((pc - 1) and 0xFFFF)
                pc = target
            }

            0x22 -> {
                val low = fetch16()
                pushWide8(pbr)
                idle()
                val bank = fetch8()
                pushWide16((pc - 1) and 0xFFFF)
                pbr = bank
                pc = low
            }

            0xFC -> {
                val pointer = fetch16()
                push16((pc - 1) and 0xFFFF)
                idle()
                pc = readWord((pbr shl 16) or ((pointer + x) and 0xFFFF), WRAP_DP_NONE)
            }

            0x60 -> {
                idle()
                idle()
                pc = (pull16() + 1) and 0xFFFF
                idle()
            }

            0x6B -> {
                idle()
                idle()
                val target = pullWide16()
                pbr = pullWide8()
                pc = (target + 1) and 0xFFFF
            }

            0x40 -> {
                idle()
                idle()
                setStatus(pull8())
                pc = pull16()
                if (!emulation) pbr = pull8()
            }

            0x08 -> {
                idle()
                push8(p)
            }

            0x28 -> {
                idle()
                idle()
                setStatus(pull8())
            }

            0x48 -> {
                idle()
                if (flagM) push8(a and 0xFF) else push16(a and 0xFFFF)
            }

            0x68 -> {
                idle()
                idle()
                if (flagM) {
                    val value = pull8()
                    a = (a and 0xFF00) or value
                    setNZ8(value)
                } else {
                    val value = pull16()
                    a = value
                    setNZ16(value)
                }
            }

            0xDA -> {
                idle()
                if (flagX) push8(x and 0xFF) else push16(x and 0xFFFF)
            }

            0xFA -> {
                idle()
                idle()
                if (flagX) {
                    val value = pull8()
                    x = value
                    setNZ8(value)
                } else {
                    val value = pull16()
                    x = value
                    setNZ16(value)
                }
            }

            0x5A -> {
                idle()
                if (flagX) push8(y and 0xFF) else push16(y and 0xFFFF)
            }

            0x7A -> {
                idle()
                idle()
                if (flagX) {
                    val value = pull8()
                    y = value
                    setNZ8(value)
                } else {
                    val value = pull16()
                    y = value
                    setNZ16(value)
                }
            }

            0x8B -> {
                idle()
                pushWide8(dbr)
            }

            0xAB -> {
                idle()
                idle()
                dbr = pullWide8()
                setNZ8(dbr)
            }

            0x4B -> {
                idle()
                pushWide8(pbr)
            }

            0x0B -> {
                idle()
                pushWide16(d)
            }

            0x2B -> {
                idle()
                idle()
                d = pullWide16()
                setNZ16(d)
            }

            0xF4 -> pushWide16(fetch16())

            0xD4 -> {
                val address = dp()
                pushWide16(readWord(address, WRAP_DP))
            }

            0x62 -> {
                val offset = fetch16()
                idle()
                pushWide16((pc + signed16(offset)) and 0xFFFF)
            }

            0x54 -> move(1)
            0x44 -> move(-1)

            0x18 -> {
                idle()
                p = p and 0x01.inv()
            }

            0x38 -> {
                idle()
                p = p or 0x01
            }

            0x58 -> {
                idle()
                p = p and 0x04.inv()
            }

            0x78 -> {
                idle()
                p = p or 0x04
            }

            0xB8 -> {
                idle()
                p = p and 0x40.inv()
            }

            0xD8 -> {
                idle()
                p = p and 0x08.inv()
            }

            0xF8 -> {
                idle()
                p = p or 0x08
            }

            0xC2 -> {
                val value = fetch8()
                idle()
                setStatus(p and value.inv())
            }

            0xE2 -> {
                val value = fetch8()
                idle()
                setStatus(p or value)
            }

            0xFB -> {
                idle()
                val carry = flagC
                if (emulation) p = p or 0x01 else p = p and 0x01.inv()
                emulation = carry
                if (emulation) {
                    p = p or 0x30
                    x = x and 0xFF
                    y = y and 0xFF
                    s = 0x0100 or (s and 0xFF)
                }
            }

            0xEB -> {
                idle()
                idle()
                a = ((a shl 8) or (a ushr 8)) and 0xFFFF
                setNZ8(a and 0xFF)
            }

            0xAA -> {
                idle()
                if (flagX) {
                    x = a and 0xFF
                    setNZ8(x)
                } else {
                    x = a and 0xFFFF
                    setNZ16(x)
                }
            }

            0xA8 -> {
                idle()
                if (flagX) {
                    y = a and 0xFF
                    setNZ8(y)
                } else {
                    y = a and 0xFFFF
                    setNZ16(y)
                }
            }

            0x8A -> {
                idle()
                if (flagM) {
                    a = (a and 0xFF00) or (x and 0xFF)
                    setNZ8(a and 0xFF)
                } else {
                    a = x and 0xFFFF
                    setNZ16(a)
                }
            }

            0x98 -> {
                idle()
                if (flagM) {
                    a = (a and 0xFF00) or (y and 0xFF)
                    setNZ8(a and 0xFF)
                } else {
                    a = y and 0xFFFF
                    setNZ16(a)
                }
            }

            0x9B -> {
                idle()
                if (flagX) {
                    y = x and 0xFF
                    setNZ8(y)
                } else {
                    y = x and 0xFFFF
                    setNZ16(y)
                }
            }

            0xBB -> {
                idle()
                if (flagX) {
                    x = y and 0xFF
                    setNZ8(x)
                } else {
                    x = y and 0xFFFF
                    setNZ16(x)
                }
            }

            0xBA -> {
                idle()
                if (flagX) {
                    x = s and 0xFF
                    setNZ8(x)
                } else {
                    x = s and 0xFFFF
                    setNZ16(x)
                }
            }

            0x9A -> {
                idle()
                s = if (emulation) 0x0100 or (x and 0xFF) else x and 0xFFFF
            }

            0x1B -> {
                idle()
                s = if (emulation) 0x0100 or (a and 0xFF) else a and 0xFFFF
            }

            0x3B -> {
                idle()
                a = s and 0xFFFF
                setNZ16(a)
            }

            0x5B -> {
                idle()
                d = a and 0xFFFF
                setNZ16(d)
            }

            0x7B -> {
                idle()
                a = d and 0xFFFF
                setNZ16(a)
            }

            0xE8 -> {
                idle()
                if (flagX) {
                    x = (x + 1) and 0xFF
                    setNZ8(x)
                } else {
                    x = (x + 1) and 0xFFFF
                    setNZ16(x)
                }
            }

            0xC8 -> {
                idle()
                if (flagX) {
                    y = (y + 1) and 0xFF
                    setNZ8(y)
                } else {
                    y = (y + 1) and 0xFFFF
                    setNZ16(y)
                }
            }

            0xCA -> {
                idle()
                if (flagX) {
                    x = (x - 1) and 0xFF
                    setNZ8(x)
                } else {
                    x = (x - 1) and 0xFFFF
                    setNZ16(x)
                }
            }

            0x88 -> {
                idle()
                if (flagX) {
                    y = (y - 1) and 0xFF
                    setNZ8(y)
                } else {
                    y = (y - 1) and 0xFFFF
                    setNZ16(y)
                }
            }

            0xCB -> {
                idle()
                idle()
                waiting = true
            }

            0xDB -> {
                idle()
                idle()
                stopped = true
            }

            0xEA -> idle()

            0x42 -> {
                fetch8()
            }

            else -> idle()
        }
    }

    private fun move(direction: Int) {
        val destination = fetch8()
        val source = fetch8()

        dbr = destination

        val value = read((source shl 16) or (x and 0xFFFF))
        write((destination shl 16) or (y and 0xFFFF), value)

        idle()
        idle()

        if (flagX) {
            x = (x + direction) and 0xFF
            y = (y + direction) and 0xFF
        } else {
            x = (x + direction) and 0xFFFF
            y = (y + direction) and 0xFFFF
        }

        a = (a - 1) and 0xFFFF

        if (a != 0xFFFF) pc = (pc - 3) and 0xFFFF
    }

    private fun setStatus(value: Int) {
        p = value and 0xFF

        if (emulation) {
            p = p or 0x30
        }

        if (flagX) {
            x = x and 0xFF
            y = y and 0xFF
        }
    }

    private fun interrupt(target: Int, software: Boolean) {
        if (!emulation) push8(pbr)
        push16(pc)
        push8(if (emulation && !software) p and 0xEF else p)

        p = p or 0x04
        p = p and 0x08.inv()

        pbr = 0
        pc = readVector(target)
    }

    private fun readVector(address: Int): Int {
        val low = read(address)
        val high = read(address + 1)
        return low or (high shl 8)
    }

    private fun branch(taken: Boolean) {
        val offset = fetch8()
        if (!taken) return

        idle()

        val target = (pc + signed8(offset)) and 0xFFFF

        if (emulation && (target and 0xFF00) != (pc and 0xFF00)) idle()

        pc = target
    }

    private fun ora(address: Int, wrap: Int) {
        if (flagM) {
            val value = (a or read(address)) and 0xFF
            a = (a and 0xFF00) or value
            setNZ8(value)
        } else {
            val value = (a or readWord(address, wrap)) and 0xFFFF
            a = value
            setNZ16(value)
        }
    }

    private fun and(address: Int, wrap: Int) {
        if (flagM) {
            val value = (a and read(address)) and 0xFF
            a = (a and 0xFF00) or value
            setNZ8(value)
        } else {
            val value = (a and readWord(address, wrap)) and 0xFFFF
            a = value
            setNZ16(value)
        }
    }

    private fun eor(address: Int, wrap: Int) {
        if (flagM) {
            val value = (a xor read(address)) and 0xFF
            a = (a and 0xFF00) or value
            setNZ8(value)
        } else {
            val value = (a xor readWord(address, wrap)) and 0xFFFF
            a = value
            setNZ16(value)
        }
    }

    private fun lda(address: Int, wrap: Int) {
        if (flagM) {
            val value = read(address)
            a = (a and 0xFF00) or value
            setNZ8(value)
        } else {
            val value = readWord(address, wrap)
            a = value
            setNZ16(value)
        }
    }

    private fun ldx(address: Int, wrap: Int) {
        if (flagX) {
            val value = read(address)
            x = value
            setNZ8(value)
        } else {
            val value = readWord(address, wrap)
            x = value
            setNZ16(value)
        }
    }

    private fun ldy(address: Int, wrap: Int) {
        if (flagX) {
            val value = read(address)
            y = value
            setNZ8(value)
        } else {
            val value = readWord(address, wrap)
            y = value
            setNZ16(value)
        }
    }

    private fun sta(address: Int, wrap: Int) {
        if (flagM) write(address, a and 0xFF) else writeWord(address, a and 0xFFFF, wrap)
    }

    private fun stx(address: Int, wrap: Int) {
        if (flagX) write(address, x and 0xFF) else writeWord(address, x and 0xFFFF, wrap)
    }

    private fun sty(address: Int, wrap: Int) {
        if (flagX) write(address, y and 0xFF) else writeWord(address, y and 0xFFFF, wrap)
    }

    private fun stz(address: Int, wrap: Int) {
        if (flagM) write(address, 0) else writeWord(address, 0, wrap)
    }

    private fun cmp(address: Int, wrap: Int) {
        if (flagM) {
            val result = (a and 0xFF) - read(address)
            setCarry(result >= 0)
            setNZ8(result and 0xFF)
        } else {
            val result = (a and 0xFFFF) - readWord(address, wrap)
            setCarry(result >= 0)
            setNZ16(result and 0xFFFF)
        }
    }

    private fun cpx(address: Int, wrap: Int) {
        if (flagX) {
            val result = (x and 0xFF) - read(address)
            setCarry(result >= 0)
            setNZ8(result and 0xFF)
        } else {
            val result = (x and 0xFFFF) - readWord(address, wrap)
            setCarry(result >= 0)
            setNZ16(result and 0xFFFF)
        }
    }

    private fun cpy(address: Int, wrap: Int) {
        if (flagX) {
            val result = (y and 0xFF) - read(address)
            setCarry(result >= 0)
            setNZ8(result and 0xFF)
        } else {
            val result = (y and 0xFFFF) - readWord(address, wrap)
            setCarry(result >= 0)
            setNZ16(result and 0xFFFF)
        }
    }

    private fun bit(address: Int, wrap: Int) {
        if (flagM) {
            val value = read(address)
            p = p and 0xC2.inv()
            if (value and 0x80 != 0) p = p or 0x80
            if (value and 0x40 != 0) p = p or 0x40
            if (value and a and 0xFF == 0) p = p or 0x02
        } else {
            val value = readWord(address, wrap)
            p = p and 0xC2.inv()
            if (value and 0x8000 != 0) p = p or 0x80
            if (value and 0x4000 != 0) p = p or 0x40
            if (value and a and 0xFFFF == 0) p = p or 0x02
        }
    }

    private fun bitImmediate() {
        val address = imm(!flagM)

        val zero = if (flagM) {
            (read(address) and a and 0xFF) == 0
        } else {
            (readWord(address, WRAP_BANK) and a and 0xFFFF) == 0
        }

        p = p and 0x02.inv()
        if (zero) p = p or 0x02
    }

    private fun adc(address: Int, wrap: Int) {
        if (flagM) adc8(read(address)) else adc16(readWord(address, wrap))
    }

    private fun sbc(address: Int, wrap: Int) {
        if (flagM) sbc8(read(address)) else sbc16(readWord(address, wrap))
    }

    private fun adc8(data: Int) {
        val value = a and 0xFF
        val carry = p and 1
        var result: Int

        if (flagD) {
            result = (value and 0x0F) + (data and 0x0F) + carry
            if (result > 0x09) result += 0x06
            val half = if (result > 0x0F) 1 else 0
            result = (value and 0xF0) + (data and 0xF0) + (half shl 4) + (result and 0x0F)
        } else {
            result = value + data + carry
        }

        setOverflow((value xor data).inv() and (value xor result) and 0x80 != 0)

        if (flagD && result > 0x9F) result += 0x60

        setCarry(result > 0xFF)
        a = (a and 0xFF00) or (result and 0xFF)
        setNZ8(result and 0xFF)
    }

    private fun adc16(data: Int) {
        val value = a and 0xFFFF
        val carry = p and 1
        var result: Int

        if (flagD) {
            result = (value and 0x000F) + (data and 0x000F) + carry
            if (result > 0x0009) result += 0x0006
            var half = if (result > 0x000F) 1 else 0
            result = (value and 0x00F0) + (data and 0x00F0) + (half shl 4) + (result and 0x000F)
            if (result > 0x009F) result += 0x0060
            half = if (result > 0x00FF) 1 else 0
            result = (value and 0x0F00) + (data and 0x0F00) + (half shl 8) + (result and 0x00FF)
            if (result > 0x09FF) result += 0x0600
            half = if (result > 0x0FFF) 1 else 0
            result = (value and 0xF000) + (data and 0xF000) + (half shl 12) + (result and 0x0FFF)
        } else {
            result = value + data + carry
        }

        setOverflow((value xor data).inv() and (value xor result) and 0x8000 != 0)

        if (flagD && result > 0x9FFF) result += 0x6000

        setCarry(result > 0xFFFF)
        a = result and 0xFFFF
        setNZ16(a)
    }

    private fun sbc8(operand: Int) {
        val data = operand.inv() and 0xFF
        val value = a and 0xFF
        val carry = p and 1
        var result: Int

        if (flagD) {
            result = (value and 0x0F) + (data and 0x0F) + carry
            if (result <= 0x0F) result -= 0x06
            val half = if (result > 0x0F) 1 else 0
            result = (value and 0xF0) + (data and 0xF0) + (half shl 4) + (result and 0x0F)
        } else {
            result = value + data + carry
        }

        setOverflow((value xor data).inv() and (value xor result) and 0x80 != 0)

        if (flagD && result <= 0xFF) result -= 0x60

        setCarry(result > 0xFF)
        a = (a and 0xFF00) or (result and 0xFF)
        setNZ8(result and 0xFF)
    }

    private fun sbc16(operand: Int) {
        val data = operand.inv() and 0xFFFF
        val value = a and 0xFFFF
        val carry = p and 1
        var result: Int

        if (flagD) {
            result = (value and 0x000F) + (data and 0x000F) + carry
            if (result <= 0x000F) result -= 0x0006
            var half = if (result > 0x000F) 1 else 0
            result = (value and 0x00F0) + (data and 0x00F0) + (half shl 4) + (result and 0x000F)
            if (result <= 0x00FF) result -= 0x0060
            half = if (result > 0x00FF) 1 else 0
            result = (value and 0x0F00) + (data and 0x0F00) + (half shl 8) + (result and 0x00FF)
            if (result <= 0x0FFF) result -= 0x0600
            half = if (result > 0x0FFF) 1 else 0
            result = (value and 0xF000) + (data and 0xF000) + (half shl 12) + (result and 0x0FFF)
        } else {
            result = value + data + carry
        }

        setOverflow((value xor data).inv() and (value xor result) and 0x8000 != 0)

        if (flagD && result <= 0xFFFF) result -= 0x6000

        setCarry(result > 0xFFFF)
        a = result and 0xFFFF
        setNZ16(a)
    }

    private fun rmw(address: Int, wrap: Int, op: Int) {
        if (flagM) {
            val value = read(address)
            idle()
            write(address, alu(value, 0xFF, op))
        } else {
            val value = readWord(address, wrap)
            idle()
            val result = alu(value, 0xFFFF, op)
            writeWord(address, result, wrap)
        }
    }

    private fun alu(value: Int, mask: Int, op: Int): Int {
        val high = if (mask == 0xFF) 0x80 else 0x8000

        val result = when (op) {
            ASL -> {
                setCarry(value and high != 0)
                (value shl 1) and mask
            }

            LSR -> {
                setCarry(value and 1 != 0)
                (value ushr 1) and mask
            }

            ROL -> {
                val carry = p and 1
                setCarry(value and high != 0)
                ((value shl 1) or carry) and mask
            }

            ROR -> {
                val carry = if (flagC) high else 0
                setCarry(value and 1 != 0)
                ((value ushr 1) or carry) and mask
            }

            INC -> (value + 1) and mask
            DEC -> (value - 1) and mask

            TSB -> {
                setZero((value and (a and mask)) == 0)
                (value or (a and mask)) and mask
            }

            else -> {
                setZero((value and (a and mask)) == 0)
                value and (a and mask).inv() and mask
            }
        }

        if (op != TSB && op != TRB) {
            if (mask == 0xFF) setNZ8(result) else setNZ16(result)
        }

        return result
    }

    private fun aslA() {
        idle()
        a = if (flagM) (a and 0xFF00) or alu(a and 0xFF, 0xFF, ASL) else alu(a and 0xFFFF, 0xFFFF, ASL)
    }

    private fun lsrA() {
        idle()
        a = if (flagM) (a and 0xFF00) or alu(a and 0xFF, 0xFF, LSR) else alu(a and 0xFFFF, 0xFFFF, LSR)
    }

    private fun rolA() {
        idle()
        a = if (flagM) (a and 0xFF00) or alu(a and 0xFF, 0xFF, ROL) else alu(a and 0xFFFF, 0xFFFF, ROL)
    }

    private fun rorA() {
        idle()
        a = if (flagM) (a and 0xFF00) or alu(a and 0xFF, 0xFF, ROR) else alu(a and 0xFFFF, 0xFFFF, ROR)
    }

    private fun incA() {
        idle()
        a = if (flagM) (a and 0xFF00) or alu(a and 0xFF, 0xFF, INC) else alu(a and 0xFFFF, 0xFFFF, INC)
    }

    private fun decA() {
        idle()
        a = if (flagM) (a and 0xFF00) or alu(a and 0xFF, 0xFF, DEC) else alu(a and 0xFFFF, 0xFFFF, DEC)
    }

    private fun imm(wide: Boolean): Int {
        val address = (pbr shl 16) or pc
        pc = (pc + if (wide) 2 else 1) and 0xFFFF
        return address
    }

    private fun abs(): Int = (dbr shl 16) or fetch16()

    private fun absLong(): Int {
        val low = fetch16()
        val bank = fetch8()
        return (bank shl 16) or low
    }

    private fun absLongX(): Int {
        val low = fetch16()
        val bank = fetch8()
        return ((bank shl 16) + low + x) and 0xFFFFFF
    }

    private fun absX(always: Boolean): Int {
        val base = fetch16()
        val indexed = base + x

        if (always || !flagX || (indexed and 0xFF00) != (base and 0xFF00)) idle()

        return ((dbr shl 16) + indexed) and 0xFFFFFF
    }

    private fun absY(always: Boolean): Int {
        val base = fetch16()
        val indexed = base + y

        if (always || !flagX || (indexed and 0xFF00) != (base and 0xFF00)) idle()

        return ((dbr shl 16) + indexed) and 0xFFFFFF
    }

    private fun dp(): Int {
        val offset = fetch8()
        if (d and 0xFF != 0) idle()
        return (d + offset) and 0xFFFF
    }

    private fun dpX(): Int {
        val offset = fetch8()
        if (d and 0xFF != 0) idle()
        idle()

        if (emulation && d and 0xFF == 0) return (d and 0xFF00) or ((offset + x) and 0xFF)

        return (d + offset + x) and 0xFFFF
    }

    private fun dpY(): Int {
        val offset = fetch8()
        if (d and 0xFF != 0) idle()
        idle()

        if (emulation && d and 0xFF == 0) return (d and 0xFF00) or ((offset + y) and 0xFF)

        return (d + offset + y) and 0xFFFF
    }

    private fun dpInd(): Int = (dbr shl 16) or readWord(dp(), WRAP_DP)

    private fun dpXInd(): Int = (dbr shl 16) or readWord(dpX(), WRAP_DP)

    private fun dpIndY(always: Boolean): Int {
        val base = readWord(dp(), WRAP_DP)
        val indexed = base + y

        if (always || !flagX || (indexed and 0xFF00) != (base and 0xFF00)) idle()

        return ((dbr shl 16) + indexed) and 0xFFFFFF
    }

    private fun dpIndLong(): Int {
        val pointer = dp()
        val low = readWord(pointer, WRAP_BANK)
        val bank = read((pointer + 2) and 0xFFFF)
        return (bank shl 16) or low
    }

    private fun dpIndLongY(): Int {
        val pointer = dp()
        val low = readWord(pointer, WRAP_BANK)
        val bank = read((pointer + 2) and 0xFFFF)
        return ((bank shl 16) + low + y) and 0xFFFFFF
    }

    private fun sr(): Int {
        val offset = fetch8()
        idle()
        return (s + offset) and 0xFFFF
    }

    private fun srIndY(): Int {
        val base = readWord(sr(), WRAP_BANK)
        idle()
        return ((dbr shl 16) + base + y) and 0xFFFFFF
    }

    private fun fetch8(): Int {
        val value = read((pbr shl 16) or pc)
        pc = (pc + 1) and 0xFFFF
        return value
    }

    private fun fetch16(): Int = fetch8() or (fetch8() shl 8)

    private fun push8(value: Int) {
        write(if (emulation) 0x0100 or (s and 0xFF) else s and 0xFFFF, value)
        s = if (emulation) 0x0100 or ((s - 1) and 0xFF) else (s - 1) and 0xFFFF
    }

    private fun push16(value: Int) {
        push8((value ushr 8) and 0xFF)
        push8(value and 0xFF)
    }

    private fun pull8(): Int {
        s = if (emulation) 0x0100 or ((s + 1) and 0xFF) else (s + 1) and 0xFFFF
        return read(if (emulation) 0x0100 or (s and 0xFF) else s and 0xFFFF)
    }

    private fun pull16(): Int = pull8() or (pull8() shl 8)

    private fun pushWide8(value: Int) {
        write(s and 0xFFFF, value)
        s = (s - 1) and 0xFFFF
    }

    private fun pushWide16(value: Int) {
        pushWide8((value ushr 8) and 0xFF)
        pushWide8(value and 0xFF)
    }

    private fun pullWide8(): Int {
        s = (s + 1) and 0xFFFF
        return read(s and 0xFFFF)
    }

    private fun pullWide16(): Int = pullWide8() or (pullWide8() shl 8)

    private fun readWord(address: Int, wrap: Int): Int {
        val low = read(address)
        val high = read(next(address, wrap))
        return low or (high shl 8)
    }

    private fun writeWord(address: Int, value: Int, wrap: Int) {
        write(address, value and 0xFF)
        write(next(address, wrap), (value ushr 8) and 0xFF)
    }

    private fun next(address: Int, wrap: Int): Int = when (wrap) {
        WRAP_BANK -> (address and 0xFF0000) or ((address + 1) and 0xFFFF)

        WRAP_DP -> {
            if (emulation && d and 0xFF == 0) {
                (address and 0xFF00) or ((address + 1) and 0xFF)
            } else {
                (address and 0xFF0000) or ((address + 1) and 0xFFFF)
            }
        }

        WRAP_DP_NONE -> (address and 0xFF0000) or ((address + 1) and 0xFFFF)

        else -> (address + 1) and 0xFFFFFF
    }

    private fun read(address: Int): Int {
        val masked = address and 0xFFFFFF
        cycles += mem.speed(masked)
        return mem.read8(masked)
    }

    private fun write(address: Int, value: Int) {
        val masked = address and 0xFFFFFF
        cycles += mem.speed(masked)
        mem.write8(masked, value)
    }

    private fun idle() {
        cycles += INTERNAL
    }

    private fun setCarry(value: Boolean) {
        p = if (value) p or 0x01 else p and 0x01.inv()
    }

    private fun setZero(value: Boolean) {
        p = if (value) p or 0x02 else p and 0x02.inv()
    }

    private fun setOverflow(value: Boolean) {
        p = if (value) p or 0x40 else p and 0x40.inv()
    }

    private fun setNZ8(value: Int) {
        p = (p and 0x82.inv()) or (if (value and 0xFF == 0) 0x02 else 0) or (value and 0x80)
    }

    private fun setNZ16(value: Int) {
        p = (p and 0x82.inv()) or (if (value and 0xFFFF == 0) 0x02 else 0) or ((value ushr 8) and 0x80)
    }

    private fun signed8(value: Int): Int = if (value and 0x80 != 0) value - 0x100 else value

    private fun signed16(value: Int): Int = if (value and 0x8000 != 0) value - 0x10000 else value

    fun save(writer: StateWriter) {
        writer.int(a)
        writer.int(x)
        writer.int(y)
        writer.int(s)
        writer.int(d)
        writer.int(dbr)
        writer.int(pbr)
        writer.int(pc)
        writer.int(p)
        writer.bool(emulation)
        writer.bool(waiting)
        writer.bool(stopped)
        writer.bool(nmiPending)
        writer.bool(irqLine)
    }

    fun load(reader: StateReader) {
        a = reader.int()
        x = reader.int()
        y = reader.int()
        s = reader.int()
        d = reader.int()
        dbr = reader.int()
        pbr = reader.int()
        pc = reader.int()
        p = reader.int()
        emulation = reader.bool()
        waiting = reader.bool()
        stopped = reader.bool()
        nmiPending = reader.bool()
        irqLine = reader.bool()
    }

    companion object {
        const val INTERNAL = 6

        private const val WRAP_LONG = 0
        private const val WRAP_BANK = 1
        private const val WRAP_DP = 2
        private const val WRAP_DP_NONE = 3

        private const val ASL = 0
        private const val LSR = 1
        private const val ROL = 2
        private const val ROR = 3
        private const val INC = 4
        private const val DEC = 5
        private const val TSB = 6
        private const val TRB = 7
    }
}
