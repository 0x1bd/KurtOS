package frontend

import kapi.Audio
import kapi.Console
import kapi.Files
import kapi.Gamepad
import kapi.Graphics
import kapi.Input
import kapi.Keys
import kapi.Pad
import kapi.Surface
import kapi.Sys
import kapi.Time
import kapi.ui.Panels
import kapi.ui.PixelFont
import kapi.ui.PixelIcons
import kapi.ui.PixelSink
import kapi.ui.SurfaceSink
import shell.CommandRegistry
import shell.Shell

object Home {
    private class Card(
        val title: String,
        val body: UInt,
        val band: UInt,
        val edge: UInt,
        val art: PixelIcons.Icon,
        val extension: String,
    )

    private val consoles = listOf(
        Card("SNES", 0x00DCE7F7u, 0x006FA3DEu, 0x00A9C4E8u, ConsoleArt.SNES, ".sfc"),
        Card("GB", 0x00E1EFD5u, 0x00A6C87Cu, 0x00BCD79Bu, ConsoleArt.GAME_BOY, ".gb"),
        Card("GBC", 0x00EEDCF6u, 0x00C39BDFu, 0x00D3B6E8u, ConsoleArt.GAME_BOY_COLOR, ".gbc"),
        Card("GBA", 0x00D6E8F7u, 0x00A7D0ECu, 0x00AAD0EAu, ConsoleArt.GAME_BOY_ADVANCE, ".gba"),
    )

    private const val SCREEN_HOME = 0
    private const val SCREEN_LIBRARY = 1
    private const val SCREEN_SETTINGS = 2
    private const val SCREEN_SYSTEM = 3

    private const val SETTING_VOLUME = 0
    private const val SETTING_MUTE = 1
    private const val SETTING_ZONE = 2
    private const val SETTING_DST = 3
    private const val SETTING_FPS = 4
    private const val SETTING_SYSTEM = 5
    private const val SETTING_COUNT = 6

    private val HINTS = listOf(
        Chrome.Hint(HomeIcons.DPAD, null, Panels.BAR_TEXT, "NAVIGATE"),
        Chrome.Hint(null, "A", Panels.GREEN, "SELECT"),
        Chrome.Hint(null, "B", Panels.RED, "BACK"),
        Chrome.Hint(HomeIcons.MENU, null, Panels.BAR_TEXT, "MENU"),
    )

    private val padPrevious = BooleanArray(Pad.COUNT)

    private var screen = SCREEN_HOME
    private var card = 0
    private var selected = 0
    private var scroll = 0
    private var setting = 0

    fun run(registry: CommandRegistry): Nothing {
        Settings.syncFromSystem()
        Settings.load()

        while (true) {
            val surface = Graphics.surface()
            if (surface == null) {
                Shell.run(registry)
                continue
            }

            var games = GameLibrary.scan()
            val sink = SurfaceSink(surface)
            flushInput()

            var painted = -1L
            var clock = ""
            var dirty = true

            while (true) {
                Input.poll()

                val tick = Chrome.clockText()
                val stripes = stripeOffset()

                if (dirty || stripes != painted || tick != clock) {
                    clock = tick
                    painted = stripes
                    dirty = false
                    render(surface, sink, games, clock, stripes.toInt())
                }

                val action = read()
                if (action != Action.NONE) dirty = true
                if (action == Action.SELECT || action == Action.BACK || action == Action.MENU) Audio.click()

                when (action) {
                    Action.NONE -> Time.idle()
                    Action.UP -> move(-1, games)
                    Action.DOWN -> move(1, games)
                    Action.LEFT -> horizontal(-1)
                    Action.RIGHT -> horizontal(1)

                    Action.SELECT -> {
                        if (select(surface, games, registry)) break
                        games = GameLibrary.scan()
                        clampSelection(games)
                    }

                    Action.BACK -> if (screen != SCREEN_HOME) screen = SCREEN_HOME

                    Action.MENU -> {
                        setting = 0
                        screen = if (screen == SCREEN_SETTINGS) SCREEN_HOME else SCREEN_SETTINGS
                    }

                    Action.LOUDER, Action.QUIETER -> {
                        val step = if (action == Action.LOUDER) VOLUME_STEP else -VOLUME_STEP
                        Settings.setVolume(Settings.volume + step)
                        Audio.showVolume()
                    }

                    Action.REFRESH -> {
                        games = GameLibrary.scan()
                        clampSelection(games)
                    }

                    Action.SHELL -> {
                        openShell(registry)
                        break
                    }
                }
            }

            Sys.collectGarbage()
        }
    }

