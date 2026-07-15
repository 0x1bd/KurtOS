package kapi

interface ConsoleBackend {
    fun print(text: String)
    fun println(text: String)
    fun readLine(): String
    fun tryReadChar(): Char?
    fun clear()
}

interface IndexedBitmap {
    val width: UInt
    val height: UInt
    val pixels: ByteArray

    fun setPalette(index: Int, color: UInt)
    fun draw(x: UInt, y: UInt, scale: UInt)
}

interface HighColorBitmap {
    val width: UInt
    val height: UInt
    val pixels: ShortArray

    fun draw(x: UInt, y: UInt, scale: UInt)
}

interface Surface {
    val width: UInt
    val height: UInt

    fun clear(color: UInt)
    fun fillRect(x: UInt, y: UInt, width: UInt, height: UInt, color: UInt)
    fun createBitmap(width: UInt, height: UInt, paletteSize: Int): IndexedBitmap?
    fun createHighColorBitmap(width: UInt, height: UInt): HighColorBitmap?
    fun present()
    fun presentAll()
}

interface GraphicsBackend {
    fun surface(): Surface?
    fun status(): String
}

data class KeyEvent(val code: UShort, val pressed: Boolean)

interface InputBackend {
    fun poll()
    fun isKeyDown(code: UShort): Boolean
    fun consumePress(code: UShort): Boolean
    fun nextEvent(): KeyEvent?
    fun characterFor(code: UShort): Char?
    fun drain()
    fun status(): String
}

data class DateTime(
    val year: Int,
    val month: Int,
    val day: Int,
    val hour: Int,
    val minute: Int,
    val second: Int,
)

interface TimeBackend {
    fun uptimeMillis(): ULong
    fun idle()
    fun cycles(): ULong = 0UL
    fun now(): DateTime? = null
    fun epochSeconds(): Long? = null
    fun zoneOffsetMinutes(): Int = 0
    fun daylightSaving(): Boolean = false
    fun setZone(offsetMinutes: Int, daylightSaving: Boolean) {}
}

enum class FileKind { File, Directory }

data class FileEntry(val name: String, val kind: FileKind, val size: ULong)

interface FilesBackend {
    fun list(path: String): List<FileEntry>?
    fun read(path: String, maxBytes: UInt): ByteArray?
    fun write(path: String, data: ByteArray): Boolean
    fun mkdir(path: String): Boolean
    fun writable(path: String): Boolean
    fun status(): String
}

interface SystemBackend {
    fun halt(): Nothing
    fun memoryReport(): String
    fun collectGarbage()
    fun toast(title: String, subtitle: String?) {}
    fun cpuMhz(): Int = 0
    fun tscMhz(): Int = 0
}

enum class GamepadEvent { Connected, Disconnected }

interface GamepadBackend {
    fun available(): Boolean
    fun status(): String
    fun refresh()
    fun pump(): GamepadEvent?
    fun poll()
    fun isDown(button: Int): Boolean
}

interface AudioBackend {
    fun status(): String
    fun available(): Boolean
    fun sampleRate(): UInt
    fun channels(): Int
    fun volume(): Int
    fun setVolume(percent: Int)
    fun muted(): Boolean
    fun toggleMuted()
    fun showVolume() {}
    fun click() {}
    fun open(): Boolean
    fun close()
    fun availableFrames(): Int
    fun write(samples: ShortArray, frames: Int): Int
}

object Console {
    internal var backend: ConsoleBackend? = null

    fun print(text: String) = backend?.print(text) ?: Unit
    fun println(text: String) = backend?.println(text) ?: Unit
    fun println() = backend?.println("") ?: Unit
    fun readLine(): String = backend?.readLine() ?: ""
    fun tryReadChar(): Char? = backend?.tryReadChar()
    fun clear() = backend?.clear() ?: Unit
}

object Graphics {
    internal var backend: GraphicsBackend? = null

    fun surface(): Surface? = backend?.surface()
    fun status(): String = backend?.status() ?: "unavailable"
}

object Input {
    internal var backend: InputBackend? = null

    fun poll() {
        backend?.poll()
        Gamepad.pump()
    }
    fun isKeyDown(code: UShort): Boolean = backend?.isKeyDown(code) ?: false
    fun consumePress(code: UShort): Boolean = backend?.consumePress(code) ?: false
    fun nextEvent(): KeyEvent? = backend?.nextEvent()
    fun characterFor(code: UShort): Char? = backend?.characterFor(code)
    fun status(): String = backend?.status() ?: "unavailable"

