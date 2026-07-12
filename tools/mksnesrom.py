#!/usr/bin/env python3
"""Assemble a small SNES demo ROM used to smoke-test the emulator on device.

The ROM sets up mode 1 with a checkerboard BG1, one sprite, and recolours the
backdrop from the joypad so every stage of the pipeline (CPU, PPU, DMA, OAM,
auto-joypad) is visible on screen.

    tools/mksnesrom.py assets/roms/kurtos-test.sfc
"""

import struct
import sys


class Assembler:
    def __init__(self, origin=0x8000):
        self.code = bytearray()
        self.origin = origin
        self.labels = {}
        self.fixups = []

    def byte(self, *values):
        for value in values:
            self.code.append(value & 0xFF)
        return self

    def word(self, value):
        return self.byte(value & 0xFF, (value >> 8) & 0xFF)

    @property
    def pc(self):
        return self.origin + len(self.code)

    def label(self, name):
        self.labels[name] = self.pc
        return self

    # --- instructions -------------------------------------------------
    def sei(self):                 return self.byte(0x78)
    def clc(self):                 return self.byte(0x18)
    def xce(self):                 return self.byte(0xFB)
    def rep(self, mask):           return self.byte(0xC2, mask)
    def sep(self, mask):           return self.byte(0xE2, mask)
    def lda8(self, value):         return self.byte(0xA9, value)
    def lda16(self, value):        return self.byte(0xA9).word(value)
    def ldx16(self, value):        return self.byte(0xA2).word(value)
    def ldy16(self, value):        return self.byte(0xA0).word(value)
    def txs(self):                 return self.byte(0x9A)
    def tcd(self):                 return self.byte(0x5B)
    def sta(self, addr):           return self.byte(0x8D).word(addr)
    def stx(self, addr):           return self.byte(0x8E).word(addr)
    def sty(self, addr):           return self.byte(0x8C).word(addr)
    def stz(self, addr):           return self.byte(0x9C).word(addr)
    def lda_abs(self, addr):       return self.byte(0xAD).word(addr)
    def inx(self):                 return self.byte(0xE8)
    def dex(self):                 return self.byte(0xCA)
    def iny(self):                 return self.byte(0xC8)
    def nop(self):                 return self.byte(0xEA)
    def wai(self):                 return self.byte(0xCB)
    def rti(self):                 return self.byte(0x40)
    def and8(self, value):         return self.byte(0x29, value)
    def ora8(self, value):         return self.byte(0x09, value)
    def asl(self):                 return self.byte(0x0A)
    def sta_long(self, addr):
        return self.byte(0x8F, addr & 0xFF, (addr >> 8) & 0xFF, (addr >> 16) & 0xFF)

    def bne(self, target):
        self.byte(0xD0)
        self.fixups.append((len(self.code), target, self.pc + 1))
        return self.byte(0x00)

    def bra(self, target):
        self.byte(0x80)
        self.fixups.append((len(self.code), target, self.pc + 1))
        return self.byte(0x00)

    def cpx16(self, value):        return self.byte(0xE0).word(value)

    def resolve(self):
        for at, target, after in self.fixups:
            delta = self.labels[target] - after
            assert -128 <= delta <= 127, f"branch to {target} out of range ({delta})"
            self.code[at] = delta & 0xFF
        return bytes(self.code)


INIDISP = 0x2100
OBSEL   = 0x2101
OAMADDL = 0x2102
OAMDATA = 0x2104
BGMODE  = 0x2105
BG1SC   = 0x2107
BG12NBA = 0x210B
VMAIN   = 0x2115
VMADDL  = 0x2116
VMDATAL = 0x2118
VMDATAH = 0x2119
CGADD   = 0x2121
CGDATA  = 0x2122
TM      = 0x212C
NMITIMEN = 0x4200
RDNMI    = 0x4210
JOY1L    = 0x4218