    private fun select(surface: Surface, games: List<Game>, registry: CommandRegistry): Boolean {
        when (screen) {
            SCREEN_HOME -> {
                selected = 0
                scroll = 0
                screen = SCREEN_LIBRARY
            }

            SCREEN_LIBRARY -> {
                val shelf = shelf(games)
                if (shelf.isEmpty()) return false

                saveSettings()
                play(surface, shelf[selected])
                Settings.syncFromSystem()
                Settings.flush()
                return true
            }

            SCREEN_SETTINGS -> {
                if (setting == SETTING_SYSTEM) screen = SCREEN_SYSTEM else adjust(1)
            }

            SCREEN_SYSTEM -> {
                openShell(registry)
                return true
            }
        }

        return false
    }

    private fun move(delta: Int, games: List<Game>) {
        when (screen) {
            SCREEN_SETTINGS -> setting = (setting + SETTING_COUNT + delta) % SETTING_COUNT

            SCREEN_LIBRARY -> {
                val count = shelf(games).size
                if (count == 0) return
                selected = (selected + count + delta) % count
            }
        }
    }

    private fun horizontal(delta: Int) {
        when (screen) {
            SCREEN_HOME -> card = (card + consoles.size + delta) % consoles.size
            SCREEN_SETTINGS -> if (setting != SETTING_SYSTEM) adjust(delta)
        }
    }

    private fun shelf(games: List<Game>): List<Game> {
        val console = consoles[card]
        return games.filter { it.path.endsWith(console.extension, ignoreCase = true) || alias(it, console) }
    }

    private fun alias(game: Game, console: Card): Boolean =
        console.extension == ".sfc" && game.path.endsWith(".smc", ignoreCase = true)

    private fun clampSelection(games: List<Game>) {
        val count = shelf(games).size
        if (count == 0) {
            selected = 0
            scroll = 0
            return
        }

        if (selected >= count) selected = count - 1
    }

    private fun openShell(registry: CommandRegistry) {
        saveSettings()
        Console.clear()
        Shell.run(registry)
        Console.clear()
        screen = SCREEN_HOME
    }

    private fun play(surface: Surface, game: Game) {
        val failure = Player.play(surface, game)

        if (failure != null) Sys.toast(game.name.uppercase(), failure)
    }

    private fun saveSettings() {
        Settings.syncFromSystem()

        if (Settings.flush() == false) {
            Sys.toast("SETTINGS NOT SAVED", "CANNOT WRITE ${Settings.PATH}")
        }
    }

    private fun adjust(delta: Int) {
        when (setting) {
            SETTING_SYSTEM -> Unit
            SETTING_VOLUME -> Settings.setVolume(Settings.volume + delta * VOLUME_STEP)
            SETTING_MUTE -> Settings.setMuted(!Settings.muted)
            SETTING_ZONE -> Settings.setZoneOffset(Settings.zoneOffsetMinutes + delta * Settings.OFFSET_STEP)
            SETTING_DST -> Settings.setDaylightSaving(!Settings.daylightSaving)
            SETTING_FPS -> Settings.setShowFps(!Settings.showFps)
        }
    }

    private enum class Action { NONE, UP, DOWN, LEFT, RIGHT, SELECT, BACK, MENU, REFRESH, SHELL, LOUDER, QUIETER }

    private fun read(): Action {
        if (Input.consumePress(Keys.UP)) return Action.UP
        if (Input.consumePress(Keys.DOWN)) return Action.DOWN
        if (Input.consumePress(Keys.LEFT)) return Action.LEFT
        if (Input.consumePress(Keys.RIGHT)) return Action.RIGHT
        if (Input.consumePress(Keys.ENTER)) return Action.SELECT
        if (Input.consumePress(Keys.SPACE)) return Action.SELECT
        if (Input.consumePress(Keys.BACKSPACE)) return Action.BACK
        if (Input.consumePress(Keys.ESC)) return Action.BACK
        if (Input.consumePress(Keys.F2)) return Action.MENU
        if (Input.consumePress(Keys.W)) return Action.UP
        if (Input.consumePress(Keys.S)) return Action.DOWN
        if (Input.consumePress(Keys.A)) return Action.LEFT
        if (Input.consumePress(Keys.D)) return Action.RIGHT
        if (Input.consumePress(Keys.R)) return Action.REFRESH
        if (Input.consumePress(Keys.F1)) return Action.SHELL

        while (Console.tryReadChar() != null) {
        }

        return padAction()
    }

