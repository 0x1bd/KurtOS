package spezi.common

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.posix.F_OK
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.access
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.fwrite

object Files {

    fun exists(path: String): Boolean = access(path, F_OK) == 0

    fun read(path: String): String? {
        val handle = fopen(path, "rb") ?: return null
        try {
            if (fseek(handle, 0, SEEK_END) != 0) return null
            val size = ftell(handle).toInt()
            if (size < 0) return null
            if (fseek(handle, 0, SEEK_SET) != 0) return null
            if (size == 0) return ""
            val buffer = ByteArray(size)
            val read = buffer.usePinned { fread(it.addressOf(0), 1u, size.toULong(), handle) }
            return buffer.decodeToString(0, read.toInt())
        } finally {
            fclose(handle)
        }
    }

    fun write(path: String, content: String): Boolean {
        val handle = fopen(path, "wb") ?: return false
        try {
            val bytes = content.encodeToByteArray()
            if (bytes.isEmpty()) return true
            val written = bytes.usePinned { fwrite(it.addressOf(0), 1u, bytes.size.toULong(), handle) }
            return written.toInt() == bytes.size
        } finally {
            fclose(handle)
        }
    }

    fun join(base: String, relative: String): String =
        if (base.isEmpty()) relative
        else if (base.endsWith('/')) base + relative
        else "$base/$relative"

    fun normalize(path: String): String {
        val absolute = path.startsWith('/')
        val parts = ArrayList<String>()
        for (part in path.split('/')) {
            when {
                part.isEmpty() || part == "." -> {}
                part == ".." && parts.isNotEmpty() && parts.last() != ".." -> parts.removeAt(parts.size - 1)
                else -> parts.add(part)
            }
        }
        val joined = parts.joinToString("/")
        return if (absolute) "/$joined" else joined
    }
}
