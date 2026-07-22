package kurtos.testkit

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.pointed
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.SEEK_END
import platform.posix.closedir
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.fwrite
import platform.posix.mkdir
import platform.posix.opendir
import platform.posix.readdir

@OptIn(ExperimentalForeignApi::class)
fun readFile(path: String): ByteArray? {
    val handle = fopen(path, "rb") ?: return null
    try {
        fseek(handle, 0, SEEK_END)
        val size = ftell(handle).toInt()
        if (size <= 0) return null
        fseek(handle, 0, 0)
        val data = ByteArray(size)
        data.usePinned { fread(it.addressOf(0), 1u, size.toULong(), handle) }
        return data
    } finally {
        fclose(handle)
    }
}

@OptIn(ExperimentalForeignApi::class)
fun ensureDirectory(path: String) {
    var at = path.indexOf('/', 1)
    while (at > 0) {
        mkdir(path.substring(0, at), 0x1FFu)
        at = path.indexOf('/', at + 1)
    }
    mkdir(path, 0x1FFu)
}

@OptIn(ExperimentalForeignApi::class)
fun writeFile(path: String, text: String) {
    ensureDirectory(path.substringBeforeLast('/'))
    val handle = fopen(path, "wb") ?: return
    val bytes = text.encodeToByteArray()
    bytes.usePinned { fwrite(it.addressOf(0), 1u, bytes.size.toULong(), handle) }
    fclose(handle)
}

@OptIn(ExperimentalForeignApi::class)
fun findFile(root: String, name: String, depth: Int = 8): String? {
    readFile("$root/$name")?.let { return "$root/$name" }
    if (depth <= 0) return null

    val handle = opendir(root) ?: return null
    try {
        while (true) {
            val entry = readdir(handle) ?: return null
            val child = entry.pointed.d_name.toKString()
            if (child.startsWith(".")) continue
            findFile("$root/$child", name, depth - 1)?.let { return it }
        }
    } finally {
        closedir(handle)
    }
}