    private fun padAction(): Action {
        if (!Gamepad.available()) return Action.NONE
        Gamepad.poll()

        var action = Action.NONE

        for (button in 0 until Pad.COUNT) {
            val down = Gamepad.isDown(button)

            if (down && !padPrevious[button] && action == Action.NONE) {
                action = when (button) {
                    Pad.UP -> Action.UP
                    Pad.DOWN -> Action.DOWN
                    Pad.LEFT -> Action.LEFT
                    Pad.RIGHT -> Action.RIGHT
                    Pad.A, Pad.START -> Action.SELECT
                    Pad.B -> Action.BACK
                    Pad.SELECT -> Action.MENU
                    Pad.LT -> Action.QUIETER
                    Pad.RT -> Action.LOUDER
                    Pad.Y -> Action.REFRESH
                    else -> Action.NONE
                }
            }

            padPrevious[button] = down
        }

        return action
    }

    private fun flushInput() {
        Input.poll()
        Input.drain()
        while (Console.tryReadChar() != null) {
        }

        if (!Gamepad.available()) return

        Gamepad.poll()
        for (button in 0 until Pad.COUNT) padPrevious[button] = Gamepad.isDown(button)
    }

    private fun stripeOffset(): Long = (Time.uptimeMillis() / STRIPE_MILLIS).toLong()

    internal fun preview(surface: Surface, screenId: Int, cardIndex: Int, games: List<Game>, clock: String, stripes: Int) {
        screen = screenId
        card = cardIndex
        selected = 0
        scroll = 0
        setting = 0
        render(surface, SurfaceSink(surface), games, clock, stripes)
    }

    internal const val PREVIEW_HOME = SCREEN_HOME
    internal const val PREVIEW_LIBRARY = SCREEN_LIBRARY
    internal const val PREVIEW_SETTINGS = SCREEN_SETTINGS
    internal const val PREVIEW_SYSTEM = SCREEN_SYSTEM

    private fun render(surface: Surface, sink: PixelSink, games: List<Game>, clock: String, stripes: Int) {
        val width = surface.width.toInt()
        val height = surface.height.toInt()

        drawBackground(sink, width, height, stripes)

        val barHeight = Chrome.barHeight(height)
        Chrome.drawStatusBar(sink, width, barHeight, "KurtOS ${BuildInfo.VERSION}", clock)
        Chrome.drawNavBar(sink, width, height, barHeight, HINTS)

        val top = barHeight
        val bottom = height - barHeight

        when (screen) {
            SCREEN_HOME -> drawHome(sink, width, top, bottom, games)
            SCREEN_LIBRARY -> drawLibrary(sink, width, top, bottom, games)
            SCREEN_SETTINGS -> drawSettings(sink, width, top, bottom)
            else -> drawSystem(sink, width, top, bottom)
        }

        surface.present()
    }

    private fun drawBackground(sink: PixelSink, width: Int, height: Int, stripes: Int) {
        sink.fill(0, 0, width, height, Panels.PAPER)

        val period = STRIPE_WIDTH * 2
        val shift = stripes % period
        var x = shift - period

        while (x < width) {
            sink.fill(x, 0, STRIPE_WIDTH, height, Panels.STRIPE)
            x += period
        }
    }

    private fun drawHome(sink: PixelSink, width: Int, top: Int, bottom: Int, games: List<Game>) {
        val space = bottom - top

        val titleScale = maxOf(2, space / 108)
        val leadScale = maxOf(1, space / 260)

        val title = "WELCOME!"
        PixelFont.draw(
            sink,
            (width - PixelFont.textWidth(title, titleScale)) / 2,
            top + space / 12,
            title,
            Panels.INK,
            titleScale,
        )

        val lead = "Select a system to start playing."
        PixelFont.draw(
            sink,
            (width - PixelFont.textWidth(lead, leadScale)) / 2,
            top + space / 12 + PixelFont.HEIGHT * titleScale + space / 22,
            lead,
            Panels.QUIET,
            leadScale,
        )

        val cardWidth = width * 22 / 100
        val cardHeight = space * 58 / 100
        val gap = width * 3 / 200
        val total = consoles.size * cardWidth + (consoles.size - 1) * gap
        val startX = (width - total) / 2
        val cardY = top + space * 34 / 100

        for ((index, console) in consoles.withIndex()) {
            val x = startX + index * (cardWidth + gap)
            drawCard(sink, x, cardY, cardWidth, cardHeight, console, index == card)
        }

    }

