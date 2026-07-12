package apps

import kapi.Console
import kapi.Graphics
import kapi.Sys
import kapi.Keys
import kapi.ui.Menu
import kapi.ui.MenuItem
import kapi.ui.PixelIcons
import shell.CommandRegistry
import shell.Shell

object Launcher {
    fun run(registry: CommandRegistry): Nothing {
        val menu = Menu(
            title = "KURTOS",
            subtitle = "SELECT AN APP",
            footer = "ARROWS SELECT   ENTER START   F1 TERMINAL",
        )

        var selected = 0

        while (true) {
            val surface = Graphics.surface()
            if (surface == null) {
                Shell.run(registry)
                continue
            }

            val applications = AppRegistry.all()
            val items = applications.map {
                MenuItem(it.name.uppercase(), it.description.uppercase(), iconFor(it.name))
            } + MenuItem("TERMINAL", "COMMAND SHELL", PixelIcons.TERMINAL, hotkey = Keys.F1)

            val choice = menu.choose(surface, items, selected) ?: continue
            selected = choice

            Console.clear()

            if (choice < applications.size) {
                applications[choice].run()
            } else {
                Shell.run(registry)
            }

            Console.clear()
            Sys.collectGarbage()
        }
    }

    private fun iconFor(name: String): PixelIcons.Icon = when (name) {
        "gameboy", "gba" -> PixelIcons.CARTRIDGE
        else -> PixelIcons.QUESTION_BLOCK
    }
}
