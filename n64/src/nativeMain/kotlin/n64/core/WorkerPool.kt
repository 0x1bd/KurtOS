package n64.core

import kotlin.concurrent.AtomicInt
import kotlin.concurrent.Volatile
import kotlin.native.concurrent.ObsoleteWorkersApi
import kotlin.native.concurrent.Worker
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix._SC_NPROCESSORS_ONLN
import platform.posix.getenv
import platform.posix.sched_yield
import platform.posix.sysconf

fun interface PoolJob {
    fun run(lane: Int, lanes: Int)
}

@OptIn(ObsoleteWorkersApi::class, ExperimentalForeignApi::class)
class WorkerPool private constructor(private val extra: Int) {
    val lanes = extra + 1

    @Volatile
    private var job: PoolJob? = null

    @Volatile
    private var failure: Throwable? = null

    private val seq = AtomicInt(0)
    private val done = AtomicInt(0)

    init {
        for (index in 0 until extra) {
            val lane = index + 1
            Worker.start(name = "pool-$lane").executeAfter(0L) { loop(lane) }
        }
    }

    private fun loop(lane: Int) {
        var seen = 0
        var idle = 0
        while (true) {
            val current = seq.value
            if (current == seen) {
                if (++idle >= SPIN_LIMIT) {
                    sched_yield()
                    idle = 0
                }
                continue
            }
            seen = current
            idle = 0
            val fn = job
            if (fn != null) {
                try {
                    fn.run(lane, lanes)
                } catch (t: Throwable) {
                    failure = t
                }
            }
            done.incrementAndGet()
        }
    }

    fun run(fn: PoolJob) {
        job = fn
        done.value = 0
        seq.incrementAndGet()
        fn.run(0, lanes)
        while (done.value != extra) {
        }
        job = null
        failure?.let {
            failure = null
            throw it
        }
    }

    companion object {
        private const val SPIN_LIMIT = 2000
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
