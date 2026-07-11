package kapi

interface Application {
    val name: String
    val description: String

    fun run()
}
