package frontend.fred

import kapi.Audio
import kapi.Sfx
import kapi.Surface
import kapi.ui.Canvas
import kapi.ui.Icon
import kapi.ui.Icons
import kapi.ui.ModalChoice
import kapi.ui.NavInput
import kapi.ui.PopupModal

object Fred {
    private const val MAX_FREDS = 256

    private var nonce = 0x9E3779B9u
    private val pets = mutableListOf(FredPet(nextNonce(), false))

    fun draw(canvas: Canvas, width: Int, height: Int, navTop: Int) {
        for (pet in pets) pet.draw(canvas, width, height, navTop)
    }

    fun overlay(canvas: Canvas, width: Int, height: Int, navTop: Int) = Chaos.overlay(canvas, width, height, navTop)

    fun face(): Icon = Icons.get("fred/r0c0")

    fun count(): Int = pets.size

    fun level(): Int = Chaos.level()

    fun shakeX(): Int = Chaos.shakeX()
    fun shakeY(): Int = Chaos.shakeY()

    fun title(default: String): String = Chaos.title(default)
    fun clock(default: String): String = Chaos.clock(default)
    fun welcome(default: String): String = Chaos.welcome(default)
    fun card(default: String): String = Chaos.card(default)
    fun settingsLabel(): String = Chaos.settingsLabel()
    fun paper(default: UInt): UInt = Chaos.paper(default)
    fun stripe(default: UInt): UInt = Chaos.stripe(default)

    fun multiply() {
        val room = MAX_FREDS - pets.size
        if (room <= 0) return
        repeat(minOf(pets.size, room)) { pets.add(FredPet(nextNonce(), true)) }
        Audio.play(Sfx.MEOW)
    }

    private fun reset() {
        pets.clear()
        pets.add(FredPet(nextNonce(), false))
    }

    fun easterEgg(surface: Surface, background: (Canvas) -> Unit) {
        var round = 0
        while (true) {
            val answer = PopupModal.ask(
                surface,
                "DISABLE FRED",
                listOf(FredText.question(Chaos.level())),
                listOf(ModalChoice("YES"), ModalChoice("NO")),
                face(),
            ) { canvas -> background(canvas) }

            if (answer != 0) break

            multiply()
            Audio.play(if (Chaos.level() >= 4) Sfx.GLITCH else Sfx.POWERUP)

            if (pets.size >= MAX_FREDS) {
                Endgame.finale(surface)
                reset()
                break
            }

            PopupModal.ask(
                surface,
                "FRED x ${pets.size}",
                listOf(FredText.line(Chaos.level(), round)),
                listOf(ModalChoice("OK")),
                face(),
            ) { canvas -> background(canvas) }
            round++
        }
        NavInput.prime()
    }

    private fun nextNonce(): UInt {
        nonce = nonce * 2654435761u + 0x6D2B79F5u
        return nonce or 1u
    }
}
