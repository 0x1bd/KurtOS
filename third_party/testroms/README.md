# Test ROMs

Freely distributable hardware test ROMs, vendored so the emulator test suites can run
offline. They are not games and carry no Nintendo code.

| ROM | Source | Reports via |
| --- | --- | --- |
| arm.gba, thumb.gba, memory.gba | [jsmolka/gba-tests](https://github.com/jsmolka/gba-tests) (MIT) | `r12` -- 0 means every test passed, otherwise the number of the first failing test |
| cpu_instrs.gb, instr_timing.gb | blargg's gb-test-roms, via [retrio/gb-test-roms](https://github.com/retrio/gb-test-roms) | the serial port -- the ROM prints its results as text |

## SNES

- `65816.vec`, `spc700.vec` -- **generated, not committed**. Run
  `tools/mkcpuvectors.py 65816` / `tools/mkcpuvectors.py spc700` to build them from
  the TomHarte SingleStepTests suites (SingleStepTests/65816, SingleStepTests/spc700).
  200 cases per opcode; the 65816 set is fetched as a byte-range prefix of each
  opcode file because the full suite is ~3 GB of JSON.

  `Cpu65816Test` and `Spc700Test` skip silently when these files are absent, so the
  committed test tier still passes on a fresh clone.
