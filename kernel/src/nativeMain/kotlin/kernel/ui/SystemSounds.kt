package kernel.ui

import hal.Clock
import kernel.audio.AudioService

object SystemSounds {
    enum class Clip { Coin, Pipe, Blip, Bump, Mute, Fanfare }

    private var buffer = ShortArray(0)
    private var length = 0
    private var playIndex = 0
    private var mixIndex = 0
    private var mixing = false
    private var ownStream = false
    private var silencing = false
    private var closeAt = 0UL

    private var block = ShortArray(0)

    fun play(clip: Clip) {
        if (!begin()) return
        build(clip)
        finish()
    }

    fun playFred(id: Int) {
        if (!begin()) return
        buildFred(id)
        finish()
    }

    private fun begin(): Boolean {
        if (!AudioService.available) return false

        if (buffer.isEmpty()) {
            buffer = ShortArray(AudioService.sampleRate * 2)
            block = ShortArray(BLOCK_FRAMES * 2)
        }

        mixing = false
        length = 0
        return true
    }

    private fun finish() {
        if (length == 0) return

        if (AudioService.streaming && !ownStream) {
            mixIndex = 0
            mixing = true
            return
        }

        if (!ownStream) {
            if (!AudioService.open()) return
            ownStream = true
        }

        playIndex = 0
        silencing = false
        closeAt = Clock.uptimeMillis() + (length * 1000 / AudioService.sampleRate).toULong() + LINGER_MS
        pump()
    }

    private fun buildFred(id: Int) {
        when (id) {
            0 -> tone(523, 25, GENTLE)
            1 -> { sweep(520, 880, 90); sweep(880, 430, 150) }
            2 -> { sweep(720, 1180, 80); sweep(1180, 600, 120) }
            3 -> tone(1400, 25)
            4 -> { tone(523, 55); tone(659, 55); tone(784, 55); tone(1047, 130) }
            5 -> { sweep(2100, 200, 45); sweep(280, 1700, 45) }
            6 -> sweep(1600, 200, 90)
            7 -> sweep(150, 60, 700)
            8 -> { tone(880, 70); tone(500, 70); tone(880, 70); tone(500, 70); tone(880, 70) }
            9 -> sweep(180, 2200, 320)
            10 -> { sweep(300, 1500, 460); tone(1568, 200) }
            11 -> { sweep(1200, 320, 180); tone(523, 120, GENTLE) }
        }
    }

    fun nextMixSample(): Int {
        if (!mixing) return 0

        val value = buffer[mixIndex].toInt()
        mixIndex++
        if (mixIndex >= length) mixing = false

        return value
    }

    fun tick() {
        if (!ownStream) return

        pump()

        if (Clock.uptimeMillis() >= closeAt) {
            ownStream = false
            AudioService.close()
        }
    }

    fun surrender() {
        if (!ownStream) return

        ownStream = false
        mixing = false
        AudioService.close()
    }

    private fun pump() {
        while (playIndex < length) {
            val room = AudioService.availableFrames()
            if (room <= 0) return

            val frames = minOf(BLOCK_FRAMES, room, length - playIndex)

            for (i in 0 until frames) {
                val value = buffer[playIndex + i]
                block[i * 2] = value
                block[i * 2 + 1] = value
            }

            val written = AudioService.write(block, frames)
            if (written <= 0) return
            playIndex += written
        }

        if (!silencing) {
            block.fill(0)
            silencing = true
        }

        val cushion = AudioService.sampleRate / 20

        while (true) {
            val queued = AudioService.queuedFrames()
            if (queued >= cushion) break

            val room = AudioService.availableFrames()
            if (room <= 0) break

            if (AudioService.write(block, minOf(BLOCK_FRAMES, room, cushion - queued)) <= 0) break
        }
    }

    private fun build(clip: Clip) {
        when (clip) {
            Clip.Coin -> {
                tone(988, 90)
                tone(1319, 340)
            }

            Clip.Pipe -> sweep(700, 180, 300)

            Clip.Blip -> tone(523, 25, GENTLE)

            Clip.Bump -> sweep(220, 110, 110)

            Clip.Mute -> tone(330, 50, GENTLE)

            Clip.Fanfare -> {
                tone(659, 110)
                tone(784, 110)
                tone(1319, 110)
                tone(1047, 110)
                tone(1175, 110)
                tone(1568, 320)
            }
        }
    }

    private fun tone(hertz: Int, millis: Int, peak: Int = AMPLITUDE) {
        val rate = AudioService.sampleRate
        val samples = rate * millis / 1000
        val period = if (hertz > 0) rate / hertz else 0

        for (i in 0 until samples) {
            if (length >= buffer.size) return
            buffer[length] = square(i, period, envelope(i, samples, peak))
            length++
        }
    }

    private fun sweep(fromHertz: Int, toHertz: Int, millis: Int) {
        val rate = AudioService.sampleRate
        val samples = rate * millis / 1000

        var phase = 0
        var period = if (fromHertz > 0) rate / fromHertz else 0

        for (i in 0 until samples) {
            if (length >= buffer.size) return

            val hertz = fromHertz + (toHertz - fromHertz) * i / samples
            if (hertz > 0) {
                val next = rate / hertz
                if (phase >= period) {
                    phase = 0
                    period = next
                }
            }

            buffer[length] = square(phase, period, envelope(i, samples, AMPLITUDE))
            length++
            phase++
        }
    }

    private fun square(phase: Int, period: Int, amplitude: Int): Short {
        if (period <= 0) return 0
        return if ((phase % period) * 2 < period) amplitude.toShort() else (-amplitude).toShort()
    }

    private fun envelope(position: Int, total: Int, peak: Int): Int {
        val attack = AudioService.sampleRate * ATTACK_MS / 1000
        if (position < attack) return peak * position / attack

        val fadeStart = total * 6 / 10
        if (position < fadeStart) return peak

        val remaining = total - position
        return peak * remaining / (total - fadeStart)
    }

    private const val AMPLITUDE = 6500
    private const val GENTLE = 2200
    private const val BLOCK_FRAMES = 512
    private const val ATTACK_MS = 4
    private const val LINGER_MS = 2500UL
}
