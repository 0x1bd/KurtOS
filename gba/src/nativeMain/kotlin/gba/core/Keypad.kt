package gba.core

class Keypad {
    var state = 0x3FF
    var control = 0

    fun setButtons(pressed: Int) {
        state = pressed.inv() and 0x3FF
    }

    companion object {
        const val A = 0x001
        const val B = 0x002
        const val SELECT = 0x004
        const val START = 0x008
        const val RIGHT = 0x010
        const val LEFT = 0x020
        const val UP = 0x040
        const val DOWN = 0x080
        const val R = 0x100
        const val L = 0x200
    }
}
