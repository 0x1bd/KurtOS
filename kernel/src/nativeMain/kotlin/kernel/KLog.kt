package kernel

import hal.Clock
import hal.Serial

class KLogEntry(val millis: ULong, val tag: String, val ok: Boolean?, val label: String, val detail: String)

object KLog {
    private val entries = mutableListOf<KLogEntry>()

    val history: List<KLogEntry> get() = entries

    fun info(tag: String, message: String) {
        record(KLogEntry(Clock.uptimeMillis(), tag, null, "", message))
    }

    fun step(tag: String, label: String, ok: Boolean, detail: String = "") {
        record(KLogEntry(Clock.uptimeMillis(), tag, ok, label, detail))
    }

    fun entriesFor(tag: String): List<KLogEntry> = entries.filter { it.tag == tag }

    fun tags(): List<String> {
        val seen = mutableListOf<String>()
        for (entry in entries) {
            if (entry.tag !in seen) seen.add(entry.tag)
        }
        return seen
    }

    fun format(entry: KLogEntry): String {
        val body = when {
            entry.ok == null -> entry.detail
            entry.detail.isEmpty() -> "${entry.label}: ${status(entry.ok)}"
            else -> "${entry.label}: ${status(entry.ok)} (${entry.detail})"
        }
        return "${timestamp(entry.millis)} ${entry.tag.padEnd(7)} $body"
    }

    private fun status(ok: Boolean): String = if (ok) "OK" else "FAIL"

    private fun timestamp(millis: ULong): String {
        val seconds = (millis / 1000UL).toString().padStart(4)
        val fraction = (millis % 1000UL).toString().padStart(3, '0')
        return "[$seconds.$fraction]"
    }

    private fun record(entry: KLogEntry) {
        if (entries.size < MAX_ENTRIES) entries.add(entry)
        Serial.println(format(entry))
    }

    fun hex(value: ULong, width: Int = 0): String {
        val text = value.toString(16)
        return "0x" + if (width > text.length) text.padStart(width, '0') else text
    }

    fun hex(value: UInt, width: Int = 0): String = hex(value.toULong(), width)

    fun mib(bytes: ULong): String = "${bytes / (1024UL * 1024UL)} MiB"

    private const val MAX_ENTRIES = 512
}
