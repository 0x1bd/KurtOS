package kernel

import kapi.AudioBackend
import kapi.GamepadBackend
import kapi.GamepadEvent
import kernel.drivers.usb.GamepadService
import kernel.audio.AudioService
import hal.BootInfo
import hal.Clock
import hal.Cpu
import kapi.DateTime
import kapi.FileEntry
import kapi.FileKind
import kapi.FilesBackend
import kapi.GraphicsBackend
import kapi.InputBackend
import kapi.KeyEvent
import kapi.Surface
import kapi.SystemBackend
import kapi.TimeBackend
import kernel.drivers.Keyboard
import kernel.fs.StorageService
import kernel.graphics.GraphicsService
import kernel.graphics.OffsetSurface
import kernel.ui.UI

object KernelGraphics : GraphicsBackend {
    private var surface: Surface? = null

    override fun surface(): Surface? {
        if (!GraphicsService.initialize()) return null

        val existing = surface
        if (existing != null) return existing

        val fb = GraphicsService.framebuffer() ?: return null
        return OffsetSurface(fb, 0u).also { surface = it }
    }

    override fun status(): String = GraphicsService.status()
}

object KernelInput : InputBackend {
    override fun poll() {
        Keyboard.poll()
        UI.tick()
    }

    override fun isKeyDown(code: UShort): Boolean = Keyboard.isKeyDown(code)

    override fun nextEvent(): KeyEvent? = Keyboard.nextEvent()

    override fun consumePress(code: UShort): Boolean = Keyboard.consumePress(code)

    override fun consumeChord(code: UShort): Boolean = Keyboard.consumeChord(code)

    override fun characterFor(code: UShort): Char? = Keyboard.characterFor(code)

    override fun drain() = Keyboard.drain()

    override fun status(): String =
        if (kernel.drivers.I8042.present) "ps/2 keyboard" else "no keyboard"

    override fun diagnostics(): String =
        "K${hal.Arch.keyboardInterrupts()} R${Keyboard.fromRing} P${Keyboard.fromPoll} X${hal.Arch.droppedScancodes()}"
}

object KernelTime : TimeBackend {
    override fun uptimeMillis(): ULong = Clock.uptimeMillis()

    override fun now(): DateTime? = kernel.drivers.RTC.now()

    override fun epochSeconds(): Long? = kernel.drivers.RTC.epochSeconds()

    override fun zoneOffsetMinutes(): Int = kernel.drivers.RTC.standardOffsetMinutes

    override fun daylightSaving(): Boolean = kernel.drivers.RTC.daylightSaving

    override fun setZone(offsetMinutes: Int, daylightSaving: Boolean) {
        kernel.drivers.RTC.standardOffsetMinutes = offsetMinutes
        kernel.drivers.RTC.daylightSaving = daylightSaving
    }

    override fun idle() {
        Cpu.waitForInterrupt()
        UI.tick()
    }

    override fun cycles(): ULong = Cpu.timestamp()
}

object KernelFiles : FilesBackend {
    private const val SYSTEM_PREFIX = "/sys"

    private fun systemPath(path: String): String? {
        if (path == SYSTEM_PREFIX) return "/"
        if (path.startsWith("$SYSTEM_PREFIX/")) return path.substring(SYSTEM_PREFIX.length)
        return null
    }

    override fun list(path: String): List<FileEntry>? {
        val system = systemPath(path)

        val listed = if (system != null) {
            StorageService.system()?.list(system)
        } else {
            StorageService.volume()?.list(path)
        } ?: return null

        val entries = listed.map {
            FileEntry(
                it.name,
                if (it.directory) FileKind.Directory else FileKind.File,
                it.size,
            )
        }

        if (system == null && path == "/" && StorageService.system() != null) {
            return entries + FileEntry("sys", FileKind.Directory, 0UL)
        }

        return entries
    }

