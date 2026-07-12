package kernel.shellext

import apps.AppRegistry
import hal.Arch
import hal.BootInfo
import hal.MemoryKind
import hal.Serial
import kapi.Console
import kapi.FileKind
import kapi.Files
import kapi.Graphics
import kapi.Input
import kapi.Sys
import kapi.Time
import kernel.arch.Acpi
import kernel.arch.Apic
import kernel.drivers.I8042
import kernel.drivers.Keyboard
import kernel.drivers.KeyboardLayout
import kernel.memory.PageAllocator
import shell.CommandRegistry

object KernelShell {
    fun install(registry: CommandRegistry) {
        registry.register("uptime", "show milliseconds since boot") {
            Console.println("${Time.uptimeMillis()} ms (timer ${Apic.timerHz} Hz via ${Apic.calibrationSource})")
        }

        registry.register("devices", "list what the kernel found") {
            Console.println("serial:   ${if (Serial.isPresent) "16550 on COM1" else "absent"}")
            Console.println("keyboard: ${Input.status()}")
            Console.println("graphics: ${Graphics.status()}")
            Console.println("storage:  ${Files.status()}")
            Console.println("acpi:     ${if (Acpi.available) "ioapic at 0x${Acpi.ioApicAddress.toString(16)}" else "unavailable"}")
        }

        registry.register("memmap", "print the bootloader memory map") {
            BootInfo.memoryMap.forEach { region ->
                val end = region.base + region.length
                val kib = region.length / 1024UL
                Console.println(
                    "0x${region.base.toString(16).padStart(12, '0')}-0x${end.toString(16).padStart(12, '0')} " +
                        "${kib.toString().padStart(9)} KiB  ${region.kind}"
                )
            }
        }

        registry.register("irqstats", "show interrupt counters") {
            Console.println("ticks:   ${Arch.ticks()}")
            Console.println("dropped: ${Arch.droppedScancodes()} scancodes")
            Console.println("pages:   ${PageAllocator.freePages}/${PageAllocator.totalPages} free")
        }

        registry.register("gfx-test", "draw a test pattern") {
            val surface = Graphics.surface()
            if (surface == null) {
                Console.println("Graphics: ${Graphics.status()}")
                return@register
            }
            surface.clear(0x00101820u)
            surface.fillRect(24u, 24u, surface.width / 3u, surface.height / 3u, 0x00d94848u)
            surface.fillRect(surface.width / 3u, surface.height / 4u, surface.width / 3u, surface.height / 3u, 0x0048d96fu)
            surface.fillRect(surface.width / 2u, surface.height / 2u, surface.width / 3u, surface.height / 3u, 0x004875d9u)
            surface.presentAll()
            Console.println("Test pattern drawn. Run 'clear' to restore the console.")
        }

        registry.register("ls", "list a directory") { args ->
            val path = args.getOrNull(0) ?: "/"
            val entries = Files.list(path)
            if (entries == null) {
                Console.println("not found: $path (${Files.status()})")
                return@register
            }
            entries.forEach { entry ->
                val kind = if (entry.kind == FileKind.Directory) "dir " else "file"
                Console.println("$kind ${entry.size.toString().padStart(10)} ${entry.name}")
            }
        }

        registry.register("cat", "print a file") { args ->
            val path = args.getOrNull(0)
            if (path == null) {
                Console.println("usage: cat <path>")
                return@register
            }
            val bytes = Files.read(path, args.getOrNull(1)?.toUIntOrNull() ?: 512u)
            if (bytes == null) {
                Console.println("not found: $path")
                return@register
            }
            Console.println(printable(bytes))
        }

        registry.register("apps", "list installed applications") {
            AppRegistry.all().forEach { Console.println("${it.name.padEnd(12)} ${it.description}") }
        }

        registry.register("run", "run an application") { args ->
            val name = args.getOrNull(0)
            if (name == null) {
                Console.println("usage: run <app>")
                return@register
            }

            val application = AppRegistry.find(name)
            if (application == null) {
                Console.println("no such application: $name")
                return@register
            }

            application.run()
            Console.clear()
            Sys.collectGarbage()
        }

        registry.register("keymap", "show or set the keyboard layout") { args ->
            val name = args.getOrNull(0)
            if (name == null) {
                Console.println("layout: ${Keyboard.layoutName}")
                Console.println("available: ${KeyboardLayout.all.joinToString(", ") { it.name }}")
                return@register
            }

            if (!Keyboard.selectLayout(name)) {
                Console.println("unknown layout: $name")
                return@register
            }

            Console.println("layout: ${Keyboard.layoutName}")
        }

        registry.register("gc", "run a garbage collection") {
            Console.println("before: ${Sys.memoryReport()}")
            val start = Time.uptimeMillis()
            Sys.collectGarbage()
            val elapsed = Time.uptimeMillis() - start
            Console.println("after:  ${Sys.memoryReport()} (${elapsed} ms)")
        }

        registry.register("crash", "trigger a fault, to test the panic handler") { args ->
            when (args.getOrNull(0)) {
                "pf" -> hal.RawMemory.read64(0xDEAD_0000_0000UL)
                "de" -> Console.println("${100 / zero()}")
                else -> Console.println("usage: crash <pf|de>")
            }
        }
    }

    private fun zero(): Int = (Arch.ticks() and 0UL).toInt()

    private fun printable(bytes: ByteArray): String {
        val builder = StringBuilder()
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            builder.append(
                if (value in 32..126 || value == 10 || value == 13 || value == 9) value.toChar() else '.'
            )
        }
        return builder.toString()
    }
}
