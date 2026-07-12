package gameboy.core

class Joypad(private val interrupts: Interrupts) {
    private val pressed = BooleanArray(8)
    private var select = 0x30

    fun setButton(button: Int, down: Boolean) {
        if (button < 0 || button >= pressed.size) return
        if (pressed[button] == down) return

        pressed[button] = down
        if (down) interrupts.request(Interrupts.JOYPAD)
    }

    fun write(value: Int) {
        select = value and 0x30
    }

    fun read(): Int {
        var low = 0x0F

        if (select and 0x10 == 0) {
            if (pressed[RIGHT]) low = low and 0x0E
            if (pressed[LEFT]) low = low and 0x0D
            if (pressed[UP]) low = low and 0x0B
            if (pressed[DOWN]) low = low and 0x07
        }

        if (select and 0x20 == 0) {
            if (pressed[A]) low = low and 0x0E
            if (pressed[B]) low = low and 0x0D
            if (pressed[SELECT]) low = low and 0x0B
            if (pressed[START]) low = low and 0x07
        }

        return 0xC0 or select or low
    }

    companion object {
        const val RIGHT = 0
        const val LEFT = 1
        const val UP = 2
        const val DOWN = 3
        const val A = 4
        const val B = 5
        const val SELECT = 6
        const val START = 7
    }
}
