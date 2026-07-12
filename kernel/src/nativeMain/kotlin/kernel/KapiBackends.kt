package kernel

import kapi.AudioBackend
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
import kernel.fs.FlxEntryKind
import kernel.fs.FlxService
import kernel.graphics.GraphicsService

object KernelGraphics : GraphicsBackend {
    override fun surface(): Surface? {
        if (!GraphicsService.initialize()) return null
        return GraphicsService.framebuffer()
    }

    override fun status(): String = GraphicsService.status()
}

object KernelInput : InputBackend {
    override fun poll() = Keyboard.poll()

    override fun isKeyDown(code: UShort): Boolean = Keyboard.isKeyDown(code)

    override fun nextEvent(): KeyEvent? = Keyboard.nextEvent()

    override fun consumePress(code: UShort): Boolean = Keyboard.consumePress(code)

    override fun characterFor(code: UShort): Char? = Keyboard.characterFor(code)

    override fun status(): String =
        if (kernel.drivers.I8042.present) "ps/2 keyboard" else "no keyboard"
}

object KernelTime : TimeBackend {
    override fun uptimeMillis(): ULong = Clock.uptimeMillis()

    override fun idle() = Cpu.waitForInterrupt()
}

object KernelFiles : FilesBackend {
    override fun list(path: String): List<FileEntry>? {
        if (!FlxService.initialize()) return null
        return FlxService.list(path)?.map {
            FileEntry(
                it.name,
                if (it.kind == FlxEntryKind.Directory) FileKind.Directory else FileKind.File,
                it.size,
            )
        }
    }

    override fun read(path: String, maxBytes: UInt): ByteArray? {
        if (!FlxService.initialize()) return null
        return FlxService.open(path)?.readAll(maxBytes)
    }

    override fun status(): String {
        FlxService.initialize()
        return FlxService.status()
    }
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
