package kernel

import kernel.arch.Smp
import kernel.drivers.usb.GamepadService
import kernel.drivers.usb.UsbLock
import kotlin.native.concurrent.ObsoleteWorkersApi
import kotlin.native.concurrent.Worker
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.usleep

@OptIn(ObsoleteWorkersApi::class, ExperimentalForeignApi::class)
object InputService {
    val threaded: Boolean get() = GamepadService.threaded

    fun start() {
        if (GamepadService.threaded) return
        if (Smp.cpus < MIN_CPUS || !Smp.workersVerified) return

        GamepadService.threaded = true
        Worker.start(name = "input").executeAfter(0L) { loop() }
    }

    private fun loop() {
        while (true) {
            UsbLock.withLock { GamepadService.service() }
            usleep(POLL_MICROS)
        }
    }

    private const val MIN_CPUS = 5
    private val POLL_MICROS = 1000u
}
