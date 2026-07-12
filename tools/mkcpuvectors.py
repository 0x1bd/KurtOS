#!/usr/bin/env python3
import json
import os
import struct
import sys
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_DIR = os.path.join(ROOT, "third_party", "testroms")

CASES_PER_OPCODE = 200
PREFIX_BYTES = 768 * 1024


def fetch(url, prefix=None):
    request = urllib.request.Request(url)
    if prefix is not None:
        request.add_header("Range", f"bytes=0-{prefix - 1}")
    with urllib.request.urlopen(request, timeout=120) as response:
        return response.read().decode("utf-8", errors="ignore")


def parse_prefix(text, limit):
    decoder = json.JSONDecoder()
    index = text.find("[")
    if index < 0:
        return []

    index += 1
    cases = []

    while len(cases) < limit:
        while index < len(text) and text[index] in " \t\r\n,":
            index += 1
        if index >= len(text) or text[index] != "{":
            break
        try:
            case, end = decoder.raw_decode(text, index)
        except ValueError:
            break
        cases.append(case)
        index = end

    return cases


def ram_bytes(entries):
    out = struct.pack("<H", len(entries))
    for addr, value in entries:
        out += struct.pack("<IB", addr, value)
    return out


def encode_65816(case):
    out = b""
    for key in ("initial", "final"):
        s = case[key]
        out += struct.pack(
            "<HHHHHHBBBB",
            s["pc"], s["s"], s["a"], s["x"], s["y"], s["d"],
            s["dbr"], s["pbr"], s["p"], s["e"],
        )
    out += struct.pack("<H", len(case["cycles"]))
    out += ram_bytes(case["initial"]["ram"])
    out += ram_bytes(case["final"]["ram"])
    return out


def encode_spc700(case):
    out = b""
    for key in ("initial", "final"):
        s = case[key]
        out += struct.pack(
            "<HBBBBB",
            s["pc"], s["a"], s["x"], s["y"], s["sp"], s["psw"],
        )
    out += struct.pack("<H", len(case["cycles"]))
    out += ram_bytes(case["initial"]["ram"])
    out += ram_bytes(case["final"]["ram"])
    return out


def build_65816():
    base = "https://raw.githubusercontent.com/SingleStepTests/65816/main/v1"
    blob = b""
    total = 0

    for opcode in range(256):
        for mode in ("e", "n"):
            name = f"{opcode:02x}.{mode}.json"
            text = fetch(f"{base}/{name}", PREFIX_BYTES)
            cases = parse_prefix(text, CASES_PER_OPCODE)
            for case in cases:
                blob += encode_65816(case)
            total += len(cases)
        print(f"  {opcode:02x}  {total} cases", flush=True)

    return struct.pack("<4sI", b"V651", total) + blob


def build_spc700():
    base = "https://raw.githubusercontent.com/SingleStepTests/spc700/main/v1"
    blob = b""
    total = 0

    for opcode in range(256):
        name = f"{opcode:02x}.json"
        cases = json.loads(fetch(f"{base}/{name}"))[:CASES_PER_OPCODE]
        for case in cases:
            blob += encode_spc700(case)
        total += len(cases)
        print(f"  {opcode:02x}  {total} cases", flush=True)

    return struct.pack("<4sI", b"VSPC", total) + blob


def main():
    if len(sys.argv) != 2 or sys.argv[1] not in ("65816", "spc700"):
        print(__doc__)
        return 1

    core = sys.argv[1]
    os.makedirs(OUT_DIR, exist_ok=True)

    data = build_65816() if core == "65816" else build_spc700()

    path = os.path.join(OUT_DIR, f"{core}.vec")
    with open(path, "wb") as handle:
        handle.write(data)

    print(f"wrote {path} ({len(data) / 1e6:.1f} MB)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
