# Test ROMs

Freely distributable hardware test ROMs, vendored so the emulator test suites can run
offline. They are not games and carry no Nintendo code.

| ROM | Source | Reports via |
| --- | --- | --- |
| arm.gba, thumb.gba, memory.gba | [jsmolka/gba-tests](https://github.com/jsmolka/gba-tests) (MIT) | `r12` -- 0 means every test passed, otherwise the number of the first failing test |
| cpu_instrs.gb, instr_timing.gb | blargg's gb-test-roms, via [retrio/gb-test-roms](https://github.com/retrio/gb-test-roms) | the serial port -- the ROM prints its results as text |
