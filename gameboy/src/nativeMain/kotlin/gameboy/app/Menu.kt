package gameboy.app

import kapi.Surface
import kapi.ui.Menu
import kapi.ui.MenuItem
import kapi.ui.PixelIcons

object RomMenu {
    private var selected = 0

    fun choose(surface: Surface, roms: List<Rom>, status: String?): Rom? {
        if (selected >= roms.size) selected = 0

        val menu = Menu(
            title = "GAMEBOY",
            subtitle = status?.uppercase() ?: "PICK A CARTRIDGE",
            footer = "ENTER START   ESC BACK   IN GAME: Z=A X=B ESC=QUIT",
        )

        val items = roms.map {
            MenuItem(it.name.uppercase(), "${it.size / 1024UL} KIB", PixelIcons.CARTRIDGE)
        }

        val choice = menu.choose(surface, items, selected) ?: return null
        selected = choice
        return roms[choice]
    }
}
