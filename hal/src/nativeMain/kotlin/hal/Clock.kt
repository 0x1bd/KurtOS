package hal

object Clock {
    fun uptimeMillis(): ULong = Arch.ticks()

    fun timestamp(): ULong = Cpu.timestamp()

    fun sleepMillis(millis: ULong) {
        val deadline = uptimeMillis() + millis
        while (uptimeMillis() < deadline) {
            Cpu.waitForInterrupt()
        }
    }
}
