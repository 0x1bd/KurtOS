package gameboy.app

import kapi.FileKind
import kapi.Files

class Rom(val name: String, val path: String, val size: ULong)

object RomLibrary {
    private const val DIRECTORY = "/roms"
    private const val MAX_ROM_BYTES = 8388608u

    fun list(): List<Rom> {
        val entries = Files.list(DIRECTORY) ?: return emptyList()

        return entries
            .filter { it.kind == FileKind.File && isRom(it.name) }
            .map { Rom(baseName(it.name), "$DIRECTORY/${it.name}", it.size) }
            .sortedBy { it.name }
    }

    fun load(rom: Rom): ByteArray? = Files.read(rom.path, MAX_ROM_BYTES)

    private fun isRom(name: String): Boolean = name.endsWith(".gb") || name.endsWith(".gbc")

    private fun baseName(name: String): String = name.removeSuffix(".gbc").removeSuffix(".gb")
}
