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
            .filter { it.kind == FileKind.File && it.name.endsWith(".gb") }
            .map { Rom(it.name.removeSuffix(".gb"), "$DIRECTORY/${it.name}", it.size) }
            .sortedBy { it.name }
    }

    fun load(rom: Rom): ByteArray? = Files.read(rom.path, MAX_ROM_BYTES)
}
