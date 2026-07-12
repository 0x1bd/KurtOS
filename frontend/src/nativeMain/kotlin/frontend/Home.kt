package frontend

import kapi.Console
import kapi.Graphics
import kapi.Keys
import kapi.Sys
import kapi.emu.Emulator
import kapi.ui.Menu
import kapi.ui.MenuChoice
import kapi.ui.MenuItem
import kapi.ui.PixelIcons
import shell.CommandRegistry
import shell.Shell

object Home {
    fun run(registry: CommandRegistry): Nothing {
        var selected = 0
        var status: String? = null

        while (true) {
            val surface = Graphics.surface()
            if (surface == null) {
                Shell.run(registry)
                continue
            }

            val games = GameLibrary.scan()

            val menu = Menu(
                title = "KURTOS",
                subtitle = (status ?: subtitle(games)).uppercase(),
                footer = "ARROWS SELECT   ENTER PLAY",
            )

            val items = if (games.isEmpty()) {
                listOf(MenuItem("NO GAMES INSTALLED", "ADD ROMS TO ${GameLibrary.DIRECTORY.uppercase()}", PixelIcons.QUESTION_BLOCK))
            } else {
                games.map { MenuItem(it.name.uppercase(), sublabel(it), iconFor(it.emulator)) }
            }

            when (val choice = menu.choose(surface, items, selected, hotkeys = listOf(Keys.F1))) {
                is MenuChoice.Row -> {
                    status = null
                    if (games.isNotEmpty()) {
                        selected = choice.index
                        Console.clear()
                        status = Player.play(surface, games[choice.index])
                        Console.clear()
                    }
                }

                is MenuChoice.Key -> {
                    status = null
                    Console.clear()
                    Shell.run(registry)
                    Console.clear()
                }

                MenuChoice.Back -> status = null
            }

            Sys.collectGarbage()
        }
    }

    private fun subtitle(games: List<Game>): String {
        if (games.isEmpty()) return "GAME LIBRARY"

        val systems = games.map { it.emulator.id }.distinct().size
        val gamesPart = if (games.size == 1) "1 GAME" else "${games.size} GAMES"
        val systemsPart = if (systems == 1) "1 SYSTEM" else "$systems SYSTEMS"

        return "$gamesPart   $systemsPart"
    }

    private fun sublabel(game: Game): String = "${game.emulator.system}  ${game.size / 1024UL} KIB"

    private fun iconFor(emulator: Emulator): PixelIcons.Icon = when (emulator.id) {
        "gba" -> PixelIcons.CARTRIDGE_GBA
        else -> PixelIcons.CARTRIDGE
    }
}
