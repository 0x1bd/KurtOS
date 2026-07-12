package frontend

import gameboy.GameBoyEmulator
import gba.GbaEmulator
import kapi.emu.Emulator

object Emulators {
    val all: List<Emulator> = listOf(
        GameBoyEmulator,
        GbaEmulator,
    )

    fun forFile(name: String): Emulator? =
        all.firstOrNull { emulator ->
            emulator.extensions.any { name.endsWith(it, ignoreCase = true) }
        }
}
