package kernel.drivers.usb

import kotlin.concurrent.AtomicInt
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.sched_yield

@OptIn(ExperimentalForeignApi::class)
object UsbLock {
    private val held = AtomicInt(0)

    fun acquire() {
        while (!held.compareAndSet(0, 1)) {
            sched_yield()
        }
    }

    fun release() {
        held.value = 0
    }

    inline fun <T> withLock(block: () -> T): T {
        acquire()
        try {
            return block()
        } finally {
            release()
        }
    }
}
