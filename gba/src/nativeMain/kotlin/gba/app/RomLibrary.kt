package gba.app

import kapi.FileKind
import kapi.Files

class Rom(val name: String, val path: String, val size: ULong)

object RomLibrary {
    fun list(): List<Rom> {
        val entries = Files.list(DIRECTORY) ?: return emptyList()

        return entries
            .filter { it.kind == FileKind.File && it.name.endsWith(".gba") }
            .map { Rom(baseName(it.name), "$DIRECTORY/${it.name}", it.size) }
            .sortedBy { it.name }
    }

    fun load(rom: Rom): ByteArray? = Files.read(rom.path, rom.size.toUInt())

    private fun baseName(name: String): String = name.removeSuffix(".gba")

    private const val DIRECTORY = "/roms"
}