    fun drain() {
        backend?.drain()
        while (nextEvent() != null) {
        }
    }
}

object Time {
    internal var backend: TimeBackend? = null

    fun uptimeMillis(): ULong = backend?.uptimeMillis() ?: 0UL

    fun now(): DateTime? = backend?.now()

    fun epochSeconds(): Long? = backend?.epochSeconds()

    fun zoneOffsetMinutes(): Int = backend?.zoneOffsetMinutes() ?: 0

    fun daylightSaving(): Boolean = backend?.daylightSaving() ?: false

    fun setZone(offsetMinutes: Int, daylightSaving: Boolean) = backend?.setZone(offsetMinutes, daylightSaving) ?: Unit

    fun idle() {
        backend?.idle()
        Gamepad.pump()
    }

    fun cycles(): ULong = backend?.cycles() ?: 0UL
}

object Files {
    internal var backend: FilesBackend? = null

    fun list(path: String): List<FileEntry>? = backend?.list(path)
    fun read(path: String, maxBytes: UInt = 4096u): ByteArray? = backend?.read(path, maxBytes)
    fun write(path: String, data: ByteArray): Boolean = backend?.write(path, data) ?: false
    fun mkdir(path: String): Boolean = backend?.mkdir(path) ?: false
    fun writable(path: String): Boolean = backend?.writable(path) ?: false
    fun status(): String = backend?.status() ?: "unavailable"
}

object Sys {
    internal var backend: SystemBackend? = null

    fun halt(): Nothing = backend?.halt() ?: error("no system backend")
    fun memoryReport(): String = backend?.memoryReport() ?: "unavailable"
    fun collectGarbage() = backend?.collectGarbage() ?: Unit
    fun toast(title: String, subtitle: String? = null) = backend?.toast(title, subtitle) ?: Unit
    fun cpuMhz(): Int = backend?.cpuMhz() ?: 0
    fun tscMhz(): Int = backend?.tscMhz() ?: 0
}

object Gamepad {
    internal var backend: GamepadBackend? = null

    private val connectListeners = mutableListOf<() -> Unit>()
    private val disconnectListeners = mutableListOf<() -> Unit>()

    fun available(): Boolean = backend?.available() ?: false
    fun status(): String = backend?.status() ?: "unavailable"
    fun refresh() = backend?.refresh() ?: Unit
    fun isDown(button: Int): Boolean = backend?.isDown(button) ?: false

    fun poll() {
        pump()
        backend?.poll()
    }

    fun onConnect(listener: () -> Unit) {
        connectListeners.add(listener)
    }

    fun onDisconnect(listener: () -> Unit) {
        disconnectListeners.add(listener)
    }

    fun pump() {
        when (backend?.pump()) {
            GamepadEvent.Connected -> connectListeners.forEach { it() }
            GamepadEvent.Disconnected -> disconnectListeners.forEach { it() }
            null -> Unit
        }
    }
}

object Audio {
    internal var backend: AudioBackend? = null

    fun status(): String = backend?.status() ?: "unavailable"
    fun available(): Boolean = backend?.available() ?: false
    fun sampleRate(): UInt = backend?.sampleRate() ?: 0u
    fun channels(): Int = backend?.channels() ?: 0
    fun volume(): Int = backend?.volume() ?: 0
    fun setVolume(percent: Int) = backend?.setVolume(percent) ?: Unit
    fun muted(): Boolean = backend?.muted() ?: false
    fun toggleMuted() = backend?.toggleMuted() ?: Unit
    fun showVolume() = backend?.showVolume() ?: Unit
    fun click() = backend?.click() ?: Unit
    fun open(): Boolean = backend?.open() ?: false
    fun close() = backend?.close() ?: Unit
    fun availableFrames(): Int = backend?.availableFrames() ?: 0
    fun write(samples: ShortArray, frames: Int): Int = backend?.write(samples, frames) ?: 0
}

object KapiRuntime {
    fun install(
        console: ConsoleBackend,
        graphics: GraphicsBackend,
        input: InputBackend,
        time: TimeBackend,
        files: FilesBackend,
        system: SystemBackend,
        audio: AudioBackend,
        gamepad: GamepadBackend,
    ) {
        Console.backend = console
        Graphics.backend = graphics
        Input.backend = input
        Time.backend = time
        Files.backend = files
        Sys.backend = system
        Audio.backend = audio
        Gamepad.backend = gamepad
    }
}
