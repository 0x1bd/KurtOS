package apps

import apps.snake.SnakeApplication
import kapi.Application

object AppRegistry {
    private val applications: List<Application> = listOf(
        SnakeApplication,
    )

    fun all(): List<Application> = applications

    fun find(name: String): Application? = applications.firstOrNull { it.name == name }
}
