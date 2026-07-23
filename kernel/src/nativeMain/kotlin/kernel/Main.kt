package kernel

import frontend.CoreCommands
import frontend.Home
import frontend.Settings
import hal.Cpu
import hal.Serial
import kapi.KapiRuntime
import kernel.arch.Acpi
import kernel.arch.Apic
import kernel.arch.PerfMonitor
import kernel.arch.Gdt
import kernel.arch.Idt
import kernel.arch.IoApic
import kernel.arch.Pic
import kernel.arch.Smp
import kapi.Gamepad
import kernel.audio.AudioService
import kernel.drivers.usb.GamepadService
import kernel.drivers.usb.USBService
import kernel.console.BootScreen
import kernel.console.SystemConsole
import kernel.drivers.I8042
import kernel.drivers.gpu.vega.GpuService
import kernel.fs.StorageService
import kernel.graphics.GraphicsService
import kernel.memory.PageAllocator
import kernel.shellext.KernelShell
import kapi.ui.PixelIcons
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
    KLog.info("input", GamepadService.summary())
    StorageService.initialize()
    GpuService.initialize()
    USBService.armInterrupts(Idt.VECTOR_USB, Apic.localId())
    InputService.start()

    Gamepad.onConnect {
        val label = GamepadService.status.substringBefore(" (")
        OSD.notify(PixelIcons.GAMEPAD, "GAMEPAD CONNECTED", label, SystemSounds.Clip.Coin)
    }
    Gamepad.onDisconnect {
        OSD.notify(PixelIcons.GAMEPAD, "GAMEPAD DISCONNECTED", null, SystemSounds.Clip.Pipe)
    }

    val registry = CommandRegistry()
    CoreCommands.install(registry)
    KernelShell.install(registry)

    Settings.load()
    if (Settings.bootDiagnostics) BootScreen.show()

    OSD.notify(PixelIcons.FRED, "WELCOME TO KURTOS", "FRED SAYS HI", SystemSounds.Clip.Fanfare)

    Home.run(registry)
}

private fun startInterrupts() {
    Pic.disable()

    Acpi.initialize()
    KLog.step("acpi", "madt", Acpi.available, if (Acpi.available) "ioapic at ${KLog.hex(Acpi.ioApicAddress)}" else "unavailable")

    Cpu.requestMaxPerformance()

    Apic.initialize()
    PerfMonitor.initialize()
    KLog.info("apic", "timer ${Apic.timerHz} Hz via ${Apic.calibrationSource}")

    Smp.initialize()
    KLog.info("smp", "${Smp.cpus} cpu(s)")

    val ps2 = I8042.initialize()
    KLog.step("input", "ps/2 keyboard", ps2, if (ps2) "" else "controller absent")
    if (ps2) {
        IoApic.route(IRQ_KEYBOARD, Idt.VECTOR_KEYBOARD, Apic.localId())
        hal.Arch.enableKeyboardPoll()
    }

    Cpu.enableInterrupts()
}
