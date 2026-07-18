package kernel.audio

import hal.Clock
import hal.RawMemory
import kernel.KLog
import kernel.drivers.HDAController
import kernel.drivers.Pci

object AudioService {
    private var controller: HDAController? = null
    private var writeCursor = 0
    private var playing = false
    private var muted = false

    private var level = 50

    var status: String = "not initialized"
        private set

    val sampleRate: Int get() = HDAController.SAMPLE_RATE
    val channels: Int get() = HDAController.CHANNELS

    val available: Boolean get() = controller != null

    val streaming: Boolean get() = playing

    fun initialize(): Boolean {
        if (controller != null) return true

        val devices = Pci.findAll(CLASS_MULTIMEDIA, SUBCLASS_HD_AUDIO)
        if (devices.isEmpty()) {
            status = "no hd audio controller"
            report(false)
            return false
        }

        var lastStatus = "no hd audio controller"
        for (device in devices) {
            val hda = HDAController(device)
            if (hda.initialize()) {
                controller = hda
                status = hda.status
                report(true)
                return true
            }
            lastStatus = hda.status
        }

        status = lastStatus
        report(false)
        return false
    }

    private var reported = false

    private fun report(ok: Boolean) {
        if (reported) return
        reported = true
        KLog.step("audio", "hda", ok, status)
    }

    fun describe(): List<String> = controller?.describeCodecs() ?: listOf(status)

    fun volume(): Int = level

    fun setVolume(percent: Int) {
        level = percent.coerceIn(0, 100)
        muted = false
    }

    fun adjustVolume(delta: Int) {
        setVolume(level + delta)
    }

    fun toggleMuted() {
        muted = !muted
    }

    fun muted(): Boolean = muted

    fun open(): Boolean {
        val hda = controller ?: return false
        kernel.ui.SystemSounds.surrender()
        if (playing) return true

        silence()
        writeCursor = HDAController.BUFFER_BYTES / 2

        hda.start()
        playing = true
        return true
    }

    fun close() {
        val hda = controller ?: return
        if (!playing) return

        hda.stop()
        silence()
        playing = false
    }

    fun availableFrames(): Int {
        val hda = controller ?: return 0
        if (!playing) return 0

        return freeBytes(hda) / HDAController.BYTES_PER_FRAME
    }

    fun queuedFrames(): Int {
        val hda = controller ?: return 0
        if (!playing) return 0

        val capacity = (HDAController.BUFFER_BYTES - GUARD_BYTES) / HDAController.BYTES_PER_FRAME
        return capacity - freeBytes(hda) / HDAController.BYTES_PER_FRAME
    }

    fun write(samples: ShortArray, frames: Int): Int {
        val hda = controller ?: return 0
        if (!playing || frames <= 0) return 0

        val capacity = HDAController.BUFFER_BYTES
        val limit = freeBytes(hda) / HDAController.BYTES_PER_FRAME
        val count = if (frames < limit) frames else limit
        if (count <= 0) return 0

        val base = hda.bufferAddress()
        var offset = writeCursor
        var index = 0

        for (frame in 0 until count) {
            val overlay = kernel.ui.SystemSounds.nextMixSample()

            RawMemory.write16(base + offset.toULong(), scale(samples[index], overlay))
            offset = (offset + 2) % capacity
            index++

            RawMemory.write16(base + offset.toULong(), scale(samples[index], overlay))
            offset = (offset + 2) % capacity
            index++
        }

        writeCursor = offset
        return count
    }

    private fun freeBytes(hda: HDAController): Int {
        val capacity = HDAController.BUFFER_BYTES
        val position = hda.position() % capacity

        var free = position - writeCursor
        if (free <= 0) free += capacity

        free -= GUARD_BYTES
        return if (free < 0) 0 else free
    }

    private fun scale(sample: Short, overlay: Int = 0): UShort {
        if (muted) return 0u

        val mixed = (sample.toInt() + overlay).coerceIn(-32768, 32767)
        val value = mixed * level / (100 * MASTER_DIVISOR)
        return value.toShort().toUShort()
    }

    private fun silence() {
        val hda = controller ?: return
        RawMemory.zero(hda.bufferAddress(), HDAController.BUFFER_BYTES.toULong())
    }

    fun tone(hertz: Int, millis: Int) {
        controller ?: return

        val opened = open()
        if (!opened) return

        val total = sampleRate * millis / 1000
        val block = ShortArray(BLOCK_FRAMES * HDAController.CHANNELS)

        var produced = 0
        var phase = 0

        while (produced < total) {
            val room = availableFrames()
            if (room <= 0) continue

            val frames = minOf(BLOCK_FRAMES, minOf(room, total - produced))

            for (i in 0 until frames) {
                val value = square(phase, hertz)
                block[i * 2] = value
                block[i * 2 + 1] = value
                phase++
            }

            produced += write(block, frames)
        }

        Clock.sleepMillis((bufferMillis() + DRAIN_MARGIN_MS).toULong())
        close()
    }

    private fun bufferMillis(): Int =
        HDAController.BUFFER_BYTES / HDAController.BYTES_PER_FRAME * 1000 / sampleRate

    private fun square(phase: Int, hertz: Int): Short {
        if (hertz <= 0) return 0

        val period = sampleRate / hertz
        if (period <= 0) return 0

        return if ((phase % period) * 2 < period) AMPLITUDE else (-AMPLITUDE).toShort()
    }

    private const val CLASS_MULTIMEDIA = 0x04
    private const val SUBCLASS_HD_AUDIO = 0x03

    private const val MASTER_DIVISOR = 10
    private const val GUARD_BYTES = 256
    private const val BLOCK_FRAMES = 512
    private const val AMPLITUDE: Short = 8000
    private const val DRAIN_MARGIN_MS = 30
}
