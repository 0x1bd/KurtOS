# KurtOS
KurtOS is an operating system capable of running on x86 bare metal whose main focus is to emulate consoles from the [4th to 6th generation](https://en.wikipedia.org/wiki/History_of_video_game_consoles).

KurtOS uses Kotlin/Native for its kernel, drivers and emulation. This is done by supplying a custom interface for the K/N compiler to link against at build time.

## Features
### Supported consoles for emulation
- Game Boy (DMG)
- Game Boy Color (GBC)
- Game Boy Advance (GBA)
- SNES (Super Nintendo Entertainment System)
- N64 (Nintendo 64)
- _more to come_
#### Known issues:
- SNES does not support any additional coprocessors (SuperFX, SA-1) which makes some games unplayable (Super Mario World 2: Yoshi’s Island...)
- N64 has visual glitches and performance problems. (Ryzen 7 3700U avg FPS ~40 / 50 in SM64)
- Audio scaling behaves incorrectly on different systems.
- There is no multi-monitor support yet, nor can you output via HDMI or DP.
### Peripherals
KurtOS supports keyboards and XInput gamepads. This is subject to change in future versions to support more peripheral devices.
### Desktop
KurtOS features a simple desktop environment which includes:
- A home page for playing a game on an emulator
- A settings page
- A very simple command shell