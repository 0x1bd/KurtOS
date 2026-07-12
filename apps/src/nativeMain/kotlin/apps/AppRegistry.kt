package apps

import apps.snake.SnakeApplication
import gameboy.app.GameBoyApplication
import gba.app.GbaApplication
import kapi.Application

object AppRegistry {
    private val applications: List<Application> = listOf(
        GameBoyApplication,
        GbaApplication,
        SnakeApplication,
    )

    fun all(): List<Application> = applications

    fun find(name: String): Application? = applications.firstOrNull { it.name == name }
}
