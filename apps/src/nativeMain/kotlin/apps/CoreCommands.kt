package apps

import kapi.Console
import kapi.Sys
import shell.CommandRegistry

object CoreCommands {
    fun install(registry: CommandRegistry) {
        registry.register("help", "show this message") {
            registry.printHelp()
        }
        registry.register("echo", "print arguments") { args ->
            Console.println(args.joinToString(" "))
        }
        registry.register("mem", "show memory usage") {
            Console.println(Sys.memoryReport())
        }
        registry.register("heap-test", "allocate Kotlin objects and print them") {
            runHeapTest()
        }
        registry.register("clear", "clear the screen") {
            Console.clear()
        }
        registry.register("halt", "halt the system") {
            Console.println("Halting.")
            Sys.halt()
        }
    }

    private fun runHeapTest() {
        data class TestNode(val id: Int, val label: String)

        val nodes = (0 until 5).map { TestNode(it, "node-$it") }
        nodes.forEach { Console.println("  ${it.id}: ${it.label}") }
        Console.println("heap-test passed - ${nodes.size} objects allocated.")
    }
}