def build():
    """Returns (code, labels)."""
    a = Assembler()

    a.sei().clc().xce()
    a.rep(0x30)
    a.ldx16(0x1FFF).txs()
    a.lda16(0x0000).tcd()

    a.sep(0x20)
    a.lda8(0x8F).sta(INIDISP)

    # palette: backdrop + 3 colours + sprite colour
    a.lda8(0x00).sta(CGADD)
    for colour in (0x0000, 0x001F, 0x03E0, 0x7C00):
        a.lda8(colour & 0xFF).sta(CGDATA)
        a.lda8((colour >> 8) & 0xFF).sta(CGDATA)

    a.lda8(0x81).sta(CGADD)
    a.lda8(0xFF).sta(CGDATA)
    a.lda8(0x7F).sta(CGDATA)

    # VRAM: word writes, address 0 (character data for BG tile 1)
    a.lda8(0x80).sta(VMAIN)
    a.rep(0x20)
    a.lda16(0x0010).sta(VMADDL)

    # tile 1: 4bpp, colour index 1 everywhere (planes 0 = 0xFF)
    a.ldx16(0x0000)
    a.label("chr")
    a.lda16(0x00FF).sta(VMDATAL)
    a.inx()
    a.cpx16(0x0008)
    a.bne("chr")

    a.ldx16(0x0000)
    a.label("chr2")
    a.lda16(0x0000).sta(VMDATAL)
    a.inx()
    a.cpx16(0x0008)
    a.bne("chr2")

    # sprite tile at 0x4000 words: solid colour index 1
    a.lda16(0x4000).sta(VMADDL)
    a.ldx16(0x0000)
    a.label("obj")
    a.lda16(0x00FF).sta(VMDATAL)
    a.inx()
    a.cpx16(0x0008)
    a.bne("obj")

    a.ldx16(0x0000)
    a.label("obj2")
    a.lda16(0x0000).sta(VMDATAL)
    a.inx()
    a.cpx16(0x0008)
    a.bne("obj2")

    # tilemap at word 0x0400: every entry points at tile 1
    a.lda16(0x0400).sta(VMADDL)
    a.ldx16(0x0000)
    a.label("map")
    a.lda16(0x0001).sta(VMDATAL)
    a.inx()
    a.cpx16(0x0400)
    a.bne("map")

    a.sep(0x20)

    # OAM: one sprite near the middle
    a.rep(0x20)
    a.lda16(0x0000).sta(OAMADDL)
    a.sep(0x20)
    a.lda8(0x78).sta(OAMDATA)   # x
    a.lda8(0x68).sta(OAMDATA)   # y
    a.lda8(0x00).sta(OAMDATA)   # tile
    a.lda8(0x30).sta(OAMDATA)   # attr: priority 3, palette 0

    a.lda8(0x02).sta(OBSEL)     # sprite chr base 0x4000 words
    a.lda8(0x01).sta(BGMODE)    # mode 1
    a.lda8(0x04).sta(BG1SC)     # map base word 0x0400
    a.lda8(0x00).sta(BG12NBA)   # chr base 0
    a.lda8(0x11).sta(TM)        # BG1 + OBJ on main screen

    a.lda8(0x0F).sta(INIDISP)   # release forced blank
    a.lda8(0x81).sta(NMITIMEN)  # NMI + auto joypad

    # main loop: recolour backdrop from joypad high byte
    a.label("loop")
    a.wai()
    a.lda_abs(RDNMI)

    a.lda_abs(JOY1L)
    a.and8(0xF0)

    a.sta_long(0x7E0000)

    a.lda8(0x00).sta(CGADD)
    a.lda_abs(0x0000)
    a.sta(CGDATA)
    a.lda8(0x00).sta(CGDATA)

    a.bra("loop")

    a.label("nmi")
    a.rti()

    return a.resolve(), a.labels


def main():
    if len(sys.argv) != 2:
        print(__doc__)
        return 1

    code, labels = build()

    rom = bytearray(0x8000)
    rom[0:len(code)] = code

    title = b"KURTOS SNES DEMO     "
    header = 0x7FC0

    rom[header:header + 21] = title
    rom[header + 0x15] = 0x20        # LoROM
    rom[header + 0x16] = 0x00        # ROM only
    rom[header + 0x17] = 0x08        # 256 KB code
    rom[header + 0x18] = 0x00        # no SRAM
    rom[header + 0x19] = 0x01
    rom[header + 0x1A] = 0x33
    rom[header + 0x1B] = 0x00

    checksum = sum(rom) & 0xFFFF
    struct.pack_into("<HH", rom, header + 0x1C, checksum ^ 0xFFFF, checksum)

    # vectors: native NMI at $FFEA, emulation RESET at $FFFC, emulation NMI at $FFFA
    struct.pack_into("<H", rom, header + 0x2A, labels["nmi"])
    struct.pack_into("<H", rom, header + 0x3A, labels["nmi"])
    struct.pack_into("<H", rom, header + 0x3C, 0x8000)

    with open(sys.argv[1], "wb") as handle:
        handle.write(rom)

    print(f"wrote {sys.argv[1]} ({len(rom)} bytes, {len(code)} bytes of code)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
