@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package n64.core

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.toLong
import kotlinx.cinterop.usePinned
import platform.posix.MAP_ANONYMOUS
import platform.posix.MAP_FAILED
import platform.posix.MAP_PRIVATE
import platform.posix.PROT_EXEC
import platform.posix.PROT_READ
import platform.posix.PROT_WRITE
import platform.posix.memcpy
import platform.posix.mmap

const val CODE_ARENA_SIZE = 32 shl 20

class CodeArena {
    var base = 0L
        private set
    var offset = 0
        private set

    fun init(): Boolean {
        val mem = mmap(
            null,
            CODE_ARENA_SIZE.toULong(),
            PROT_READ or PROT_WRITE or PROT_EXEC,
            MAP_PRIVATE or MAP_ANONYMOUS,
            -1,
            0,
        )
        if (mem == null || mem == MAP_FAILED) return false
        base = mem.toLong()
        return true
    }

    fun reset() {
        offset = 0
    }

    fun add(buf: ByteArray, len: Int): Long {
        if (offset + len > CODE_ARENA_SIZE) return 0
        val at = base + offset
        buf.usePinned {
            memcpy(at.toCPointer<ByteVar>(), it.addressOf(0), len.toULong())
        }
        offset = (offset + len + 15) and 15.inv()
        return at
    }
}
