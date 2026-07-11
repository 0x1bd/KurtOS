package apps.snake

import kapi.Application
import kapi.Console
import kapi.Graphics
import kapi.Input
import kapi.KeyEvent
import kapi.Keys
import kapi.Surface
import kapi.Time

private const val ESCAPE = '\u001B'

object SnakeApplication : Application {
    override val name = "snake"
    override val description = "the classic"

    private const val BOARD_WIDTH = 32
    private const val BOARD_HEIGHT = 24
    private const val MAX_LENGTH = BOARD_WIDTH * BOARD_HEIGHT
    private const val TICK_MS: ULong = 115UL

    private const val COLOR_BACKGROUND: UInt = 0x00070b0eu
    private const val COLOR_BOARD: UInt = 0x00101812u
    private const val COLOR_BORDER: UInt = 0x004d6f55u
    private const val COLOR_FOOD: UInt = 0x00dc3f32u
    private const val COLOR_HEAD: UInt = 0x00b7f56au
    private const val COLOR_BODY: UInt = 0x0048b85au
    private const val COLOR_GAME_OVER: UInt = 0x00882222u

    private var quitRequested = false

    override fun run() {
        quitRequested = false

        val surface = Graphics.surface()
        if (surface == null) {
            Console.println("Graphics: ${Graphics.status()}")
            return
        }

        val cell = minOf(
            surface.width / (BOARD_WIDTH.toUInt() + 2u),
            surface.height / (BOARD_HEIGHT.toUInt() + 3u),
        )
        if (cell == 0u) {
            Console.println("Snake: the screen is too small")
            return
        }

        Input.drain()
        while (Console.tryReadChar() != null) {
        }

        val snakeX = IntArray(MAX_LENGTH)
        val snakeY = IntArray(MAX_LENGTH)
        var length = 5
        val startX = BOARD_WIDTH / 2
        val startY = BOARD_HEIGHT / 2
        for (i in 0 until length) {
            snakeX[i] = startX - i
            snakeY[i] = startY
        }

        var direction = Direction.Right
        var seed = Time.uptimeMillis().toUInt() xor 0x51f15eedu
        var foodX = 0
        var foodY = 0

        fun random(): Int {
            seed = seed * 1664525u + 1013904223u
            return ((seed shr 16).toInt() and 0x7fff)
        }

        fun placeFood() {
            var attempts = 0
            while (attempts < 2048) {
                val x = random() % BOARD_WIDTH
                val y = random() % BOARD_HEIGHT
                var occupied = false
                for (i in 0 until length) {
                    if (snakeX[i] == x && snakeY[i] == y) {
                        occupied = true
                        break
                    }
                }
                if (!occupied) {
                    foodX = x
                    foodY = y
                    return
                }
                attempts++
            }
            foodX = 0
            foodY = 0
        }

        placeFood()
        render(surface, cell, snakeX, snakeY, length, foodX, foodY, gameOver = false)

        var score = 0
        var nextTick = Time.uptimeMillis() + TICK_MS
        var gameOver = false

        while (!gameOver) {
            Input.poll()
            direction = requestedTurn(direction)

            if (quitRequested) {
                Console.println("Snake exited. Score: $score")
                return
            }

            val now = Time.uptimeMillis()
            if (now < nextTick) {
                Time.idle()
                continue
            }
            nextTick = now + TICK_MS

            val nextX = snakeX[0] + direction.dx
            val nextY = snakeY[0] + direction.dy
            val grow = nextX == foodX && nextY == foodY

            if (nextX !in 0 until BOARD_WIDTH || nextY !in 0 until BOARD_HEIGHT) {
                gameOver = true
            } else {
                val limit = if (grow) length else length - 1
                for (i in 0 until limit) {
                    if (snakeX[i] == nextX && snakeY[i] == nextY) {
                        gameOver = true
                        break
                    }
                }
            }

            if (!gameOver) {
                val newLength = if (grow && length < MAX_LENGTH) length + 1 else length
                for (i in newLength - 1 downTo 1) {
                    snakeX[i] = snakeX[i - 1]
                    snakeY[i] = snakeY[i - 1]
                }
                snakeX[0] = nextX
                snakeY[0] = nextY
                length = newLength
                if (grow) {
                    score++
                    placeFood()
                }
            }

            render(surface, cell, snakeX, snakeY, length, foodX, foodY, gameOver)
        }

        Console.println("Snake game over. Score: $score")

        val end = Time.uptimeMillis() + 2_000UL
        while (Time.uptimeMillis() < end) {
            Input.poll()
            if (Input.isKeyDown(Keys.ESC) || Input.isKeyDown(Keys.Q)) break
            Time.idle()
        }
    }