    private fun drawCard(
        sink: PixelSink,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        console: Card,
        active: Boolean,
    ) {
        val border = maxOf(2, width / 80)
        val bandHeight = height * 20 / 100
        val edge = if (active) Panels.ACCENT else console.edge
        val body = if (active) 0x00FFFFFFu else console.body

        if (active) {
            val markWidth = width / 8
            val markHeight = markWidth / 2
            val markX = x + (width - markWidth) / 2
            val markY = y - markHeight - border * 2

            for (row in 0 until markHeight) {
                val inset = row * markWidth / (markHeight * 2)
                sink.fill(markX + inset, markY + row, markWidth - inset * 2, 1, Panels.ACCENT)
            }
        }

        sink.fill(x, y, width, height, edge)
        sink.fill(x + border, y + border, width - border * 2, height - border * 2, body)

        val artArea = height - bandHeight - border
        val scale = maxOf(1, minOf((width - border * 6) / console.art.width, (artArea - border * 4) / console.art.height))
        val artX = x + (width - console.art.width * scale) / 2
        val artY = y + border + (artArea - console.art.height * scale) / 2
        console.art.draw(sink, artX, artY, scale)

        val bandY = y + height - bandHeight - border
        sink.fill(x + border, bandY, width - border * 2, bandHeight, console.band)

        val room = width - border * 4
        val fit = room / (console.title.length * PixelFont.WIDTH)
        val labelScale = maxOf(2, minOf(fit, bandHeight / 12))

        PixelFont.draw(
            sink,
            x + (width - PixelFont.textWidth(console.title, labelScale)) / 2,
            bandY + (bandHeight - PixelFont.HEIGHT * labelScale) / 2,
            console.title,
            Panels.INK,
            labelScale,
        )
    }

    private fun drawPanel(sink: PixelSink, width: Int, top: Int, bottom: Int, title: String): Int {
        val space = bottom - top
        val scale = maxOf(2, space / 200)

        PixelFont.draw(sink, width * 6 / 100, top + space / 20, title, Panels.INK, scale)

        val y = top + space / 20 + PixelFont.HEIGHT * scale + space / 40
        sink.fill(width * 6 / 100, y, width * 88 / 100, maxOf(2, space / 300), Panels.EDGE)

        return y + space / 30
    }

    private fun drawLibrary(sink: PixelSink, width: Int, top: Int, bottom: Int, games: List<Game>) {
        val console = consoles[card]
        val shelf = shelf(games)
        val space = bottom - top

        var y = drawPanel(sink, width, top, bottom, console.title)

        val scale = maxOf(1, space / 260)
        val left = width * 6 / 100
        val listWidth = width * 88 / 100

        if (shelf.isEmpty()) {
            PixelFont.draw(sink, left, y + space / 20, "NO GAMES FOUND", Panels.QUIET, scale + 1)
            PixelFont.draw(
                sink,
                left,
                y + space / 20 + PixelFont.HEIGHT * (scale + 1) + space / 40,
                "COPY ${console.extension.uppercase()} FILES TO ${GameLibrary.DIRECTORY.uppercase()}",
                Panels.QUIET,
                scale,
            )
            return
        }

        val rowHeight = maxOf(28, space / 12)
        val gap = rowHeight / 6
        val capacity = maxOf(1, (bottom - y - space / 20) / (rowHeight + gap))

        if (selected < scroll) scroll = selected
        if (selected >= scroll + capacity) scroll = selected - capacity + 1
        if (scroll > maxOf(0, shelf.size - capacity)) scroll = maxOf(0, shelf.size - capacity)

        for (slot in 0 until minOf(capacity, shelf.size - scroll)) {
            val index = scroll + slot
            val game = shelf[index]
            val rowY = y + slot * (rowHeight + gap)
            val active = index == selected
            val border = maxOf(2, rowHeight / 20)

            sink.fill(left, rowY, listWidth, rowHeight, if (active) Panels.ACCENT else Panels.EDGE)
            sink.fill(
                left + border,
                rowY + border,
                listWidth - border * 2,
                rowHeight - border * 2,
                if (active) 0x00FFFFFFu else console.body,
            )

            val artScale = maxOf(1, (rowHeight - border * 4) / console.art.height)
            console.art.draw(
                sink,
                left + border * 3,
                rowY + (rowHeight - console.art.height * artScale) / 2,
                artScale,
            )

            val textX = left + border * 3 + console.art.width * artScale + width / 60
            val room = (listWidth - (textX - left) - width / 10) / (PixelFont.WIDTH * scale)

            PixelFont.draw(
                sink,
                textX,
                rowY + (rowHeight - PixelFont.HEIGHT * scale) / 2,
                clip(game.name.uppercase(), room),
                Panels.INK,
                scale,
            )

            val size = "${game.size / 1024UL} KIB"
            PixelFont.draw(
                sink,
                left + listWidth - border * 3 - PixelFont.textWidth(size, scale),
                rowY + (rowHeight - PixelFont.HEIGHT * scale) / 2,
                size,
                Panels.QUIET,
                scale,
            )
        }
    }

