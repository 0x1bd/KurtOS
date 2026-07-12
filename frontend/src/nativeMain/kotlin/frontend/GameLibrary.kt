package frontend

import kapi.FileKind
import kapi.Files
import kapi.emu.Emulator

class Game(val name: String, val path: String, val size: ULong, val emulator: Emulator)

object GameLibrary {
    const val DIRECTORY = "/roms"
    const val SAVES = "/saves"
    const val STATES = "/states"

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

    fun savePath(game: Game): String = "$SAVES/${game.name}.sav"

    fun statePath(game: Game): String = "$STATES/${game.name}.state"

    fun loadState(game: Game, maxBytes: UInt): ByteArray? = Files.read(statePath(game), maxBytes)

    fun storeState(game: Game, data: ByteArray): Boolean {
        val path = statePath(game)
        if (!Files.writable(path)) return false

        Files.mkdir(STATES)
        return Files.write(path, data)
    }

    fun loadSave(game: Game, maxBytes: UInt): ByteArray? = Files.read(savePath(game), maxBytes)

    fun storeSave(game: Game, data: ByteArray): Boolean {
        val path = savePath(game)
        if (!Files.writable(path)) return false

        Files.mkdir(SAVES)
        return Files.write(path, data)
    }

    private fun baseName(name: String, emulator: Emulator): String {
        for (extension in emulator.extensions) {
            if (name.endsWith(extension, ignoreCase = true)) {
                return name.dropLast(extension.length)
            }
        }
        return name
    }
}
