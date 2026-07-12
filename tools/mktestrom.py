#!/usr/bin/env python3

import sys

ROM_SIZE = 32 * 1024
ENTRY = 0x150


class Assembler:
    def __init__(self, origin):
        self.origin = origin
        self.code = bytearray()
        self.labels = {}
        self.fixups = []

    @property
    def pc(self):
        return self.origin + len(self.code)

    def label(self, name):
        self.labels[name] = self.pc

    def emit(self, *values):
        self.code.extend(values)

    def jr(self, opcode, name):
        self.emit(opcode, 0)
        self.fixups.append((len(self.code) - 1, self.pc, name))

    def resolve(self):
        for index, next_pc, name in self.fixups:
            delta = self.labels[name] - next_pc
            if not -128 <= delta <= 127:
                raise ValueError("jr out of range to %s (%d)" % (name, delta))
            self.code[index] = delta & 0xFF
        return bytes(self.code)


def lo(value):
    return value & 0xFF


def hi(value):
    return (value >> 8) & 0xFF


def tiles():
    data = bytearray()

    data += bytes(16)

    for _ in range(8):
        data += bytes((0xFF, 0x00))

    for _ in range(8):
        data += bytes((0x00, 0xFF))

    diamond = (0x18, 0x3C, 0x7E, 0xFF, 0xFF, 0x7E, 0x3C, 0x18)
    for row in diamond:
        data += bytes((row, row))

    return bytes(data)


def program(tile_data_address, tile_data_length):
    a = Assembler(ENTRY)

    a.emit(0xF3)                                    # di
    a.emit(0xAF)                                    # xor a
    a.emit(0xE0, 0x40)                              # ldh [LCDC], a

    a.emit(0x21, lo(0x8000), hi(0x8000))            # ld hl, $8000
    a.emit(0x11, lo(tile_data_address), hi(tile_data_address))
    a.emit(0x06, tile_data_length)                  # ld b, len
    a.label("copy")
    a.emit(0x1A)                                    # ld a, [de]
    a.emit(0x13)                                    # inc de
    a.emit(0x22)                                    # ld [hl+], a
    a.emit(0x05)                                    # dec b
    a.jr(0x20, "copy")                              # jr nz, copy

    a.emit(0x21, lo(0x9800), hi(0x9800))            # ld hl, $9800
    a.emit(0x3E, 0x01)                              # ld a, 1
    a.emit(0x06, 0x04)                              # ld b, 4
    a.label("outer")
    a.emit(0x0E, 0x00)                              # ld c, 0
    a.label("inner")
    a.emit(0x22)                                    # ld [hl+], a
    a.emit(0xEE, 0x03)                              # xor 3
    a.emit(0x0D)                                    # dec c
    a.jr(0x20, "inner")
    a.emit(0x05)                                    # dec b
    a.jr(0x20, "outer")

    a.emit(0x3E, 0xE4)                              # ld a, $E4
    a.emit(0xE0, 0x47)                              # ldh [BGP], a
    a.emit(0x3E, 0xE4)
    a.emit(0xE0, 0x48)                              # ldh [OBP0], a

    a.emit(0x3E, 0x50)                              # ld a, 80
    a.emit(0xEA, 0x00, 0xFE)                        # ld [$FE00], a
    a.emit(0x3E, 0x50)
    a.emit(0xEA, 0x01, 0xFE)                        # ld [$FE01], a
    a.emit(0x3E, 0x03)                              # ld a, 3
    a.emit(0xEA, 0x02, 0xFE)                        # ld [$FE02], a
    a.emit(0xAF)
    a.emit(0xEA, 0x03, 0xFE)                        # ld [$FE03], a

    a.emit(0xAF)
    a.emit(0xE0, 0x42)                              # ldh [SCY], a
    a.emit(0xE0, 0x43)                              # ldh [SCX], a

    a.emit(0x3E, 0x93)                              # ld a, $93
    a.emit(0xE0, 0x40)                              # ldh [LCDC], a

    a.label("loop")
    a.label("wait_vblank")
    a.emit(0xF0, 0x44)                              # ldh a, [LY]
    a.emit(0xFE, 0x90)                              # cp 144
    a.jr(0x20, "wait_vblank")

    a.emit(0x3E, 0x20)                              # ld a, $20
    a.emit(0xE0, 0x00)                              # ldh [P1], a
    a.emit(0xF0, 0x00)                              # ldh a, [P1]
    a.emit(0xF0, 0x00)                              # ldh a, [P1]
    a.emit(0x2F)                                    # cpl
    a.emit(0xE6, 0x0F)                              # and $0F
    a.emit(0x47)                                    # ld b, a

    a.emit(0xFA, 0x01, 0xFE)                        # ld a, [$FE01]
    a.emit(0xCB, 0x40)                              # bit 0, b
    a.jr(0x28, "no_right")
    a.emit(0x3C)                                    # inc a
    a.label("no_right")
    a.emit(0xCB, 0x48)                              # bit 1, b
    a.jr(0x28, "no_left")
    a.emit(0x3D)                                    # dec a
    a.label("no_left")
    a.emit(0xEA, 0x01, 0xFE)                        # ld [$FE01], a

    a.emit(0xFA, 0x00, 0xFE)                        # ld a, [$FE00]
    a.emit(0xCB, 0x50)                              # bit 2, b
    a.jr(0x28, "no_up")
    a.emit(0x3D)                                    # dec a
    a.label("no_up")
    a.emit(0xCB, 0x58)                              # bit 3, b
    a.jr(0x28, "no_down")
    a.emit(0x3C)                                    # inc a
    a.label("no_down")
    a.emit(0xEA, 0x00, 0xFE)                        # ld [$FE00], a

    a.emit(0xF0, 0x43)                              # ldh a, [SCX]
    a.emit(0x3C)                                    # inc a
    a.emit(0xE0, 0x43)                              # ldh [SCX], a

    a.label("wait_done")
    a.emit(0xF0, 0x44)                              # ldh a, [LY]
    a.emit(0xFE, 0x90)                              # cp 144
    a.jr(0x28, "wait_done")

    a.jr(0x18, "loop")

    return a.resolve()


def build():
    rom = bytearray(b"\x00" * ROM_SIZE)

    rom[0x100] = 0x00
    rom[0x101] = 0xC3
    rom[0x102] = lo(ENTRY)
    rom[0x103] = hi(ENTRY)

    title = b"KURTOS TEST"
    rom[0x134:0x134 + len(title)] = title

    rom[0x147] = 0x00
    rom[0x148] = 0x00
    rom[0x149] = 0x00

    tile_data = tiles()

    body = program(0, len(tile_data))
    tile_address = ENTRY + len(body)
    body = program(tile_address, len(tile_data))

    rom[ENTRY:ENTRY + len(body)] = body
    rom[tile_address:tile_address + len(tile_data)] = tile_data

    checksum = 0
    for i in range(0x134, 0x14D):
        checksum = (checksum - rom[i] - 1) & 0xFF
    rom[0x14D] = checksum

    total = 0
    for i in range(ROM_SIZE):
        if i not in (0x14E, 0x14F):
            total = (total + rom[i]) & 0xFFFF
    rom[0x14E] = hi(total)
    rom[0x14F] = lo(total)

    return bytes(rom)


def main():
    if len(sys.argv) != 2:
        print("usage: mktestrom.py <out.gb>")
        return 1

    with open(sys.argv[1], "wb") as handle:
        handle.write(build())

    print("wrote %s (%d bytes)" % (sys.argv[1], ROM_SIZE))
    return 0


if __name__ == "__main__":
    sys.exit(main())
