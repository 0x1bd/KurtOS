package kernel.shellext

import frontend.GameLibrary
import frontend.Player
import hal.Arch
import hal.BootInfo
import hal.Serial
import kapi.Console
import kapi.FileKind
import kapi.Files
import kapi.Graphics
import kapi.Input
import kapi.Keys
import kapi.Pad
import kapi.Sys
import kapi.Time
import kernel.arch.Acpi
import kernel.arch.Apic
import kernel.ui.OSD
import kernel.audio.AudioService
import kapi.Audio
import kernel.drivers.Pci
import kernel.drivers.gpu.GpuLog
import kernel.drivers.gpu.vega.GpuService
import kernel.drivers.gpu.vega.VegaReg
import kernel.drivers.usb.GamepadService
import kernel.drivers.usb.USBService
import kernel.drivers.usb.UsbLock
import kernel.drivers.Keyboard
import kernel.drivers.KeyboardLayout
import kernel.fs.StorageService
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

        registry.register("storage", "show the writable volume; 'storage rescan' to re-attach") { args ->
            if (args.getOrNull(0) == "rescan") {
                StorageService.refresh()
            }
            StorageService.describe().forEach { Console.println(it) }
        }

        registry.register("write", "write text to a file on the writable volume") { args ->
            val path = args.getOrNull(0)
            if (path == null || args.size < 2) {
                Console.println("usage: write <path> <text...>")
                return@register
            }

            if (!Files.writable(path)) {
                Console.println("$path is not writable (${Files.status()})")
                return@register
            }

            val text = args.drop(1).joinToString(" ")
            val bytes = ByteArray(text.length) { text[it].code.toByte() }

            if (Files.write(path, bytes)) {
                Console.println("wrote ${bytes.size} bytes to $path")
            } else {
                Console.println("write failed: $path")
            }
        }

        registry.register("mkdir", "create a directory on the writable volume") { args ->
            val path = args.getOrNull(0)
            if (path == null) {
                Console.println("usage: mkdir <path>")
                return@register
            }

            if (Files.mkdir(path)) {
                Console.println("created $path")
            } else {
                Console.println("mkdir failed: $path")
            }
        }

        registry.register("games", "list installed games") {
            val games = GameLibrary.scan()
            if (games.isEmpty()) {
                Console.println("no games in ${GameLibrary.DIRECTORY}")
                return@register
            }
            games.forEach {
                Console.println("${it.name.padEnd(24)} ${it.emulator.id.padEnd(8)} ${it.size / 1024UL} KiB")
            }
        }

        registry.register("play", "play a game from the library") { args ->
            val name = args.joinToString(" ")
            if (name.isEmpty()) {
                Console.println("usage: play <game>")
                return@register
            }

            val game = GameLibrary.find(name)
            if (game == null) {
                Console.println("no such game: $name")
                return@register
            }

            val surface = Graphics.surface()
            if (surface == null) {
                Console.println("graphics: ${Graphics.status()}")
                return@register
            }

            OSD.hideForPrint()
            val status = Player.play(surface, game)
            Console.clear()
            if (status != null) Console.println(status)
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

        registry.register("gpudemo", "GPU compute shader draws a gradient to the screen; any key restores") {
            val result = GpuService.drawScanoutDemo()
            if (!result.startsWith("gpu drew")) {
                Console.println(result)
                return@register
            }
            val deadline = Time.uptimeMillis() + 4000UL
            while (Time.uptimeMillis() < deadline) {
            }
            GpuService.endScanoutDemo()
            Console.clear()
            Console.println(result)
        }

        registry.register("gpudiag", "run interactive compute + read gfx power state (root-cause the shell-compute blocker)") {
            GpuService.diagnoseCompute().forEach { Console.println(it) }
        }

        registry.register("gpu", "show gpu bring-up log; 'gpu regs' live sdma/mmhub; 'gpu gfx' probes gfx (may hang)") { args ->
            if (args.firstOrNull() == "regs") {
                val regs = GpuService.regs
                if (regs == null) {
                    Console.println("no register access (gpu absent or bring-up failed)")
                    return@register
                }
                Console.println("sdma:  0x${regs.read(VegaReg.SDMA0_STATUS_REG).toString(16)}")
                Console.println("rbcntl:0x${regs.read(VegaReg.SDMA0_GFX_RB_CNTL).toString(16)}")
                Console.println("rptr:  0x${regs.read(VegaReg.SDMA0_GFX_RB_RPTR).toString(16)}")
                Console.println("wptr:  0x${regs.read(VegaReg.SDMA0_GFX_RB_WPTR).toString(16)}")
                Console.println("fbbase:0x${regs.read(VegaReg.MC_VM_FB_LOCATION_BASE).toString(16)}")
                return@register
            }

            if (args.firstOrNull() == "gfx") {
                Console.println("probing gfx registers; if gfxoff is engaged this hangs the box")
                Console.println(GpuService.probeGfx())
                return@register
            }

            if (GpuLog.history.isEmpty()) {
                Console.println("gpu: no log (service did not run)")
                return@register
            }

            for (entry in GpuLog.history) {
                if (entry.ok == null) {
                    Console.println("       ${entry.detail}")
                    continue
                }
                val badge = if (entry.ok) " OK " else "FAIL"
                Console.println("[$badge] ${entry.name.padEnd(10)} ${entry.detail}")
            }
        }

        registry.register("pci", "list pci devices") {
            Pci.all().forEach { Console.println(it.describe()) }
        }

        registry.register("audio", "show the audio device") {
            Console.println("device: ${Audio.status()}")
            Console.println("volume: ${volumeText()}  (F5 mute, F6 quieter, F7 louder)")
        }

        registry.register("mute", "toggle audio mute") {
            Audio.toggleMuted()
            Console.println("volume: ${volumeText()}")
        }

        registry.register("usb", "list usb devices; 'usb <n>' details; 'usb rescan'") { args ->
            USBService.initialize()

            val argument = args.getOrNull(0)

            if (argument == "rescan") {
                Console.println("rescanning...")
                GamepadService.rescan()
            }

            val index = argument?.toIntOrNull()
            if (index != null) {
                UsbLock.withLock { USBService.detail(index) }.forEach { Console.println(it) }
                return@register
            }

            UsbLock.withLock { USBService.describe() }.forEach { Console.println(it) }
            Console.println("irq: ${USBService.interruptStatus()}")
            Console.println("gamepad: ${GamepadService.summary()}")
        }

        registry.register("gamepad", "show live gamepad button state") {
            GamepadService.initialize()
            GamepadService.refresh()
            Console.println(GamepadService.summary())

            if (!GamepadService.available) return@register

            for (player in 0 until GamepadService.count) {
                Console.println("p${player + 1}: ${GamepadService.descriptor(player)}")
            }
            Console.println("press buttons; esc to stop")
            Input.drain()

            val previous = Array(GamepadService.count) { BooleanArray(Pad.COUNT) }

            while (true) {
                Input.poll()
                if (Input.consumePress(Keys.ESC)) break

                GamepadService.poll()

                for (player in previous.indices) {
                    var changed = false
                    for (button in 0 until Pad.COUNT) {
                        val down = GamepadService.isDown(player, button)
                        if (down != previous[player][button]) changed = true
                        previous[player][button] = down
                    }

                    if (changed) Console.println("  p${player + 1} -> " + padState(previous[player]))
                }
                Time.idle()
            }
        }

        registry.register("usbpoll", "poll a usb device's interrupt endpoint") { args ->
            USBService.initialize()

            val index = args.getOrNull(0)?.toIntOrNull() ?: 0
            val millis = args.getOrNull(1)?.toIntOrNull() ?: 3000

            val buffer = ByteArray(32)
            val deadline = Time.uptimeMillis() + millis.toULong()
            var reports = 0

            Console.println("polling device $index for $millis ms")

            var failures = 0
            var lastCode = 0

            while (Time.uptimeMillis() < deadline) {
                val length = UsbLock.withLock { USBService.report(index, buffer) }

                if (length <= 0) {
                    failures++
                    lastCode = USBService.lastCompletion(index)
                    continue
                }

                Console.println("  " + bytes(buffer, length))
                reports++
                if (reports >= 12) break
            }

            Console.println("$reports reports, $failures empty (last completion code $lastCode)")
        }

        registry.register("usbdesc", "dump a usb device's raw configuration descriptor") { args ->
            USBService.initialize()

            val index = args.getOrNull(0)?.toIntOrNull() ?: 0
            val descriptor = USBService.configurationOf(index)

            if (descriptor.isEmpty()) {
                Console.println("no configuration descriptor for device $index")
                return@register
            }

            Console.println("device $index: ${descriptor.size} bytes")
            dump(descriptor)
        }

        registry.register("hdainfo", "dump the audio codec widget graph") {
            AudioService.describe().forEach { Console.println(it) }
        }

        registry.register("volume", "show or set the output volume") { args ->
            val requested = args.getOrNull(0)
            if (requested != null) {
                val percent = requested.toIntOrNull()
                if (percent == null) {
                    Console.println("usage: volume <0-100>")
                    return@register
                }
                Audio.setVolume(percent)
            }
            Console.println("volume: ${volumeText()}")
        }

        registry.register("beep", "play a test tone") { args ->
            if (!Audio.available()) {
                Console.println("audio: ${Audio.status()}")
                return@register
            }
            val hertz = args.getOrNull(0)?.toIntOrNull() ?: 440
            val millis = args.getOrNull(1)?.toIntOrNull() ?: 500
            Console.println("playing ${hertz} Hz for ${millis} ms at ${Audio.volume()}%")
            AudioService.tone(hertz, millis)
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

    private fun padState(buttons: BooleanArray): String {
        val builder = StringBuilder()

        for (button in 0 until Pad.COUNT) {
            if (!buttons[button]) continue
            if (builder.isNotEmpty()) builder.append(' ')
            builder.append(PAD_NAMES[button])
        }

        return if (builder.isEmpty()) "(none)" else builder.toString()
    }

    private val PAD_NAMES = arrayOf(
        "up", "down", "left", "right", "A", "B", "X", "Y",
        "start", "select", "L", "R", "guide", "LT", "RT",
        "dpad-up", "dpad-down", "dpad-left", "dpad-right",
    )

    private fun dump(data: ByteArray) {
        var offset = 0
        while (offset < data.size) {
            val end = if (offset + 16 < data.size) offset + 16 else data.size
            val chunk = data.copyOfRange(offset, end)
            Console.println("  " + bytes(chunk, chunk.size))
            offset = end
        }
    }

    private fun bytes(data: ByteArray, length: Int): String {
        val builder = StringBuilder()
        for (i in 0 until length) {
            builder.append((data[i].toInt() and 0xFF).toString(16).padStart(2, '0'))
            builder.append(' ')
        }
        return builder.toString()
    }

    private fun volumeText(): String =
        if (Audio.muted()) "${Audio.volume()}% (muted)" else "${Audio.volume()}%"

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
