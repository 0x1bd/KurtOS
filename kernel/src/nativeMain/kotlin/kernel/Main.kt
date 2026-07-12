package kernel

import frontend.CoreCommands
import frontend.Home
import hal.Cpu
import hal.Serial
import kapi.Console
import kapi.KapiRuntime
import kernel.arch.Acpi
import kernel.arch.Apic
import kernel.arch.Gdt
import kernel.arch.Idt
import kernel.arch.IoApic
import kernel.arch.Pic
import kapi.Gamepad
import kernel.audio.AudioService
import kernel.drivers.usb.GamepadService
import kernel.drivers.usb.USBService
import kernel.console.SystemConsole
import kernel.drivers.I8042
import kernel.graphics.GraphicsService
import kernel.memory.PageAllocator
import kernel.shellext.KernelShell
import kapi.ui.PixelIcons
import kernel.ui.HUD
import kernel.ui.OSD
import kernel.ui.SystemSounds
import shell.CommandRegistry
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.CName

private const val IRQ_KEYBOARD = 1

@OptIn(ExperimentalNativeApi::class)
@CName("kotlin_main")
fun main() {
    Serial.initialize()

    Gdt.install()
    Idt.install()

    PageAllocator.initialize()

    if (GraphicsService.initialize()) {
        SystemConsole.attachFramebuffer()
    }

    KapiRuntime.install(
        console = SystemConsole,
        graphics = KernelGraphics,
        input = KernelInput,
        time = KernelTime,
        files = KernelFiles,
        system = KernelSystem,
        audio = KernelAudio,
        gamepad = KernelGamepad,
    )

    startInterrupts()

    AudioService.initialize()
    GamepadService.initialize()
    USBService.armInterrupts(Idt.VECTOR_USB, Apic.localId())

    Gamepad.onConnect {
        val label = GamepadService.status.substringBefore(" (")
        OSD.notify(PixelIcons.GAMEPAD, "GAMEPAD CONNECTED", label, SystemSounds.Clip.Coin)
    }
    Gamepad.onDisconnect {
        OSD.notify(PixelIcons.GAMEPAD, "GAMEPAD DISCONNECTED", null, SystemSounds.Clip.Pipe)
    }

    Console.println("KurtOS")
    Console.println(KernelSystem.memoryReport())
    Console.println("graphics: ${GraphicsService.status()}")
    Console.println("keyboard: ${KernelInput.status()}")
    Console.println("storage:  ${KernelFiles.status()}")
    Console.println("audio:    ${AudioService.status}")
    Console.println("gamepad:  ${GamepadService.status}")
    Console.println("")

    val registry = CommandRegistry()
    CoreCommands.install(registry)
    KernelShell.install(registry)

    HUD.draw()
    OSD.notify(PixelIcons.MUSHROOM, "WELCOME TO KURTOS", "LET'S-A GO!", SystemSounds.Clip.Fanfare)

    Home.run(registry)
}

private fun startInterrupts() {
    Pic.disable()

    Acpi.initialize()
    Apic.initialize()

    if (I8042.initialize()) {
        IoApic.route(IRQ_KEYBOARD, Idt.VECTOR_KEYBOARD, Apic.localId())
    }

    Cpu.enableInterrupts()
}
