package frontend

import kapi.Audio
import kapi.Console
import kapi.Files
import kapi.Gamepad
import kapi.Graphics
import kapi.Input
import kapi.Keys
import kapi.Pad
import kapi.Status
import kapi.Surface
import kapi.Sys
import kapi.Time
import kapi.emu.Emulator
import kapi.ui.Panels
import kapi.ui.PixelFont
import kapi.ui.PixelIcons
import kapi.ui.PixelSink
import kapi.ui.SurfaceSink
import shell.CommandRegistry
import shell.Shell

object Home {
    private const val TAB_LIBRARY = 0
    private const val TAB_SETTINGS = 1
    private const val TAB_SYSTEM = 2

    private val tabs = listOf("LIBRARY", "SETTINGS", "SYSTEM")

    private const val SETTING_VOLUME = 0
    private const val SETTING_MUTE = 1
    private const val SETTING_ZONE = 2
    private const val SETTING_DST = 3
    private const val SETTING_FPS = 4
    private const val SETTING_COUNT = 5

    private val padPrevious = BooleanArray(Pad.COUNT)

    private var tab = TAB_LIBRARY
    private var selected = 0
    private var setting = 0
    private var scroll = 0

    fun run(registry: CommandRegistry): Nothing {
        Settings.syncFromSystem()
        Settings.load()

        while (true) {
            val surface = Graphics.surface()
            if (surface == null) {
                Shell.run(registry)
                continue
            }

            Status.context = null

            var games = GameLibrary.scan()
            clampSelection(games.size)

            val sink = SurfaceSink(surface)
            flushInput()

            var clock = ""
            var dirty = true

            while (true) {
                Input.poll()

                val tick = clockText()
                if (dirty || tick != clock) {
                    clock = tick
                    render(surface, sink, games, clock)
                    dirty = false
                }

                when (val action = read()) {
                    Action.NONE -> Time.idle()

                    Action.UP -> {
                        move(-1, games.size)
                        dirty = true
                    }

                    Action.DOWN -> {
                        move(1, games.size)
                        dirty = true
                    }

                    Action.LEFT, Action.RIGHT -> {
                        val delta = if (action == Action.LEFT) -1 else 1
                        if (tab == TAB_SETTINGS) adjust(delta) else switchTab(delta)
                        dirty = true
                    }

                    Action.PREV_TAB -> {
                        switchTab(-1)
                        dirty = true
                    }

                    Action.NEXT_TAB -> {
                        switchTab(1)
                        dirty = true
                    }

                    Action.SELECT -> {
                        if (tab == TAB_SETTINGS) {
                            adjust(1)
                            dirty = true
                        } else if (tab == TAB_SYSTEM) {
                            openShell(registry)
                            break
                        } else if (games.isNotEmpty()) {
                            saveSettings()
                            play(surface, games[selected])
                            Settings.syncFromSystem()
                            Settings.flush()
                            games = GameLibrary.scan()
                            clampSelection(games.size)
                            break
                        }
                    }

                    Action.LOUDER, Action.QUIETER -> {
                        val step = if (action == Action.LOUDER) VOLUME_STEP else -VOLUME_STEP
                        Settings.setVolume(Settings.volume + step)
                        Audio.showVolume()
                        dirty = true
                    }

                    Action.REFRESH -> {
                        games = GameLibrary.scan()
                        clampSelection(games.size)
                        dirty = true
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

    private fun openShell(registry: CommandRegistry) {
        saveSettings()
        Console.clear()
        Shell.run(registry)
        Console.clear()
    }

    private fun play(surface: Surface, game: Game) {
        Status.context = game.name.uppercase()
        Console.clear()

        val failure = Player.play(surface, game)

        Console.clear()
        Status.context = null

        if (failure != null) Sys.toast(game.name.uppercase(), failure)
    }

    private fun saveSettings() {
        Settings.syncFromSystem()

        if (Settings.flush() == false) {
            Sys.toast("SETTINGS NOT SAVED", "CANNOT WRITE ${Settings.PATH}")
        }
    }

    private fun move(delta: Int, count: Int) {
        if (tab == TAB_SETTINGS) {
            setting = (setting + SETTING_COUNT + delta) % SETTING_COUNT
            return
        }

        if (tab != TAB_LIBRARY || count == 0) return
        selected = (selected + count + delta) % count
    }

    private fun switchTab(delta: Int) {
        if (tab == TAB_SETTINGS) saveSettings()
        tab = (tab + tabs.size + delta) % tabs.size
    }

    private fun clampSelection(count: Int) {
        if (count == 0) {
            selected = 0
            scroll = 0
            return
        }

        if (selected >= count) selected = count - 1
    }

    private fun adjust(delta: Int) {
        when (setting) {
            SETTING_VOLUME -> Settings.setVolume(Settings.volume + delta * 5)
            SETTING_MUTE -> Settings.setMuted(!Settings.muted)
            SETTING_ZONE -> Settings.setZoneOffset(Settings.zoneOffsetMinutes + delta * Settings.OFFSET_STEP)
            SETTING_DST -> Settings.setDaylightSaving(!Settings.daylightSaving)
            SETTING_FPS -> Settings.setShowFps(!Settings.showFps)
        }
    }

    private enum class Action { NONE, UP, DOWN, LEFT, RIGHT, PREV_TAB, NEXT_TAB, SELECT, REFRESH, SHELL, LOUDER, QUIETER }

    private fun read(): Action {
        if (Input.consumePress(Keys.UP)) return Action.UP
        if (Input.consumePress(Keys.DOWN)) return Action.DOWN
        if (Input.consumePress(Keys.LEFT)) return Action.LEFT
        if (Input.consumePress(Keys.RIGHT)) return Action.RIGHT
        if (Input.consumePress(Keys.Q)) return Action.PREV_TAB
        if (Input.consumePress(Keys.E)) return Action.NEXT_TAB
        if (Input.consumePress(Keys.ENTER)) return Action.SELECT
        if (Input.consumePress(Keys.SPACE)) return Action.SELECT
        if (Input.consumePress(Keys.R)) return Action.REFRESH
        if (Input.consumePress(Keys.F1)) return Action.SHELL
        if (Input.consumePress(Keys.ESC)) return Action.SHELL

        val pad = padAction()
        if (pad != Action.NONE) return pad

        val character = Console.tryReadChar() ?: return Action.NONE
        return when (character.lowercaseChar()) {
            'w', 'k' -> Action.UP
            's', 'j' -> Action.DOWN
            'a', 'h' -> Action.LEFT
            'd', 'l' -> Action.RIGHT
            '\n', '\r', ' ' -> Action.SELECT
            else -> Action.NONE
        }
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
                    Pad.DPAD_LEFT -> Action.LEFT
                    Pad.DPAD_RIGHT -> Action.RIGHT
                    Pad.L -> Action.PREV_TAB
                    Pad.R -> Action.NEXT_TAB
                    Pad.LT -> Action.QUIETER
                    Pad.RT -> Action.LOUDER
                    Pad.A, Pad.START -> Action.SELECT
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

    private fun render(surface: Surface, sink: PixelSink, games: List<Game>, clock: String) {
        val width = surface.width.toInt()
        val height = surface.height.toInt()

        surface.clear(BACKGROUND)

        drawHeader(sink, width, games, clock)
        drawTabs(sink, width)

        val top = CONTENT_Y
        val bottom = height - MARGIN

        when (tab) {
            TAB_LIBRARY -> drawLibrary(sink, width, top, bottom, games)
            TAB_SETTINGS -> drawSettings(sink, width, top, bottom)
            else -> drawSystem(sink, width, top, bottom)
        }

        surface.present()
    }

    private fun drawHeader(sink: PixelSink, width: Int, games: List<Game>, clock: String) {
        PixelFont.draw(sink, MARGIN, 24, "KURTOS", Panels.GOLD, 4, Panels.OUTLINE)

        val systems = games.map { it.emulator.id }.distinct().size
        val line = if (games.isEmpty()) {
            "NO GAMES INSTALLED"
        } else {
            "${games.size} ${plural(games.size, "GAME")}   $systems ${plural(systems, "SYSTEM")}"
        }
        PixelFont.draw(sink, MARGIN, 72, line, Panels.SUBTITLE, 2, Panels.OUTLINE)

        val clockX = width - MARGIN - PixelFont.textWidth(clock, 3)
        PixelFont.draw(sink, clockX, 24, clock, Panels.TITLE, 3, Panels.OUTLINE)

        val date = dateText()
        val dateX = width - MARGIN - PixelFont.textWidth(date, 2)
        PixelFont.draw(sink, dateX, 72, date, Panels.DIM, 2, Panels.OUTLINE)

        sink.fill(MARGIN, 100, width - MARGIN * 2, 2, Panels.SLOT)
    }

    private fun drawTabs(sink: PixelSink, width: Int) {
        var x = MARGIN

        for ((index, name) in tabs.withIndex()) {
            val label = name
            val tabWidth = PixelFont.textWidth(label, 2) + 48
            val active = index == tab

            if (active) {
                sink.fill(x, TABS_Y, tabWidth, TAB_HEIGHT, Panels.GOLD)
                sink.fill(x, TABS_Y + TAB_HEIGHT - 4, tabWidth, 4, Panels.GOLD_DARK)
                PixelFont.draw(sink, x + 24, TABS_Y + 14, label, Panels.OUTLINE, 2)
            } else {
                sink.fill(x, TABS_Y, tabWidth, TAB_HEIGHT, PANEL)
                PixelFont.draw(sink, x + 24, TABS_Y + 14, label, Panels.DIM, 2, Panels.OUTLINE)
            }

            x += tabWidth + 8
        }
    }

    private fun drawLibrary(sink: PixelSink, width: Int, top: Int, bottom: Int, games: List<Game>) {
        val available = width - MARGIN * 2
        val listWidth = available * 58 / 100
        val detailX = MARGIN + listWidth + 24
        val detailWidth = width - MARGIN - detailX

        sink.fill(MARGIN, top, listWidth, bottom - top, PANEL)

        if (games.isEmpty()) {
            PixelIcons.QUESTION_BLOCK.draw(sink, MARGIN + 32, top + 32, 3)
            PixelFont.draw(sink, MARGIN + 32, top + 116, "NO GAMES FOUND", Panels.TITLE, 2, Panels.OUTLINE)
            PixelFont.draw(
                sink,
                MARGIN + 32,
                top + 148,
                "COPY ROMS TO ${GameLibrary.DIRECTORY.uppercase()}",
                Panels.DIM,
                2,
                Panels.OUTLINE,
            )
            drawDetailPanel(sink, detailX, top, detailWidth, bottom - top, null)
            return
        }

        val capacity = maxOf(1, (bottom - top - PADDING * 2) / (ROW_HEIGHT + ROW_GAP))
        if (selected < scroll) scroll = selected
        if (selected >= scroll + capacity) scroll = selected - capacity + 1
        if (scroll > maxOf(0, games.size - capacity)) scroll = maxOf(0, games.size - capacity)

        for (slot in 0 until minOf(capacity, games.size - scroll)) {
            val index = scroll + slot
            val game = games[index]
            val rowY = top + PADDING + slot * (ROW_HEIGHT + ROW_GAP)
            val rowX = MARGIN + PADDING
            val rowWidth = listWidth - PADDING * 2
            val active = index == selected

            if (active) {
                sink.fill(rowX, rowY, rowWidth, ROW_HEIGHT, Panels.GOLD)
                sink.fill(rowX + 3, rowY + 3, rowWidth - 6, ROW_HEIGHT - 6, ROW_SELECTED)
                sink.fill(rowX + 3, rowY + 3, 6, ROW_HEIGHT - 6, Panels.GOLD)
            } else {
                sink.fill(rowX, rowY, rowWidth, ROW_HEIGHT, ROW_FILL)
            }

            val icon = iconFor(game.emulator)
            icon.draw(sink, rowX + 22, rowY + (ROW_HEIGHT - icon.height * 2) / 2, 2)

            val textX = rowX + 22 + icon.width * 2 + 18
            val tag = game.emulator.system.uppercase()
            val tagWidth = PixelFont.textWidth(tag, 2)
            val room = (rowX + rowWidth - 24 - tagWidth - 24 - textX) / (PixelFont.WIDTH * 2)

            val color = if (active) Panels.TITLE else Panels.SUBTITLE
            PixelFont.draw(sink, textX, rowY + 18, clip(game.name.uppercase(), room), color, 2, Panels.OUTLINE)

            val tagX = rowX + rowWidth - 24 - tagWidth
            PixelFont.draw(sink, tagX, rowY + 18, tag, if (active) Panels.GOLD else Panels.DIM, 2, Panels.OUTLINE)
        }

        if (games.size > capacity) {
            val trackX = MARGIN + listWidth - 10
            val trackY = top + PADDING
            val trackHeight = bottom - top - PADDING * 2

            sink.fill(trackX, trackY, 4, trackHeight, Panels.SLOT)

            val thumb = maxOf(24, trackHeight * capacity / games.size)
            val span = trackHeight - thumb
            val offset = if (games.size == capacity) 0 else span * scroll / (games.size - capacity)
            sink.fill(trackX, trackY + offset, 4, thumb, Panels.GOLD)
        }

        drawDetailPanel(sink, detailX, top, detailWidth, bottom - top, games[selected])
    }

    private fun drawDetailPanel(sink: PixelSink, x: Int, y: Int, width: Int, height: Int, game: Game?) {
        sink.fill(x, y, width, height, PANEL)
        sink.fill(x, y, width, 3, Panels.SLOT)

        val inner = x + 24
        val room = (width - 48) / (PixelFont.WIDTH * 2)

        if (game == null) {
            PixelFont.draw(sink, inner, y + 32, "NOTHING TO PLAY", Panels.DIM, 2, Panels.OUTLINE)
            return
        }

        val artHeight = minOf(height / 3, 300)
        sink.fill(inner, y + 24, width - 48, artHeight, ART)

        val icon = iconFor(game.emulator)
        val iconScale = maxOf(4, minOf(10, (artHeight - 64) / icon.height))
        val iconX = x + (width - icon.width * iconScale) / 2
        val iconY = y + 24 + (artHeight - icon.height * iconScale) / 2
        icon.draw(sink, iconX, iconY, iconScale)

        var textY = y + 24 + artHeight + 32

        PixelFont.draw(sink, inner, textY, clip(game.name.uppercase(), room), Panels.TITLE, 2, Panels.OUTLINE)
        textY += 40

        sink.fill(inner, textY, width - 48, 2, Panels.SLOT)
        textY += 24

        val saved = Files.read(GameLibrary.savePath(game), 1u) != null

        textY = detailRow(sink, inner, textY, width, "SYSTEM", game.emulator.system.uppercase())
        textY = detailRow(sink, inner, textY, width, "SIZE", "${game.size / 1024UL} KIB")
        detailRow(sink, inner, textY, width, "SAVE", if (saved) "PRESENT" else "NONE")
    }

    private fun detailRow(sink: PixelSink, x: Int, y: Int, width: Int, label: String, value: String): Int {
        PixelFont.draw(sink, x, y, label, Panels.DIM, 2, Panels.OUTLINE)

        val valueX = x + width - 48 - PixelFont.textWidth(value, 2)
        PixelFont.draw(sink, valueX, y, value, Panels.SUBTITLE, 2, Panels.OUTLINE)

        return y + 32
    }

    private fun drawSettings(sink: PixelSink, width: Int, top: Int, bottom: Int) {
        val panelWidth = width - MARGIN * 2
        val panelHeight = minOf(
            bottom - top,
            PADDING * 2 + SETTING_COUNT * (SETTING_HEIGHT + ROW_GAP) + 44,
        )
        sink.fill(MARGIN, top, panelWidth, panelHeight, PANEL)

        val rows = listOf(
            Triple("VOLUME", "${Settings.volume}%", "HOW LOUD EMULATED SOUND PLAYS"),
            Triple("MUTE", onOff(Settings.muted), "SILENCE ALL AUDIO OUTPUT"),
            Triple("TIME ZONE", Settings.zoneLabel(), "THE CMOS CLOCK KEEPS UTC - THIS SHIFTS THE DISPLAYED TIME"),
            Triple("DAYLIGHT SAVING", if (Settings.daylightSaving) "AUTO (EU)" else "OFF", "ADDS AN HOUR BETWEEN LATE MARCH AND LATE OCTOBER"),
            Triple("FPS OVERLAY", onOff(Settings.showFps), "SHOWS A LIVE FRAME RATE WHILE A GAME RUNS"),
        )

        for ((index, row) in rows.withIndex()) {
            val rowY = top + PADDING + index * (SETTING_HEIGHT + ROW_GAP)
            val rowX = MARGIN + PADDING
            val rowWidth = panelWidth - PADDING * 2
            val active = index == setting

            if (active) {
                sink.fill(rowX, rowY, rowWidth, SETTING_HEIGHT, Panels.GOLD)
                sink.fill(rowX + 3, rowY + 3, rowWidth - 6, SETTING_HEIGHT - 6, ROW_SELECTED)
                sink.fill(rowX + 3, rowY + 3, 6, SETTING_HEIGHT - 6, Panels.GOLD)
            } else {
                sink.fill(rowX, rowY, rowWidth, SETTING_HEIGHT, ROW_FILL)
            }

            val labelColor = if (active) Panels.TITLE else Panels.SUBTITLE
            PixelFont.draw(sink, rowX + 24, rowY + 14, row.first, labelColor, 2, Panels.OUTLINE)
            PixelFont.draw(sink, rowX + 24, rowY + 44, row.third, Panels.DIM, 1, Panels.OUTLINE)

            val valueX = rowX + rowWidth - 24 - PixelFont.textWidth(row.second, 2)

            if (index == SETTING_VOLUME) {
                drawMeter(sink, rowX + rowWidth - 24 - METER_WIDTH - 90, rowY + 22, Settings.volume)
            }

            PixelFont.draw(sink, valueX, rowY + 22, row.second, if (active) Panels.GOLD else Panels.SUBTITLE, 2, Panels.OUTLINE)
        }

        val hintY = top + PADDING + SETTING_COUNT * (SETTING_HEIGHT + ROW_GAP) + 20
        PixelFont.draw(
            sink,
            MARGIN + PADDING,
            hintY,
            "CHANGES ARE WRITTEN TO ${Settings.PATH.uppercase()} ON THE DATA DISK",
            Panels.DIM,
            2,
            Panels.OUTLINE,
        )
    }

    private fun drawMeter(sink: PixelSink, x: Int, y: Int, percent: Int) {
        val filled = (percent + 9) / 10

        for (i in 0 until 10) {
            val cellX = x + i * (METER_CELL + 4)
            val color = if (i < filled && !Settings.muted) Panels.GOLD else Panels.SLOT
            sink.fill(cellX, y, METER_CELL, 16, color)
        }
    }

    private fun drawSystem(sink: PixelSink, width: Int, top: Int, bottom: Int) {
        val panelWidth = width - MARGIN * 2
        val panelHeight = minOf(bottom - top, PADDING * 2 + 5 * 40 + 80)
        sink.fill(MARGIN, top, panelWidth, panelHeight, PANEL)

        val rows = listOf(
            "DISPLAY" to Graphics.status().uppercase(),
            "AUDIO" to Audio.status().uppercase(),
            "GAMEPAD" to Gamepad.status().uppercase(),
            "STORAGE" to Files.status().uppercase(),
            "MEMORY" to Sys.memoryReport().uppercase(),
        )

        var y = top + PADDING + 8
        val room = (panelWidth - PADDING * 2 - 260) / (PixelFont.WIDTH * 2)

        for ((label, value) in rows) {
            PixelFont.draw(sink, MARGIN + PADDING, y, label, Panels.DIM, 2, Panels.OUTLINE)
            PixelFont.draw(sink, MARGIN + PADDING + 260, y, clip(value, room), Panels.SUBTITLE, 2, Panels.OUTLINE)
            y += 40
        }

        y += 16
        PixelIcons.TERMINAL.draw(sink, MARGIN + PADDING, y, 2)
        PixelFont.draw(sink, MARGIN + PADDING + 56, y + 8, "ENTER  OPEN SHELL", Panels.GOLD, 2, Panels.OUTLINE)

        y = top + panelHeight + 28
        if (y + 120 > bottom) return

        PixelIcons.GAMEPAD.draw(sink, MARGIN, y, 2)
        PixelFont.draw(sink, MARGIN + 56, y + 8, "CONTROLLER", Panels.SUBTITLE, 2, Panels.OUTLINE)
        y += 48

        val binds = listOf(
            "START + SELECT" to "LEAVE THE GAME",
            "RT / LT" to "VOLUME UP / DOWN",
            "L / R" to "SWITCH TABS IN THE LAUNCHER",
        )

        for ((bind, meaning) in binds) {
            PixelFont.draw(sink, MARGIN + 16, y, bind, Panels.GOLD, 2, Panels.OUTLINE)
            PixelFont.draw(sink, MARGIN + 16 + 320, y, meaning, Panels.DIM, 2, Panels.OUTLINE)
            y += 32
        }
    }

    private fun clockText(): String {
        val now = Time.now() ?: return uptimeText()
        return "${pad(now.hour)}:${pad(now.minute)}"
    }

    private fun dateText(): String {
        val now = Time.now() ?: return "NO CLOCK"
        return "${now.year}-${pad(now.month)}-${pad(now.day)}"
    }

    private fun uptimeText(): String {
        val seconds = (Time.uptimeMillis() / 1000UL).toInt()
        return "${pad(seconds / 3600)}:${pad((seconds / 60) % 60)}"
    }

    private fun pad(value: Int): String = value.toString().padStart(2, '0')

    private fun onOff(value: Boolean): String = if (value) "ON" else "OFF"

    private fun plural(count: Int, word: String): String = if (count == 1) word else "${word}S"

    private fun clip(text: String, room: Int): String {
        if (room <= 0) return ""
        if (text.length <= room) return text
        return text.take(maxOf(0, room - 1)) + "."
    }

    private fun iconFor(emulator: Emulator): PixelIcons.Icon = when (emulator.id) {
        "gba" -> PixelIcons.CARTRIDGE_GBA
        else -> PixelIcons.CARTRIDGE
    }

    private const val BACKGROUND: UInt = 0x000B0F14u
    private const val PANEL: UInt = 0x00121A26u
    private const val ROW_FILL: UInt = 0x00182231u
    private const val ROW_SELECTED: UInt = 0x00243349u
    private const val ART: UInt = 0x000E1520u

    private const val MARGIN = 40
    private const val PADDING = 16
    private const val TABS_Y = 116
    private const val TAB_HEIGHT = 46
    private const val CONTENT_Y = 182
    private const val ROW_HEIGHT = 56
    private const val ROW_GAP = 8
    private const val SETTING_HEIGHT = 72
    private const val VOLUME_STEP = 5
    private const val METER_CELL = 12
    private const val METER_WIDTH = 10 * (METER_CELL + 4)
}
