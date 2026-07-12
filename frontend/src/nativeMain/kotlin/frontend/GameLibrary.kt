package frontend

import kapi.FileKind
import kapi.Files
import kapi.emu.Emulator

class Game(val name: String, val path: String, val size: ULong, val emulator: Emulator)

object GameLibrary {
    fun scan(): List<Game> {
        val entries = Files.list(DIRECTORY) ?: return emptyList()

        return entries
            .filter { it.kind == FileKind.File }
            .mapNotNull { entry ->
                val emulator = Emulators.forFile(entry.name) ?: return@mapNotNull null
                Game(baseName(entry.name, emulator), "$DIRECTORY/${entry.name}", entry.size, emulator)
            }
            .sortedBy { it.name }
    }

    fun find(name: String): Game? = scan().firstOrNull { it.name == name }

    fun load(game: Game): ByteArray? = Files.read(game.path, game.size.toUInt())

    private fun baseName(name: String, emulator: Emulator): String {
        for (extension in emulator.extensions) {
            if (name.endsWith(extension)) return name.removeSuffix(extension)
        }
        return name
    }

    private const val DIRECTORY = "/roms"
}
