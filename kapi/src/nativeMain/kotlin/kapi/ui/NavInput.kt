package kapi.ui

import kapi.Console
import kapi.Gamepad
import kapi.Input
import kapi.Keys
import kapi.Pad
import kapi.Time

class NavFrame(val dx: Int, val dy: Int, val activate: Boolean, val back: Boolean) {
    val moved: Boolean get() = dx != 0 || dy != 0
    val any: Boolean get() = moved || activate || back

    companion object {
        val NONE = NavFrame(0, 0, false, false)
    }
}

object NavInput {
    private const val UP = 0
    private const val DOWN = 1
    private const val LEFT = 2
    private const val RIGHT = 3

    private const val REPEAT_DELAY = 300UL
    private const val REPEAT_RATE = 90UL

    private val padPrevious = BooleanArray(Pad.COUNT)
    private val padEdge = BooleanArray(Pad.COUNT)

    private val held = BooleanArray(4)
    private val repeatAt = ULongArray(4)

    fun prime() {
        Input.drain()
        while (Console.tryReadChar() != null) {
        }

        if (Gamepad.available()) {
            Gamepad.poll()
            for (button in 0 until Pad.COUNT) padPrevious[button] = Gamepad.isDown(button)
        } else {
            for (button in 0 until Pad.COUNT) padPrevious[button] = false
        }

        for (button in 0 until Pad.COUNT) padEdge[button] = false
        for (dir in 0 until 4) {
            held[dir] = false
            repeatAt[dir] = 0UL
        }
    }

    fun read(): NavFrame {
        if (Gamepad.available()) Gamepad.poll()

        for (button in 0 until Pad.COUNT) {
            val down = Gamepad.isDown(button)
            padEdge[button] = down && !padPrevious[button]
            padPrevious[button] = down
        }

        while (Console.tryReadChar() != null) {
        }

        val now = Time.uptimeMillis()

        val up = repeat(UP, Input.isKeyDown(Keys.UP) || Input.isKeyDown(Keys.W) || pad(Pad.UP) || pad(Pad.DPAD_UP), now)
        val down = repeat(DOWN, Input.isKeyDown(Keys.DOWN) || Input.isKeyDown(Keys.S) || pad(Pad.DOWN) || pad(Pad.DPAD_DOWN), now)
        val left = repeat(LEFT, Input.isKeyDown(Keys.LEFT) || Input.isKeyDown(Keys.A) || pad(Pad.LEFT) || pad(Pad.DPAD_LEFT), now)
        val right = repeat(RIGHT, Input.isKeyDown(Keys.RIGHT) || Input.isKeyDown(Keys.D) || pad(Pad.RIGHT) || pad(Pad.DPAD_RIGHT), now)

        var dy = 0
        if (up) dy = -1 else if (down) dy = 1

        var dx = 0
        if (dy == 0) {
            if (left) dx = -1 else if (right) dx = 1
        }

        val activate = Input.consumePress(Keys.ENTER) || Input.consumePress(Keys.SPACE) ||
            padEdge[Pad.A] || padEdge[Pad.START]
        val back = Input.consumePress(Keys.ESC) || Input.consumePress(Keys.BACKSPACE) || padEdge[Pad.B]

        return NavFrame(dx, dy, activate, back)
    }

    fun padPressed(button: Int): Boolean = padEdge[button]

    private fun pad(button: Int): Boolean = Gamepad.isDown(button)

    private fun repeat(dir: Int, down: Boolean, now: ULong): Boolean {
        if (!down) {
            held[dir] = false
            return false
        }
        if (!held[dir]) {
            held[dir] = true
            repeatAt[dir] = now + REPEAT_DELAY
            return true
        }
        if (now >= repeatAt[dir]) {
            repeatAt[dir] = now + REPEAT_RATE
            return true
        }
        return false
    }
}
