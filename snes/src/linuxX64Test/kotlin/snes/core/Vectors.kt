package snes.core

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.posix.SEEK_END
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.getenv
import kotlinx.cinterop.toKString

@OptIn(ExperimentalForeignApi::class)
fun fixture(name: String): ByteArray? {
    val directory = getenv("KURTOS_TESTROMS")?.toKString() ?: return null
    val path = "$directory/$name"

    val handle = fopen(path, "rb") ?: return null

    try {
        fseek(handle, 0, SEEK_END)
        val size = ftell(handle).toInt()
        if (size <= 0) return null
        fseek(handle, 0, 0)

        val data = ByteArray(size)
        data.usePinned { pinned ->
            fread(pinned.addressOf(0), 1u, size.toULong(), handle)
        }

        return data
    } finally {
        fclose(handle)
    }
}

class VectorReader(private val data: ByteArray) {
    var position = 0
        private set

    fun u8(): Int = data[position++].toInt() and 0xFF

    fun u16(): Int = u8() or (u8() shl 8)

    fun u32(): Int = u16() or (u16() shl 16)

    fun magic(): String {
        val builder = StringBuilder()
        for (i in 0 until 4) builder.append(u8().toChar())
        return builder.toString()
    }
}

class TestMemory(size: Int) : Memory {
    val data = ByteArray(size)

    override fun read8(addr: Int): Int = data[addr].toInt() and 0xFF

    override fun write8(addr: Int, value: Int) {
        data[addr] = value.toByte()
    }

    override fun speed(addr: Int): Int = Cpu.INTERNAL
}
