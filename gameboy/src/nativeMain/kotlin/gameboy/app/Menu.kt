package gameboy.app

import kapi.Console
import kapi.Input
import kapi.Keys
import kapi.Time

object Menu {
    fun choose(roms: List<Rom>): Rom? {
        var selected = 0

        while (true) {
            render(roms, selected)

            when (waitForKey()) {
                UP -> selected = (selected + roms.size - 1) % roms.size
                DOWN -> selected = (selected + 1) % roms.size
                SELECT -> return roms[selected]
                QUIT -> return null
            }
        }
    }

    private fun render(roms: List<Rom>, selected: Int) {
        Console.clear()
        Console.println("  KurtOS GameBoy")
        Console.println("")

        roms.forEachIndexed { index, rom ->
            val marker = if (index == selected) ">" else " "
            val size = rom.size / 1024UL
            Console.println("  $marker ${rom.name.padEnd(20)} ${size.toString().padStart(5)} KiB")
        }

        Console.println("")
        Console.println("  up/down select, enter start, esc quit")
        Console.println("  in game: arrows = d-pad, ${label(Keys.Z)} = A, ${label(Keys.X)} = B,")
        Console.println("           enter = start, backspace = select, esc = quit")
    }

    private fun label(code: UShort): Char =
        Input.characterFor(code)?.uppercaseChar() ?: '?'

    private fun waitForKey(): Int {
        while (true) {
            Input.poll()

            while (true) {
                val event = Input.nextEvent() ?: break
                if (!event.pressed) continue

                when (event.code) {
                    Keys.UP, Keys.W -> return UP
                    Keys.DOWN, Keys.S -> return DOWN
                    Keys.ENTER, Keys.SPACE -> return SELECT
                    Keys.ESC, Keys.Q -> return QUIT
                }
            }

            val character = Console.tryReadChar()
            if (character != null) {
                when (character.lowercaseChar()) {
                    'w', 'k' -> return UP
                    's', 'j' -> return DOWN
                    '\n', '\r', ' ' -> return SELECT
                    'q' -> return QUIT
                }
            }

            Time.idle()
        }
    }

    private const val UP = 0
    private const val DOWN = 1
    private const val SELECT = 2
    private const val QUIT = 3
}
