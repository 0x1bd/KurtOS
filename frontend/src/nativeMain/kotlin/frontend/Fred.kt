package frontend

import kapi.Time
import kapi.ui.Canvas
import kapi.ui.Icon
import kapi.ui.Icons

object Fred {
    private const val MAX_FREDS = 256

    private var nonce = 0x9E3779B9u
    private val pets = mutableListOf(FredPet(nextNonce()))

    fun draw(canvas: Canvas, width: Int, barTop: Int, barBottom: Int) {
        for (pet in pets) pet.draw(canvas, width, barTop, barBottom)
    }

    fun face(): Icon = Icons.get("fred/r0c0")

    fun multiply() {
        val room = MAX_FREDS - pets.size
        if (room <= 0) return
        repeat(minOf(pets.size, room)) { pets.add(FredPet(nextNonce())) }
    }

    fun count(): Int = pets.size

    private fun nextNonce(): UInt {
        nonce = nonce * 2654435761u + 0x6D2B79F5u
        return nonce or 1u
    }
}

private class FredPet(private val nonce: UInt) {
    private var seeded = false
    private var rngState = 1u
    private var state = STAY
    private var frames = IDLE1
    private var frameMs = 260UL
    private var frame = 0
    private var frameTimer = 0UL
    private var stateTimer = 0UL
    private var x = 0.0
    private var facing = 1
    private var dest = 1
    private var energy = 0
    private var last = 0UL

    fun draw(canvas: Canvas, width: Int, barTop: Int, barBottom: Int) {
        val barHeight = barBottom - barTop
        val scale = maxOf(2, barHeight / 18)
        val maxX = maxOf(0, width - FRAME_W * scale)

        val now = Time.uptimeMillis()
        if (!seeded) {
            seeded = true
            rngState = (now.toUInt() xor nonce) or 1u
            energy = 10 + (rnd() % 7u).toInt()
            x = if (maxX > 0) (rnd() % (maxX + 1).toUInt()).toDouble() else 0.0
            dest = if (chance(50)) 1 else -1
            last = now
            beginStay(IDLE1, 260UL, range(1600, 3200))
        }

        var dt = now - last
        last = now
        if (dt > MAX_DT) dt = MAX_DT

        advanceFrame(dt)

        when (state) {
            STAY -> if (countdown(dt)) afterRest(maxX)

            WALK -> {
                x += facing.toDouble() * WALK_SPEED * scale.toDouble() * dt.toDouble() / 1000.0
                if (x <= 0.0) {
                    x = 0.0
                    afterWalk(maxX)
                } else if (x >= maxX.toDouble()) {
                    x = maxX.toDouble()
                    afterWalk(maxX)
                } else if (countdown(dt)) {
                    afterWalk(maxX)
                }
            }
        }

        val icon = Icons.get(frames[frame.coerceIn(0, frames.size - 1)])
        canvas.icon(icon, x.toInt(), barTop - FRAME_H * scale, scale, facing < 0)
    }

    private fun afterWalk(maxX: Int) {
        if (energy <= 0 && atDest(maxX)) sleep() else restActivity()
    }

    private fun afterRest(maxX: Int) {
        if (frames === SLEEP) dest = if (x < maxX.toDouble() / 2.0) 1 else -1

        if (energy <= 0) {
            if (atDest(maxX)) sleep() else beginWalk(maxX)
            return
        }

        val walkChance = if (energy > 4) 60 else 34
        if ((rnd() % 100u).toInt() < walkChance) beginWalk(maxX) else restActivity()
    }

    private fun restActivity() {
        energy--
        if (chance(55)) {
            beginStay(if (chance(50)) IDLE1 else IDLE2, 260UL, range(1800, 3600))
        } else {
            beginStay(if (chance(50)) CLEAN1 else CLEAN2, 150UL, range(1600, 2800))
        }
    }

    private fun sleep() {
        energy = 12 + (rnd() % 7u).toInt()
        beginStay(SLEEP, 520UL, range(9000, 22000))
    }

    private fun atDest(maxX: Int): Boolean {
        if (maxX <= 0) return true
        val zone = maxOf(1, maxX / 8)
        return if (dest < 0) x <= zone.toDouble() else x >= (maxX - zone).toDouble()
    }

    private fun beginStay(anim: Array<String>, ms: ULong, duration: ULong) {
        state = STAY
        frames = anim
        frameMs = ms
        frame = 0
        frameTimer = 0UL
        stateTimer = duration
    }

    private fun beginWalk(maxX: Int) {
        energy--
        state = WALK
        frames = if (chance(50)) WALK1 else WALK2
        frameMs = 120UL
        frame = 0
        frameTimer = 0UL
        stateTimer = range(2200, 4600)
        facing = if (energy <= 0 || chance(82)) dest else -dest
        if (facing < 0 && x <= 0.0) facing = 1
        if (facing > 0 && x >= maxX.toDouble()) facing = -1
    }

    private fun advanceFrame(dt: ULong) {
        frameTimer += dt
        while (frameTimer >= frameMs) {
            frameTimer -= frameMs
            frame = (frame + 1) % frames.size
        }
    }

    private fun countdown(dt: ULong): Boolean {
        if (stateTimer > dt) {
            stateTimer -= dt
            return false
        }
        stateTimer = 0UL
        return true
    }

    private fun rnd(): UInt {
        var value = rngState
        value = value xor (value shl 13)
        value = value xor (value shr 17)
        value = value xor (value shl 5)
        rngState = value
        return value
    }

    private fun range(lo: Int, hi: Int): ULong {
        if (hi <= lo) return lo.toULong()
        return (lo + (rnd() % (hi - lo + 1).toUInt()).toInt()).toULong()
    }

    private fun chance(percent: Int): Boolean = (rnd() % 100u).toInt() < percent

    companion object {
        private const val FRAME_W = 20
        private const val FRAME_H = 19
        private const val STAY = 0
        private const val WALK = 1
        private const val WALK_SPEED = 28.0
        private const val MAX_DT = 200UL

        private val IDLE1 = row(0, 4)
        private val IDLE2 = row(1, 4)
        private val CLEAN1 = row(2, 4)
        private val CLEAN2 = row(3, 4)
        private val WALK1 = row(4, 8)
        private val WALK2 = row(5, 8)
        private val SLEEP = row(6, 4)

        private fun row(index: Int, count: Int): Array<String> = Array(count) { "fred/r${index}c$it" }
    }
}
