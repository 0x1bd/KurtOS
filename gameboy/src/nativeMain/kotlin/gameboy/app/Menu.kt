package gameboy.app

import kapi.Console
import kapi.Gamepad
import kapi.Pad
import kapi.Input
import kapi.Keys
import kapi.Time

object Menu {
    private var selected = 0
    private val padPrevious = BooleanArray(Pad.COUNT)

    fun choose(roms: List<Rom>, status: String?): Rom? {
        if (selected >= roms.size) selected = 0
        flush()

        while (true) {
            render(roms, selected, status)

            when (waitForKey()) {
                UP -> selected = (selected + roms.size - 1) % roms.size
                DOWN -> selected = (selected + 1) % roms.size
                SELECT -> return roms[selected]
                QUIT -> return null
            }
        }
    }

    private fun render(roms: List<Rom>, selected: Int, status: String?) {
        Console.clear()
        Console.println("  KurtOS GameBoy")
        Console.println("")

        roms.forEachIndexed { index, rom ->
            val marker = if (index == selected) ">" else " "
            val size = rom.size / 1024UL
            Console.println("  $marker ${rom.name.padEnd(20)} ${size.toString().padStart(5)} KiB")
        }

        Console.println("")
        if (status != null) {
            Console.println("  $status")
            Console.println("")
        }
        Console.println("  up/down select, enter start, esc quit")
        Console.println("  in game: arrows = d-pad, ${label(Keys.Z)} = A, ${label(Keys.X)} = B,")
        Console.println("           enter = start, backspace = select, esc = quit")
    }

    private fun flush() {
        Gamepad.refresh()

        Input.poll()
        Input.drain()
        while (Console.tryReadChar() != null) {
        }

        if (!Gamepad.available()) return

        Gamepad.poll()
        for (button in 0 until Pad.COUNT) padPrevious[button] = Gamepad.isDown(button)
    }

    private fun label(code: UShort): Char =
        Input.characterFor(code)?.uppercaseChar() ?: '?'

    private fun waitForKey(): Int {
        while (true) {
            Input.poll()

            val key = pressedKey()
            if (key != NONE) return key

            val pad = padKey()
            if (pad != NONE) return pad

            val character = Console.tryReadChar()
            if (character != null) {
                val typed = when (character.lowercaseChar()) {
                    'w', 'k' -> UP
                    's', 'j' -> DOWN
                    '\n', '\r', ' ' -> SELECT
                    'q', ESCAPE -> QUIT
                    else -> NONE
                }

                if (typed != NONE) return typed
            }

            Time.idle()
        }
    }

    private fun padKey(): Int {
        if (!Gamepad.available()) return NONE
        Gamepad.poll()

        var key = NONE

        for (button in 0 until Pad.COUNT) {
            val down = Gamepad.isDown(button)

            if (down && !padPrevious[button] && key == NONE) {
                key = when (button) {
                    Pad.UP -> UP
                    Pad.DOWN -> DOWN
                    Pad.A, Pad.START -> SELECT
                    Pad.B, Pad.GUIDE -> QUIT
                    else -> NONE
                }
            }

            padPrevious[button] = down
        }

        return key
    }

    private fun pressedKey(): Int {
        if (Input.consumePress(Keys.UP)) return UP
        if (Input.consumePress(Keys.DOWN)) return DOWN
        if (Input.consumePress(Keys.ESC)) return QUIT
        return NONE
    }

    private const val ESCAPE = '\u001B'

    private const val NONE = -1
    private const val UP = 0
    private const val DOWN = 1
    private const val SELECT = 2
    private const val QUIT = 3
}
