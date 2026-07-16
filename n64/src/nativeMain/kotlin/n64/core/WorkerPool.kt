package n64.core

import kotlin.concurrent.AtomicIntArray
import kotlin.concurrent.Volatile
import kotlin.native.concurrent.ObsoleteWorkersApi
import kotlin.native.concurrent.Worker
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix._SC_NPROCESSORS_ONLN
import platform.posix.getenv
import platform.posix.sched_yield
import platform.posix.sysconf
import platform.posix.usleep

fun interface PoolJob {
    fun run(lane: Int, lanes: Int)
}

@OptIn(ObsoleteWorkersApi::class, ExperimentalForeignApi::class, ExperimentalStdlibApi::class)
class WorkerPool private constructor(private val extra: Int) {
    val lanes = extra + 1

    @Volatile
    private var job: PoolJob? = null

    @Volatile
    private var broken = false

    private var epoch = 0
    private val jobSeq = AtomicIntArray(extra)
    private val ackSeq = AtomicIntArray(extra)

    init {
        for (index in 0 until extra) {
            val lane = index + 1
            Worker.start(name = "pool-$lane").executeAfter(0L) { loop(lane) }
        }
    }

    private fun loop(lane: Int) {
        val slot = lane - 1
        var idle = 0L
        while (true) {
            val want = jobSeq[slot]
            if (want == ackSeq[slot]) {
                idle++
                if (idle >= SLEEP_AFTER) {
                    usleep(1000u)
                } else if (idle % SPIN_HOT == 0L) {
                    sched_yield()
                }
                continue
            }
            idle = 0
            val fn = job
            if (fn != null) {
                try {
                    fn.run(lane, lanes)
                } catch (t: Throwable) {
                    println("pool: lane $lane failed: ${t.message ?: t::class.simpleName}")
                }
            }
            ackSeq[slot] = want
        }
    }

    fun run(fn: PoolJob) {
        if (broken) {
            for (lane in 0 until lanes) fn.run(lane, lanes)
            return
        }

        job = fn
        epoch++
        for (slot in 0 until extra) jobSeq[slot] = epoch

        fn.run(0, lanes)

        for (slot in 0 until extra) {
            var waited = 0L
            while (ackSeq[slot] != epoch) {
                waited++
                if (waited >= YIELD_AFTER) sched_yield()
                if (waited >= JOIN_LIMIT) {
                    broken = true
                    println("pool: lane ${slot + 1} unresponsive, rendering inline from now on")
                    fn.run(slot + 1, lanes)
                    break
                }
            }
        }
    }

    companion object {
        private const val SPIN_HOT = 2000L
        private const val SLEEP_AFTER = 2_000_000L
        private const val YIELD_AFTER = 4_000_000L
        private const val JOIN_LIMIT = YIELD_AFTER + 10_000L
        private const val MAX_EXTRA = 3

        val shared: WorkerPool? by lazy {
            val requested = getenv("KURTOS_RDP_THREADS")?.toKString()?.toIntOrNull()
            val extra = if (requested != null) {
                requested - 1
            } else {
                val cpus = sysconf(_SC_NPROCESSORS_ONLN).toInt()
                minOf(cpus - 1, MAX_EXTRA)
            }
            if (extra >= 1) WorkerPool(extra) else null
        }
    }
}
