import sys
import struct


def parse(path):
    data = open(path, "rb").read()
    if data[:4] != b"\x7fELF":
        raise SystemExit("not an ELF")

    e_shoff = struct.unpack_from("<Q", data, 0x28)[0]
    e_shentsize = struct.unpack_from("<H", data, 0x3A)[0]
    e_shnum = struct.unpack_from("<H", data, 0x3C)[0]
    e_shstrndx = struct.unpack_from("<H", data, 0x3E)[0]

    sections = []
    for i in range(e_shnum):
        off = e_shoff + i * e_shentsize
        name, typ, flags, addr, offset, size, link, info, align, entsize = struct.unpack_from("<IIQQQQIIQQ", data, off)
        sections.append(dict(name=name, type=typ, addr=addr, offset=offset, size=size, link=link, entsize=entsize))

    shstr = sections[e_shstrndx]

    def secname(s):
        end = data.index(b"\x00", shstr["offset"] + s["name"])
        return data[shstr["offset"] + s["name"]:end].decode()

    byname = {secname(s): s for s in sections}

    text = byname[".text"]
    isa = data[text["offset"]:text["offset"] + text["size"]]

    symtab = byname[".symtab"]
    strtab = sections[symtab["link"]]
    kd_addr = None
    kernel_name = None
    kernel_addr = None
    for off in range(symtab["offset"], symtab["offset"] + symtab["size"], symtab["entsize"]):
        st_name, st_info, st_other, st_shndx, st_value, st_size = struct.unpack_from("<IBBHQQ", data, off)
        nend = data.index(b"\x00", strtab["offset"] + st_name)
        sym = data[strtab["offset"] + st_name:nend].decode()
        if sym.endswith(".kd"):
            kd_sec = sections[st_shndx]
            kd_off = kd_sec["offset"] + (st_value - kd_sec["addr"])
            kd = data[kd_off:kd_off + 64]
            kernel_name = sym[:-3]
        elif st_info >> 4 == 1 and st_shndx != 0:
            sym_sec = sections[st_shndx]
            if secname(sym_sec) == ".text":
                kernel_addr = st_value

    if kernel_name is None:
        raise SystemExit("no .kd symbol")

    if kernel_addr is None:
        for off in range(symtab["offset"], symtab["offset"] + symtab["size"], symtab["entsize"]):
            st_name, st_info, st_other, st_shndx, st_value, st_size = struct.unpack_from("<IBBHQQ", data, off)
            nend = data.index(b"\x00", strtab["offset"] + st_name)
            sym = data[strtab["offset"] + st_name:nend].decode()
            if sym == kernel_name and st_shndx != 0:
                kernel_addr = st_value
                break

    entry_offset = (kernel_addr - text["addr"]) if kernel_addr else 0

    private_size = struct.unpack_from("<I", kd, 4)[0]
    if private_size:
        raise SystemExit(
            "%s needs %d bytes of scratch per lane; the dispatch path provides none. "
            "Reduce register pressure or avoid address-taken locals." % (kernel_name, private_size)
        )

    kernarg_size = struct.unpack_from("<I", kd, 8)[0]
    rsrc3 = struct.unpack_from("<I", kd, 44)[0]
    rsrc1 = struct.unpack_from("<I", kd, 48)[0]
    rsrc2 = struct.unpack_from("<I", kd, 52)[0]
    props = struct.unpack_from("<H", kd, 56)[0]
    return isa, rsrc1, rsrc2, rsrc3, kernarg_size, props, entry_offset


def main():
    if len(sys.argv) != 3:
        raise SystemExit("usage: extract_shader.py <in.hsaco> <out.kbin>")

    isa, rsrc1, rsrc2, rsrc3, kernarg_size, props, entry_offset = parse(sys.argv[1])

    out = bytearray()
    out += b"KSH1"
    out += struct.pack("<IIIII", rsrc1, rsrc2, rsrc3, kernarg_size, len(isa))
    out += struct.pack("<HH", props, entry_offset)
    out += isa

    open(sys.argv[2], "wb").write(out)
    sys.stderr.write("rsrc1=0x%x rsrc2=0x%x rsrc3=0x%x kernarg=%d props=0x%x entry=0x%x isa=%d\n" %
                     (rsrc1, rsrc2, rsrc3, kernarg_size, props, entry_offset, len(isa)))


if __name__ == "__main__":
    main()
