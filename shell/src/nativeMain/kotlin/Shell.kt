package shell

import kapi.Console

class ShellContext(val registry: CommandRegistry)

class ShellCommand(
    val name: String,
    val help: String,
    val aliases: List<String> = emptyList(),
    val execute: ShellContext.(List<String>) -> Unit,
)

class CommandRegistry {
    private val commands = mutableListOf<ShellCommand>()

    fun register(command: ShellCommand) {
        commands.add(command)
    }

    fun register(name: String, help: String, vararg aliases: String, execute: ShellContext.(List<String>) -> Unit) {
        register(ShellCommand(name, help, aliases.toList(), execute))
    }

    fun dispatch(input: String): Boolean {
        val parts = input.trim().split(" ").filter { it.isNotEmpty() }
        val verb = parts.firstOrNull() ?: return true
        val command = commands.firstOrNull { it.name == verb || verb in it.aliases } ?: return false
        command.execute(ShellContext(this), parts.drop(1))
        return true
    }

    fun printHelp() {
        Console.println("Commands:")
        commands.forEach { command ->
            Console.println("  ${command.name.padEnd(12)} - ${command.help}")
        }
    }
}

object Shell {
    fun run(registry: CommandRegistry): Nothing {
        while (true) {
            Console.print("KOS> ")
            val command = Console.readLine()
            if (!registry.dispatch(command)) {
                val trimmed = command.trim()
                if (trimmed.isNotEmpty()) {
                    Console.println("Unknown command: $trimmed. Type 'help'.")
                }
            }
        }
    }
}
