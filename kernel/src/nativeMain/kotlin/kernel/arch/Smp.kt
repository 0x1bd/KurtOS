package kernel.arch

import hal.Arch
import hal.Serial
import kotlin.concurrent.AtomicInt
import kotlin.native.concurrent.ObsoleteWorkersApi
import kotlin.native.concurrent.Worker

@OptIn(ObsoleteWorkersApi::class)
object Smp {
    var cpus = 1
        private set

    var workersVerified = false
        private set

    fun initialize() {
        Arch.smpStart()
        cpus = Arch.smpCpus()
        if (cpus <= 1) {
            Serial.print("smp: single cpu, no application processors\n")
            return
        }

        val counter = AtomicInt(0)
        val workers = Array(cpus - 1) { Worker.start(name = "smp-test-$it") }
        for (worker in workers) {
            worker.executeAfter(0L) { counter.incrementAndGet() }
        }

        var spins = 0L
        while (counter.value != workers.size && spins < 2_000_000_000L) spins++
        workersVerified = counter.value == workers.size

        for (worker in workers) worker.requestTermination(processScheduledJobs = true).result

        Serial.print("smp: $cpus cpus, worker self-test ${if (workersVerified) "ok" else "FAILED"}\n")
    }
}