    private fun requestedTurn(current: Direction): Direction {
        var direction = current

        while (true) {
            val event = Input.nextEvent() ?: break
            if (!event.pressed) continue
            if (event.code == Keys.ESC || event.code == Keys.Q) {
                quitRequested = true
            } else {
                direction = fromKey(event, direction)
            }
        }

        while (true) {
            val c = Console.tryReadChar() ?: break
            direction = fromChar(c, direction)
        }

        return direction
    }

    private fun fromKey(event: KeyEvent, current: Direction): Direction = when (event.code) {
        Keys.UP, Keys.W -> if (current == Direction.Down) current else Direction.Up
        Keys.DOWN, Keys.S -> if (current == Direction.Up) current else Direction.Down
        Keys.LEFT, Keys.A -> if (current == Direction.Right) current else Direction.Left
        Keys.RIGHT, Keys.D -> if (current == Direction.Left) current else Direction.Right
        else -> current
    }

    private fun fromChar(c: Char, current: Direction): Direction = when (c) {
        'w', 'W' -> if (current == Direction.Down) current else Direction.Up
        's', 'S' -> if (current == Direction.Up) current else Direction.Down
        'a', 'A' -> if (current == Direction.Right) current else Direction.Left
        'd', 'D' -> if (current == Direction.Left) current else Direction.Right
        'q', 'Q', ESCAPE -> {
            quitRequested = true
            current
        }
        else -> current
    }

    private fun render(
        surface: Surface,
        cell: UInt,
        snakeX: IntArray,
        snakeY: IntArray,
        length: Int,
        foodX: Int,
        foodY: Int,
        gameOver: Boolean,
    ) {
        val boardWidth = BOARD_WIDTH.toUInt() * cell
        val boardHeight = BOARD_HEIGHT.toUInt() * cell
        val originX = (surface.width - boardWidth) / 2u
        val originY = (surface.height - boardHeight) / 2u
        val inset = maxOf(1u, cell / 8u)

        surface.clear(COLOR_BACKGROUND)
        surface.fillRect(
            originX - cell,
            originY - cell,
            boardWidth + cell * 2u,
            boardHeight + cell * 2u,
            if (gameOver) COLOR_GAME_OVER else COLOR_BORDER,
        )
        surface.fillRect(originX, originY, boardWidth, boardHeight, COLOR_BOARD)

        drawCell(surface, originX, originY, cell, foodX, foodY, COLOR_FOOD, inset)

        for (i in length - 1 downTo 0) {
            val color = when {
                gameOver && i == 0 -> COLOR_GAME_OVER
                i == 0 -> COLOR_HEAD
                else -> COLOR_BODY
            }
            drawCell(surface, originX, originY, cell, snakeX[i], snakeY[i], color, inset)
        }

        surface.presentAll()
    }

    private fun drawCell(
        surface: Surface,
        originX: UInt,
        originY: UInt,
        cell: UInt,
        x: Int,
        y: Int,
        color: UInt,
        inset: UInt,
    ) {
        surface.fillRect(
            originX + x.toUInt() * cell + inset,
            originY + y.toUInt() * cell + inset,
            maxOf(1u, cell - inset * 2u),
            maxOf(1u, cell - inset * 2u),
            color,
        )
    }

    private enum class Direction(val dx: Int, val dy: Int) {
        Up(0, -1),
        Down(0, 1),
        Left(-1, 0),
        Right(1, 0),
    }
}
