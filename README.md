# KurtOS

KurtOS is an operating system that runs on x86 bare metal, focused on emulating consoles from the [4th to 6th generation](https://en.wikipedia.org/wiki/History_of_video_game_consoles#Fourth_generation_(1987%E2%80%932004)).

The kernel, drivers, and emulation are written in Kotlin/Native, built by supplying a custom interface for the K/N compiler to link against at build time.

## Features

### Console emulation

Currently supported:

- Game Boy (DMG)
- Game Boy Color (GBC)
- Game Boy Advance (GBA)
- Super Nintendo Entertainment System (SNES)
- Nintendo 64 (N64)
- *more to come*

#### Known issues

- **SNES** has no support for additional coprocessors (SuperFX, SA-1), making some games unplayable (e.g. *Super Mario World 2: Yoshi's Island*).
- **N64** has visual glitches (1076 known failures. Will be fixed later).
- Audio scaling behaves incorrectly on some systems.
- No multi-monitor support yet, and no HDMI or DisplayPort output.

### GPU

KurtOS ships a custom GPU driver written from scratch, targeting the integrated Vega graphics in the **Ryzen 7 3700U** (Picasso APU). No other GPUs are supported at this time.

Only the **N64** emulator uses hardware-accelerated rendering. All other emulators render on the CPU.

### Peripherals

KurtOS supports keyboards and XInput gamepads. Support for additional peripheral devices is planned for future versions.

### Desktop

KurtOS includes a simple desktop environment with:

- A home page for launching games in an emulator
- A settings page
- A basic command shell