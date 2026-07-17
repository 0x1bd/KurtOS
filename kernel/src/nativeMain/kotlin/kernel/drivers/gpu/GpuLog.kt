package kernel.drivers.gpu

import hal.Serial

class GpuLogEntry(val ok: Boolean?, val name: String, val detail: String)

object GpuLog {
    private val entries = mutableListOf<GpuLogEntry>()

    val history: List<GpuLogEntry> get() = entries

    fun info(message: String) {
        record(GpuLogEntry(null, "", message))
    }

    fun step(name: String, ok: Boolean, detail: String = "") {
        record(GpuLogEntry(ok, name, detail))
    }

    private fun record(entry: GpuLogEntry) {
        if (entries.size < MAX_ENTRIES) entries.add(entry)

        val line = when {
            entry.ok == null -> entry.detail
            entry.detail.isEmpty() -> "${entry.name}: ${status(entry.ok)}"
            else -> "${entry.name}: ${status(entry.ok)} (${entry.detail})"
        }
        Serial.println("[GPU] $line")
    }

    private fun status(ok: Boolean): String = if (ok) "OK" else "FAIL"

    fun hex(value: ULong, width: Int = 0): String {
        val text = value.toString(16)
        return "0x" + if (width > text.length) text.padStart(width, '0') else text
    }

    fun hex(value: UInt, width: Int = 0): String = hex(value.toULong(), width)

    fun mib(bytes: ULong): String = "${bytes / (1024UL * 1024UL)} MiB"

    private const val MAX_ENTRIES = 80
}
