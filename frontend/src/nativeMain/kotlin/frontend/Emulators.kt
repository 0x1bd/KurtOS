package frontend

import gameboy.GameBoyEmulator
import gba.GbaEmulator
import kapi.emu.Emulator
import snes.SnesEmulator

object Emulators {
    val all: List<Emulator> = listOf(
        GameBoyEmulator,
        GbaEmulator,
        SnesEmulator,
    )

    fun forFile(name: String): Emulator? =
        all.firstOrNull { emulator ->
            emulator.extensions.any { name.endsWith(it, ignoreCase = true) }
        }
}
