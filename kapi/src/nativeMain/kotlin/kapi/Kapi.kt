package kapi

interface ConsoleBackend {
    fun print(text: String)
    fun println(text: String)
    fun readLine(): String
    fun tryReadChar(): Char?
    fun clear()
}

interface Surface {
    val width: UInt
    val height: UInt

    fun clear(color: UInt)
    fun fillRect(x: UInt, y: UInt, width: UInt, height: UInt, color: UInt)
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
    fun nextEvent(): KeyEvent?
    fun status(): String
}

interface TimeBackend {
    fun uptimeMillis(): ULong
    fun idle()
}

enum class FileKind { File, Directory }

data class FileEntry(val name: String, val kind: FileKind, val size: ULong)

interface FilesBackend {
    fun list(path: String): List<FileEntry>?
    fun read(path: String, maxBytes: UInt): ByteArray?
    fun status(): String
}

interface SystemBackend {
    fun halt(): Nothing
    fun memoryReport(): String
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

    fun poll() = backend?.poll() ?: Unit
    fun isKeyDown(code: UShort): Boolean = backend?.isKeyDown(code) ?: false
    fun nextEvent(): KeyEvent? = backend?.nextEvent()
    fun status(): String = backend?.status() ?: "unavailable"

    fun drain() {
        while (nextEvent() != null) {
        }
    }
}

object Time {
    internal var backend: TimeBackend? = null

    fun uptimeMillis(): ULong = backend?.uptimeMillis() ?: 0UL

    fun idle() = backend?.idle() ?: Unit
}

object Files {
    internal var backend: FilesBackend? = null

    fun list(path: String): List<FileEntry>? = backend?.list(path)
    fun read(path: String, maxBytes: UInt = 4096u): ByteArray? = backend?.read(path, maxBytes)
    fun status(): String = backend?.status() ?: "unavailable"
}

object Sys {
    internal var backend: SystemBackend? = null

    fun halt(): Nothing = backend?.halt() ?: error("no system backend")
    fun memoryReport(): String = backend?.memoryReport() ?: "unavailable"
}

object KapiRuntime {
    fun install(
        console: ConsoleBackend,
        graphics: GraphicsBackend,
        input: InputBackend,
        time: TimeBackend,
        files: FilesBackend,
        system: SystemBackend,
    ) {
        Console.backend = console
        Graphics.backend = graphics
        Input.backend = input
        Time.backend = time
        Files.backend = files
        Sys.backend = system
    }
}
