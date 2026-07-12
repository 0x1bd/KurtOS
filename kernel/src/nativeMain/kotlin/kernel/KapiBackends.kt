package kernel

import kapi.AudioBackend
import kapi.GamepadBackend
import kapi.GamepadEvent
import kernel.drivers.usb.GamepadService
import kernel.audio.AudioService
import hal.BootInfo
import hal.Clock
import hal.Cpu
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
import kernel.ui.HUD
import kernel.ui.UI

object KernelGraphics : GraphicsBackend {
    private var surface: Surface? = null

    override fun surface(): Surface? {
        if (!GraphicsService.initialize()) return null

        val existing = surface
        if (existing != null) return existing

        val fb = GraphicsService.framebuffer() ?: return null
        return OffsetSurface(fb, HUD.RESERVED).also { surface = it }
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

    override fun characterFor(code: UShort): Char? = Keyboard.characterFor(code)

    override fun drain() = Keyboard.drain()

    override fun status(): String =
        if (kernel.drivers.I8042.present) "ps/2 keyboard" else "no keyboard"
}

object KernelTime : TimeBackend {
    override fun uptimeMillis(): ULong = Clock.uptimeMillis()

    override fun idle() {
        Cpu.waitForInterrupt()
        UI.tick()
    }
}

object KernelFiles : FilesBackend {
    override fun list(path: String): List<FileEntry>? {
        val volume = StorageService.volume() ?: return null

        return volume.list(path)?.map {
            FileEntry(
                it.name,
                if (it.directory) FileKind.Directory else FileKind.File,
                it.size,
            )
        }
    }

    override fun read(path: String, maxBytes: UInt): ByteArray? {
        val volume = StorageService.volume() ?: return null
        return volume.read(path, maxBytes)
    }

    override fun write(path: String, data: ByteArray): Boolean {
        val volume = StorageService.volume() ?: return false
        return volume.write(path, data)
    }

    override fun mkdir(path: String): Boolean {
        val volume = StorageService.volume() ?: return false
        return volume.mkdir(path)
    }

    override fun writable(path: String): Boolean {
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

    override fun status(): String = GamepadService.status

    override fun refresh() {
        GamepadService.refresh()
    }

    override fun pump(): GamepadEvent? = GamepadService.pump()

    override fun poll() = GamepadService.poll()

    override fun isDown(button: Int): Boolean = GamepadService.isDown(button)
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

    override fun open(): Boolean = AudioService.open()

    override fun close() = AudioService.close()

    override fun availableFrames(): Int = AudioService.availableFrames()

    override fun write(samples: ShortArray, frames: Int): Int = AudioService.write(samples, frames)
}

object KernelSystem : SystemBackend {
    override fun halt(): Nothing {
        Cpu.hang()
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
}