    private fun drawSettings(sink: PixelSink, width: Int, top: Int, bottom: Int) {
        val space = bottom - top
        var y = drawPanel(sink, width, top, bottom, "SETTINGS")

        val rows = listOf(
            Triple("VOLUME", "${Settings.volume}%", "HOW LOUD EMULATED SOUND PLAYS"),
            Triple("MUTE", onOff(Settings.muted), "SILENCE ALL AUDIO OUTPUT"),
            Triple("TIME ZONE", Settings.zoneLabel(), "THE CMOS CLOCK KEEPS UTC - THIS SHIFTS THE DISPLAYED TIME"),
            Triple(
                "DAYLIGHT SAVING",
                if (Settings.daylightSaving) "AUTO (EU)" else "OFF",
                "ADDS AN HOUR BETWEEN LATE MARCH AND LATE OCTOBER",
            ),
            Triple("FPS OVERLAY", onOff(Settings.showFps), "SHOWS A LIVE FRAME RATE WHILE A GAME RUNS"),
            Triple("SYSTEM INFO", "OPEN", "DRIVER STATUS AND A WAY INTO THE SHELL"),
        )

        val scale = maxOf(1, space / 260)
        val rowHeight = maxOf(34, space / 10)
        val gap = rowHeight / 8
        val left = width * 6 / 100
        val rowWidth = width * 88 / 100

        for ((index, row) in rows.withIndex()) {
            val rowY = y + index * (rowHeight + gap)
            val active = index == setting
            val border = maxOf(2, rowHeight / 22)

            sink.fill(left, rowY, rowWidth, rowHeight, if (active) Panels.ACCENT else Panels.EDGE)
            sink.fill(
                left + border,
                rowY + border,
                rowWidth - border * 2,
                rowHeight - border * 2,
                if (active) 0x00FFFFFFu else Panels.CARD,
            )

            PixelFont.draw(sink, left + border * 4, rowY + rowHeight / 5, row.first, Panels.INK, scale)
            PixelFont.draw(sink, left + border * 4, rowY + rowHeight * 3 / 5, row.third, Panels.QUIET, maxOf(1, scale - 1))

            val value = row.second
            PixelFont.draw(
                sink,
                left + rowWidth - border * 4 - PixelFont.textWidth(value, scale),
                rowY + (rowHeight - PixelFont.HEIGHT * scale) / 2,
                value,
                Panels.INK,
                scale,
            )
        }

        y += rows.size * (rowHeight + gap) + space / 30
        PixelFont.draw(
            sink,
            left,
            y,
            "SAVED TO ${Settings.PATH.uppercase()}",
            Panels.QUIET,
            maxOf(1, scale - 1),
        )
    }

    private fun drawSystem(sink: PixelSink, width: Int, top: Int, bottom: Int) {
        val space = bottom - top
        var y = drawPanel(sink, width, top, bottom, "SYSTEM")

        val rows = listOf(
            "DISPLAY" to Graphics.status().uppercase(),
            "AUDIO" to Audio.status().uppercase(),
            "GAMEPAD" to Gamepad.status().uppercase(),
            "STORAGE" to Files.status().uppercase(),
            "MEMORY" to Sys.memoryReport().uppercase(),
        )

        val scale = maxOf(1, space / 260)
        val left = width * 6 / 100
        val step = maxOf(24, space / 14)
        val room = (width * 88 / 100 - width / 5) / (PixelFont.WIDTH * scale)

        for ((label, value) in rows) {
            PixelFont.draw(sink, left, y, label, Panels.QUIET, scale)
            PixelFont.draw(sink, left + width / 5, y, clip(value, room), Panels.INK, scale)
            y += step
        }

        y += step / 2
        PixelIcons.TERMINAL.draw(sink, left, y, maxOf(1, scale))
        PixelFont.draw(
            sink,
            left + PixelIcons.TERMINAL.width * maxOf(1, scale) + width / 60,
            y + PixelFont.HEIGHT * scale / 4,
            "A  OPEN SHELL",
            Panels.INK,
            scale,
        )
    }

    private fun onOff(value: Boolean): String = if (value) "ON" else "OFF"

    private fun clip(text: String, room: Int): String {
        if (room <= 0) return ""
        if (text.length <= room) return text
        return text.take(maxOf(0, room - 1)) + "."
    }


    private const val VOLUME_STEP = 5
    private const val STRIPE_WIDTH = 28
    private const val STRIPE_MILLIS = 90UL
}
