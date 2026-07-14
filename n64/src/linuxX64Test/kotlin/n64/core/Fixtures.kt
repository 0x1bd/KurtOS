package n64.core

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.SEEK_END
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.fwrite
import platform.posix.getenv

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
fun fixture(name: String): ByteArray? {
    val directory = getenv("KURTOS_TESTROMS")?.toKString() ?: return null
    return readFile("$directory/$name")
}

@OptIn(ExperimentalForeignApi::class)
fun game(): ByteArray? {
    val path = getenv("KURTOS_GAME")?.toKString() ?: return null
    return readFile(path)
}

@OptIn(ExperimentalForeignApi::class)
fun environment(name: String): String? = getenv(name)?.toKString()

@OptIn(ExperimentalForeignApi::class)
fun dumpNative(console: N64, name: String) {
    val directory = environment("KURTOS_DUMP") ?: return

    val origin = console.vi.regs[VI_ORIGIN] and 0xFFFFFF
    val width = console.vi.regs[VI_WIDTH] and 0xFFF
    if (width == 0 || origin == 0) return

    val type = console.vi.regs[VI_STATUS] and 3
    val height = if (width >= 640) 480 else 240

    val handle = fopen("$directory/$name.ppm", "wb") ?: return

    val header = "P6\n$width $height\n255\n"
    val bytes = ByteArray(header.length + width * height * 3)
    for (i in header.indices) bytes[i] = header[i].code.toByte()

    var at = header.length
    for (y in 0 until height) {
        for (x in 0 until width) {
            if (type == 3) {
                val pixel = console.ramRead32(origin + (y * width + x) * 4)
                bytes[at++] = ((pixel ushr 24) and 0xFF).toByte()
                bytes[at++] = ((pixel ushr 16) and 0xFF).toByte()
                bytes[at++] = ((pixel ushr 8) and 0xFF).toByte()
            } else {
                val pixel = console.ramRead16(origin + (y * width + x) * 2)
                bytes[at++] = ((((pixel ushr 11) and 0x1F) * 255 / 31)).toByte()
                bytes[at++] = ((((pixel ushr 6) and 0x1F) * 255 / 31)).toByte()
                bytes[at++] = ((((pixel ushr 1) and 0x1F) * 255 / 31)).toByte()
            }
        }
    }

    bytes.usePinned { fwrite(it.addressOf(0), 1u, bytes.size.toULong(), handle) }
    fclose(handle)
}

@OptIn(ExperimentalForeignApi::class)
fun dumpFrame(frame: ShortArray, width: Int, height: Int, name: String) {
    val directory = environment("KURTOS_DUMP") ?: return
    val handle = fopen("$directory/$name.ppm", "wb") ?: return

    val header = "P6\n$width $height\n255\n"
    val bytes = ByteArray(header.length + width * height * 3)
    for (i in header.indices) bytes[i] = header[i].code.toByte()

    var at = header.length
    for (pixel in frame) {
        val value = pixel.toInt() and 0xFFFF
        bytes[at++] = (((value and 0x1F) * 255 / 31)).toByte()
        bytes[at++] = ((((value shr 5) and 0x1F) * 255 / 31)).toByte()
        bytes[at++] = ((((value shr 10) and 0x1F) * 255 / 31)).toByte()
    }

    bytes.usePinned { fwrite(it.addressOf(0), 1u, bytes.size.toULong(), handle) }
    fclose(handle)
}
