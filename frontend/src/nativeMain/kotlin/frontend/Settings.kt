package frontend

import kapi.Audio
import kapi.Files
import kapi.Time

object Settings {
    const val PATH = "/settings.cfg"

    var volume = 70
        private set
    var muted = false
        private set
    var zoneOffsetMinutes = 60
        private set
    var daylightSaving = true
        private set
    var showFps = false
        private set
    var bootDiagnostics = true
        private set

    private var dirty = false

    fun load() {
        val raw = Files.read(PATH, MAX_BYTES)
        if (raw != null) parse(raw.decodeToString())
        dirty = false
        apply()
    }

    fun apply() {
        Audio.setVolume(volume)
        if (Audio.muted() != muted) Audio.toggleMuted()
        Time.setZone(zoneOffsetMinutes, daylightSaving)
    }

    fun flush(): Boolean? {
        if (!dirty) return null
        dirty = false

        if (!Files.writable(PATH)) return false
        return Files.write(PATH, render().encodeToByteArray())
    }

    fun setVolume(percent: Int) {
        val clamped = percent.coerceIn(0, 100)
        if (clamped == volume) return

        volume = clamped
        dirty = true
        Audio.setVolume(volume)
    }

    fun setMuted(value: Boolean) {
        if (value == muted) return

        muted = value
        dirty = true
        if (Audio.muted() != muted) Audio.toggleMuted()
    }

    fun setZoneOffset(minutes: Int) {
        val clamped = minutes.coerceIn(MIN_OFFSET, MAX_OFFSET)
        if (clamped == zoneOffsetMinutes) return

        zoneOffsetMinutes = clamped
        dirty = true
        Time.setZone(zoneOffsetMinutes, daylightSaving)
    }

    fun setShowFps(value: Boolean) {
        if (value == showFps) return

        showFps = value
        dirty = true
    }

    fun setBootDiagnostics(value: Boolean) {
        if (value == bootDiagnostics) return

        bootDiagnostics = value
        dirty = true
    }

    fun setDaylightSaving(value: Boolean) {
        if (value == daylightSaving) return

        daylightSaving = value
        dirty = true
        Time.setZone(zoneOffsetMinutes, daylightSaving)
    }

    fun syncFromSystem() {
        val level = Audio.volume().coerceIn(0, 100)
        val silent = Audio.muted()

        if (level != volume || silent != muted) dirty = true

        volume = level
        muted = silent
    }

    fun zoneLabel(): String {
        val total = zoneOffsetMinutes
        val sign = if (total < 0) "-" else "+"
        val magnitude = if (total < 0) -total else total
        return "UTC$sign${pad(magnitude / 60)}:${pad(magnitude % 60)}"
    }

    private fun parse(text: String) {
        for (line in text.split('\n')) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

            val split = trimmed.indexOf(' ')
            if (split <= 0) continue

            val key = trimmed.take(split)
            val value = trimmed.drop(split + 1).trim()

            when (key) {
                "volume" -> volume = (value.toIntOrNull() ?: volume).coerceIn(0, 100)
                "muted" -> muted = value == "1"
                "zone" -> zoneOffsetMinutes = (value.toIntOrNull() ?: zoneOffsetMinutes).coerceIn(MIN_OFFSET, MAX_OFFSET)
                "dst" -> daylightSaving = value == "1"
                "fps" -> showFps = value == "1"
                "boot" -> bootDiagnostics = value != "0"
            }
        }
    }

    private fun render(): String = buildString {
        append("volume ").append(volume).append('\n')
        append("muted ").append(if (muted) 1 else 0).append('\n')
        append("zone ").append(zoneOffsetMinutes).append('\n')
        append("dst ").append(if (daylightSaving) 1 else 0).append('\n')
        append("fps ").append(if (showFps) 1 else 0).append('\n')
        append("boot ").append(if (bootDiagnostics) 1 else 0).append('\n')
    }

    private fun pad(value: Int): String = value.toString().padStart(2, '0')

    const val OFFSET_STEP = 30
    const val MIN_OFFSET = -720
    const val MAX_OFFSET = 840

    private const val MAX_BYTES: UInt = 4096u
}