    override fun read(path: String, maxBytes: UInt): ByteArray? {
        val system = systemPath(path)
        if (system != null) return StorageService.system()?.read(system, maxBytes)

        return StorageService.volume()?.read(path, maxBytes)
    }

    override fun write(path: String, data: ByteArray): Boolean {
        if (systemPath(path) != null) return false

        val volume = StorageService.volume() ?: return false
        return volume.write(path, data)
    }

    override fun mkdir(path: String): Boolean {
        if (systemPath(path) != null) return false

        val volume = StorageService.volume() ?: return false
        return volume.mkdir(path)
    }

    override fun writable(path: String): Boolean {
        if (systemPath(path) != null) return false

        StorageService.initialize()
        return StorageService.ready
    }

    override fun status(): String {
        StorageService.initialize()
        return StorageService.status
    }
}

object KernelGamepad : GamepadBackend {
    override fun available(): Boolean = GamepadService.available

    override fun count(): Int = GamepadService.count

    override fun status(): String = GamepadService.status

    override fun refresh() {
        GamepadService.refresh()
    }

    override fun pump(): GamepadEvent? = GamepadService.pump()

    override fun poll() = GamepadService.poll()

    override fun isDown(player: Int, button: Int): Boolean = GamepadService.isDown(player, button)

    override fun axis(player: Int, axis: Int): Int = GamepadService.axis(player, axis)

    override fun connected(player: Int): Boolean = GamepadService.connected(player)
}

object KernelAudio : AudioBackend {
    override fun status(): String = AudioService.status

    override fun available(): Boolean = AudioService.available

    override fun sampleRate(): UInt = AudioService.sampleRate.toUInt()

    override fun channels(): Int = AudioService.channels

    override fun volume(): Int = AudioService.volume()

    override fun setVolume(percent: Int) = AudioService.setVolume(percent)

    override fun muted(): Boolean = AudioService.muted()

    override fun toggleMuted() = AudioService.toggleMuted()

    override fun showVolume() {
        kernel.ui.SystemSounds.play(kernel.ui.SystemSounds.Clip.Blip)
        kernel.ui.OSD.showVolume()
    }

    override fun click() = kernel.ui.SystemSounds.play(kernel.ui.SystemSounds.Clip.Blip)

    override fun sound(id: Int) = kernel.ui.SystemSounds.playFred(id)

    override fun open(): Boolean = AudioService.open()

    override fun close() = AudioService.close()

    override fun availableFrames(): Int = AudioService.availableFrames()

    override fun write(samples: ShortArray, frames: Int): Int = AudioService.write(samples, frames)
}

object KernelSystem : SystemBackend {
    override fun halt(): Nothing {
        Cpu.hang()
    }

    override fun reboot(): Nothing = kernel.arch.Power.reboot()

    override fun shutdown(): Nothing = kernel.arch.Power.shutdown()

    override fun toast(title: String, subtitle: String?) {
        kernel.ui.OSD.notify(
            kapi.ui.PixelIcons.QUESTION_BLOCK,
            title,
            subtitle,
            kernel.ui.SystemSounds.Clip.Blip,
        )
    }

    @OptIn(kotlin.native.runtime.NativeRuntimeApi::class)
    override fun collectGarbage() {
        kotlin.native.runtime.GC.collect()
    }

    override fun memoryReport(): String {
        val usedMib = BootInfo.heapUsed / (1024UL * 1024UL)
        val totalMib = BootInfo.heapTotal / (1024UL * 1024UL)
        val freePages = kernel.memory.PageAllocator.freePages
        val totalPages = kernel.memory.PageAllocator.totalPages
        return "heap ${usedMib}/${totalMib} MiB, pages ${totalPages - freePages}/${totalPages} used"
    }

    override fun cpuMhz(): Int = kernel.arch.PerfMonitor.actualMhz()

    override fun tscMhz(): Int = kernel.arch.PerfMonitor.nominalMhz
}
