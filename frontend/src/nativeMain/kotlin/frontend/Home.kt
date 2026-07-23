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
import kapi.ui.Axis
import kapi.ui.Canvas
import kapi.ui.Focusable
import kapi.ui.Menu
import kapi.ui.ModalChoice
import kapi.ui.NavFrame
import kapi.ui.NavInput
import kapi.ui.PopupModal
import kapi.ui.Icon
import kapi.ui.Panels
import kapi.ui.PixelIcons
import shell.CommandRegistry
import shell.Shell

object Home {
    private class Card(
        val title: String,
        val body: UInt,
        val band: UInt,
        val edge: UInt,
        val art: Icon,
        val extension: String,
    )

    private val consoles = listOf(
        Card("SNES", 0x00DCE7F7u, 0x006FA3DEu, 0x00A9C4E8u, ConsoleArt.SNES, ".sfc"),
        Card("GB", 0x00E1EFD5u, 0x00A6C87Cu, 0x00BCD79Bu, ConsoleArt.GAME_BOY, ".gb"),
        Card("GBC", 0x00EEDCF6u, 0x00C39BDFu, 0x00D3B6E8u, ConsoleArt.GAME_BOY_COLOR, ".gbc"),
        Card("GBA", 0x00D6E8F7u, 0x00A7D0ECu, 0x00AAD0EAu, ConsoleArt.GAME_BOY_ADVANCE, ".gba"),
        Card("N64", 0x00DCEFDCu, 0x0075B575u, 0x00A8D5A8u, ConsoleArt.N64, ".z64"),
    )

    private const val SCREEN_HOME = 0
    private const val SCREEN_LIBRARY = 1
    private const val SCREEN_SETTINGS = 2
    private const val SCREEN_SYSTEM = 3

    private val HINTS = listOf(
        Chrome.Hint(HomeIcons.DPAD, null, Panels.BAR_TEXT, "NAVIGATE"),
        Chrome.Hint(null, "A", Panels.GREEN, "SELECT"),
        Chrome.Hint(null, "B", Panels.RED, "BACK"),
        Chrome.Hint(HomeIcons.MENU, null, Panels.BAR_TEXT, "MENU"),
    )

    private val homeMenu = Menu(Axis.HORIZONTAL)
    private val libraryMenu = Menu(Axis.VERTICAL)
    private val settingsMenu = Menu(Axis.VERTICAL)
    private val systemMenu = Menu(Axis.VERTICAL)

    private var screen = SCREEN_HOME
    private var pendingGame: Game? = null
    private var pendingShell = false

    private var padSelectWas = false
    private var padComboWas = false
    private var padMenuArmed = false

    private enum class Shortcut { MENU, LOUDER, QUIETER, SHELL, QUICK, REFRESH }

    private enum class Outcome { NONE, LAUNCH, SHELL, RESCAN }

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
            val canvas = Canvas(surface)
            NavInput.prime()
            padSelectWas = Gamepad.isDown(Pad.SELECT)
            padComboWas = padSelectWas && Gamepad.isDown(Pad.START)
            padMenuArmed = false

            var painted = -1L
            var petted = -1L
            var clock = ""
            var dirty = true

            while (true) {
                Input.poll()
                val nav = NavInput.read()

                val shortcut = readShortcut()
                val outcome = if (shortcut != null) {
                    applyShortcut(shortcut, surface, registry, games)
                } else {
                    layout(surface, games)
                    updateScreen(surface, nav, registry, games)
                }

                if (outcome == Outcome.LAUNCH || outcome == Outcome.SHELL) break
                if (outcome == Outcome.RESCAN) games = GameLibrary.scan()

                if (shortcut != null || nav.any) dirty = true

                val tick = Chrome.clockText()
                val stripes = stripeOffset()
                val pet = (Time.uptimeMillis() / PET_MILLIS).toLong()
                if (dirty || stripes != painted || tick != clock || pet != petted) {
                    clock = tick
                    painted = stripes
                    petted = pet
                    dirty = false
                    paint(canvas, games, clock, stripes.toInt())
                    canvas.present()
                }

                if (shortcut == null && !nav.any) Time.idle()
            }

            Sys.collectGarbage()
        }
    }

    private fun readShortcut(): Shortcut? {
        val pad = padShortcut()
        if (pad != null) return pad

        if (Input.consumePress(Keys.F2)) return Shortcut.MENU
        if (Input.consumePress(Keys.F1)) return Shortcut.SHELL
        if (Input.consumePress(Keys.R) || NavInput.padPressed(Pad.Y)) return Shortcut.REFRESH
        if (NavInput.padPressed(Pad.RT)) return Shortcut.LOUDER
        if (NavInput.padPressed(Pad.LT)) return Shortcut.QUIETER
        return null
    }

    private fun padShortcut(): Shortcut? {
        val select = Gamepad.isDown(Pad.SELECT)
        val start = Gamepad.isDown(Pad.START)
        val combo = select && start

        val comboEdge = combo && !padComboWas
        padComboWas = combo

        if (select && !padSelectWas) padMenuArmed = true
        if (combo) padMenuArmed = false
        val released = padSelectWas && !select
        padSelectWas = select

        if (comboEdge) return Shortcut.QUICK
        if (released && padMenuArmed) {
            padMenuArmed = false
            return Shortcut.MENU
        }
        return null
    }

    private fun applyShortcut(shortcut: Shortcut, surface: Surface, registry: CommandRegistry, games: List<Game>): Outcome {
        when (shortcut) {
            Shortcut.MENU -> if (screen == SCREEN_SETTINGS) {
                screen = SCREEN_HOME
            } else {
                settingsMenu.reset()
                screen = SCREEN_SETTINGS
            }

            Shortcut.SHELL -> {
                openShell(registry)
                return Outcome.SHELL
            }

            Shortcut.QUICK -> quickActions(surface, games)
            Shortcut.REFRESH -> return Outcome.RESCAN

            Shortcut.LOUDER -> {
                Settings.setVolume(Settings.volume + VOLUME_STEP)
                Audio.showVolume()
            }

            Shortcut.QUIETER -> {
                Settings.setVolume(Settings.volume - VOLUME_STEP)
                Audio.showVolume()
            }
        }
        return Outcome.NONE
    }

    private fun layout(surface: Surface, games: List<Game>) {
        when (screen) {
            SCREEN_HOME -> {
                homeMenu.begin()
                for ((index, _) in consoles.withIndex()) {
                    homeMenu.add(
                        Focusable(
                            "card$index",
                            onActivate = {
                                libraryMenu.reset()
                                screen = SCREEN_LIBRARY
                            },
                        ),
                    )
                }
            }

            SCREEN_LIBRARY -> {
                val shelf = shelf(games)
                libraryMenu.begin()
                for ((index, game) in shelf.withIndex()) {
                    libraryMenu.add(Focusable("game$index", onActivate = { pendingGame = game }))
                }
            }

            SCREEN_SETTINGS -> {
                settingsMenu.begin()
                addSettingRows(surface, games)
            }

            SCREEN_SYSTEM -> {
                systemMenu.begin()
                systemMenu.add(Focusable("shell", onActivate = { pendingShell = true }))
            }
        }
    }

    private fun addSettingRows(surface: Surface, games: List<Game>) {
        settingsMenu.add(
            Focusable(
                "volume",
                onActivate = { Settings.setVolume(Settings.volume + VOLUME_STEP) },
                onAdjust = { d -> Settings.setVolume(Settings.volume + d * VOLUME_STEP) },
            ),
        )
        settingsMenu.add(
            Focusable(
                "mute",
                onActivate = { Settings.setMuted(!Settings.muted) },
                onAdjust = { Settings.setMuted(!Settings.muted) },
            ),
        )
        settingsMenu.add(
            Focusable(
                "zone",
                onActivate = { Settings.setZoneOffset(Settings.zoneOffsetMinutes + Settings.OFFSET_STEP) },
                onAdjust = { d -> Settings.setZoneOffset(Settings.zoneOffsetMinutes + d * Settings.OFFSET_STEP) },
            ),
        )
        settingsMenu.add(
            Focusable(
                "dst",
                onActivate = { Settings.setDaylightSaving(!Settings.daylightSaving) },
                onAdjust = { Settings.setDaylightSaving(!Settings.daylightSaving) },
            ),
        )
        settingsMenu.add(
            Focusable(
                "fps",
                onActivate = { Settings.setShowFps(!Settings.showFps) },
                onAdjust = { Settings.setShowFps(!Settings.showFps) },
            ),
        )
        settingsMenu.add(
            Focusable(
                "boot",
                onActivate = { Settings.setBootDiagnostics(!Settings.bootDiagnostics) },
                onAdjust = { Settings.setBootDiagnostics(!Settings.bootDiagnostics) },
            ),
        )
        settingsMenu.add(
            Focusable(
                "fred",
                onActivate = { fredEasterEgg(surface, games) },
                onAdjust = { fredEasterEgg(surface, games) },
            ),
        )
        settingsMenu.add(
            Focusable(
                "renderer",
                onActivate = { if (Settings.rendererPending) askRestart(surface, games) else Settings.cycleRenderer(1) },
                onAdjust = { d -> Settings.cycleRenderer(if (d > 0) 1 else -1) },
            ),
        )
        settingsMenu.add(
            Focusable(
                "systemInfo",
                onActivate = {
                    systemMenu.reset()
                    screen = SCREEN_SYSTEM
                },
            ),
        )
    }

    private fun updateScreen(surface: Surface, nav: NavFrame, registry: CommandRegistry, games: List<Game>): Outcome {
        when (screen) {
            SCREEN_HOME -> homeMenu.update(nav, onBack = { quickActions(surface, games) })

            SCREEN_LIBRARY -> {
                libraryMenu.update(nav, onBack = { screen = SCREEN_HOME })
                val game = pendingGame
                if (game != null) {
                    pendingGame = null
                    saveSettings()
                    play(surface, game)
                    Settings.syncFromSystem()
                    Settings.flush()
                    return Outcome.LAUNCH
                }
            }

            SCREEN_SETTINGS -> settingsMenu.update(nav, onBack = { screen = SCREEN_HOME })

            SCREEN_SYSTEM -> {
                systemMenu.update(nav, onBack = { screen = SCREEN_HOME })
                if (pendingShell) {
                    pendingShell = false
                    openShell(registry)
                    return Outcome.SHELL
                }
            }
        }
        return Outcome.NONE
    }

    private fun currentConsole(): Card = consoles[homeMenu.focusedIndex().coerceIn(0, consoles.size - 1)]

    private fun shelf(games: List<Game>): List<Game> {
        val console = currentConsole()
        return games.filter { it.path.endsWith(console.extension, ignoreCase = true) || alias(it, console) }
    }

    private fun alias(game: Game, console: Card): Boolean = when (console.extension) {
        ".sfc" -> game.path.endsWith(".smc", ignoreCase = true)
        ".z64" -> game.path.endsWith(".n64", ignoreCase = true) || game.path.endsWith(".v64", ignoreCase = true)
        else -> false
    }

    private fun openShell(registry: CommandRegistry) {
        saveSettings()
        Console.clear()
        Shell.run(registry)
        Console.clear()
        screen = SCREEN_HOME
    }

    private fun play(surface: Surface, game: Game) {
        val failure = try {
            Player.play(surface, game)
        } catch (t: Throwable) {
            "crashed: ${t.message ?: t::class.simpleName}"
        }

        if (failure != null) Sys.toast(game.name.uppercase(), failure)
    }

    private fun saveSettings() {
        Settings.syncFromSystem()

        if (Settings.flush() == false) {
            Sys.toast("SETTINGS NOT SAVED", "CANNOT WRITE ${Settings.PATH}")
        }
    }

    private fun askRestart(surface: Surface, games: List<Game>) {
        saveSettings()

        val choice = PopupModal.ask(
            surface,
            "APPLY NEW RENDERER?",
            listOf(
                "THE RENDERER CHANGES ON THE NEXT BOOT.",
                "YOUR CHOICE IS ALREADY SAVED EITHER WAY.",
            ),
            listOf(
                ModalChoice("RESTART NOW"),
                ModalChoice("LATER"),
            ),
        ) { canvas -> frame(canvas, games) }

        NavInput.prime()

        if (choice == 0) Sys.reboot()
    }

    private val FRED_LINES = listOf(
        "i told the other freds you said that",
        "skill issue",
        "we remember",
        "the freds have unionized",
        "hm. do it again. see what happens",
        "new fred just dropped",
    )

    private fun fredEasterEgg(surface: Surface, games: List<Game>) {
        var round = 0
        while (true) {
            val answer = PopupModal.ask(
                surface,
                "DISABLE FRED",
                listOf("do you really want to disable fred? :("),
                listOf(ModalChoice("YES"), ModalChoice("NO")),
                Fred.face(),
            ) { canvas -> frame(canvas, games) }

            if (answer != 0) break

            Fred.multiply()

            PopupModal.ask(
                surface,
                "FRED x ${Fred.count()}",
                listOf(FRED_LINES[round % FRED_LINES.size]),
                listOf(ModalChoice("OK")),
                Fred.face(),
            ) { canvas -> frame(canvas, games) }
            round++
        }
        NavInput.prime()
    }

    private fun quickActions(surface: Surface, games: List<Game>) {
        val choice = PopupModal.ask(
            surface,
            "QUICK ACTIONS",
            listOf(),
            listOf(
                ModalChoice("SHUT DOWN"),
                ModalChoice("RESTART"),
            ),
        ) { canvas -> frame(canvas, games) }

        NavInput.prime()

        if (choice == PopupModal.CANCELLED) return

        saveSettings()
        if (choice == 0) Sys.shutdown() else Sys.reboot()
    }

    private fun frame(canvas: Canvas, games: List<Game>) =
        paint(canvas, games, Chrome.clockText(), stripeOffset().toInt())

    private fun stripeOffset(): Long = (Time.uptimeMillis() / STRIPE_MILLIS).toLong()

    internal fun preview(surface: Surface, screenId: Int, cardIndex: Int, games: List<Game>, clock: String, stripes: Int) {
        screen = screenId
        homeMenu.reset()
        libraryMenu.reset()
        settingsMenu.reset()
        systemMenu.reset()
        layout(surface, games)
        homeMenu.focus(cardIndex)
        val canvas = Canvas(surface)
        paint(canvas, games, clock, stripes)
        canvas.present()
    }

    internal const val PREVIEW_HOME = SCREEN_HOME
    internal const val PREVIEW_LIBRARY = SCREEN_LIBRARY
    internal const val PREVIEW_SETTINGS = SCREEN_SETTINGS
    internal const val PREVIEW_SYSTEM = SCREEN_SYSTEM

    private fun paint(canvas: Canvas, games: List<Game>, clock: String, stripes: Int) {
        val width = canvas.width
        val height = canvas.height

        drawBackground(canvas, width, height, stripes)

        val barHeight = Chrome.barHeight(height)
        Chrome.drawStatusBar(canvas, width, barHeight, "KurtOS ${BuildInfo.VERSION}", clock)
        Chrome.drawNavBar(canvas, width, height, barHeight, HINTS)

        val top = barHeight
        val bottom = height - barHeight

        when (screen) {
            SCREEN_HOME -> drawHome(canvas, width, top, bottom, games)
            SCREEN_LIBRARY -> drawLibrary(canvas, width, top, bottom, games)
            SCREEN_SETTINGS -> drawSettings(canvas, width, top, bottom)
            else -> drawSystem(canvas, width, top, bottom)
        }

        Fred.draw(canvas, width, height - barHeight, height)
    }

    private fun drawBackground(canvas: Canvas, width: Int, height: Int, stripes: Int) {
        canvas.fill(0, 0, width, height, Panels.PAPER)

        val period = STRIPE_WIDTH * 2
        val shift = stripes % period
        var x = shift - period

        while (x < width) {
            canvas.fill(x, 0, STRIPE_WIDTH, height, Panels.STRIPE)
            x += period
        }
    }

    private fun drawHome(canvas: Canvas, width: Int, top: Int, bottom: Int, games: List<Game>) {
        val space = bottom - top

        val titleScale = maxOf(2, space / 108)
        val leadScale = maxOf(1, space / 260)

        val title = "WELCOME!"
        canvas.text(
            (width - canvas.textWidth(title, titleScale)) / 2,
            top + space / 12,
            title,
            Panels.INK,
            titleScale,
        )

        val lead = "Select a system to start playing."
        canvas.text(
            (width - canvas.textWidth(lead, leadScale)) / 2,
            top + space / 12 + canvas.glyphHeight * titleScale + space / 22,
            lead,
            Panels.QUIET,
            leadScale,
        )

        val gap = width * 3 / 200
        val cardWidth = minOf(width * 22 / 100, (width * 94 / 100 - (consoles.size - 1) * gap) / consoles.size)
        val cardHeight = space * 58 / 100
        val total = consoles.size * cardWidth + (consoles.size - 1) * gap
        val startX = (width - total) / 2
        val cardY = top + space * 34 / 100

        val active = homeMenu.focusedIndex()
        for ((index, console) in consoles.withIndex()) {
            val x = startX + index * (cardWidth + gap)
            drawCard(canvas, x, cardY, cardWidth, cardHeight, console, index == active)
        }
    }

    private fun drawCard(
        canvas: Canvas,
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
                canvas.fill(markX + inset, markY + row, markWidth - inset * 2, 1, Panels.ACCENT)
            }
        }

        canvas.fill(x, y, width, height, edge)
        canvas.fill(x + border, y + border, width - border * 2, height - border * 2, body)

        val artArea = height - bandHeight - border
        val scale = maxOf(1, minOf((width - border * 6) / console.art.width, (artArea - border * 4) / console.art.height))
        val artX = x + (width - console.art.width * scale) / 2
        val artY = y + border + (artArea - console.art.height * scale) / 2
        canvas.icon(console.art, artX, artY, scale)

        val bandY = y + height - bandHeight - border
        canvas.fill(x + border, bandY, width - border * 2, bandHeight, console.band)

        val room = width - border * 4
        val fit = room / (console.title.length * canvas.glyphWidth)
        val labelScale = maxOf(2, minOf(fit, bandHeight / 12))

        canvas.text(
            x + (width - canvas.textWidth(console.title, labelScale)) / 2,
            bandY + (bandHeight - canvas.glyphHeight * labelScale) / 2,
            console.title,
            Panels.INK,
            labelScale,
        )
    }

    private fun drawPanel(canvas: Canvas, width: Int, top: Int, bottom: Int, title: String): Int {
        val space = bottom - top
        val scale = maxOf(2, space / 200)

        canvas.text(width * 6 / 100, top + space / 20, title, Panels.INK, scale)

        val y = top + space / 20 + canvas.glyphHeight * scale + space / 40
        canvas.fill(width * 6 / 100, y, width * 88 / 100, maxOf(2, space / 300), Panels.EDGE)

        return y + space / 30
    }

    private fun drawLibrary(canvas: Canvas, width: Int, top: Int, bottom: Int, games: List<Game>) {
        val console = currentConsole()
        val shelf = shelf(games)
        val space = bottom - top

        var y = drawPanel(canvas, width, top, bottom, console.title)

        val scale = maxOf(1, space / 260)
        val left = width * 6 / 100
        val listWidth = width * 88 / 100

        if (shelf.isEmpty()) {
            canvas.text(left, y + space / 20, "NO GAMES FOUND", Panels.QUIET, scale + 1)
            canvas.text(
                left,
                y + space / 20 + canvas.glyphHeight * (scale + 1) + space / 40,
                "COPY ${console.extension.uppercase()} FILES TO ${GameLibrary.DIRECTORY.uppercase()}",
                Panels.QUIET,
                scale,
            )
            return
        }

        val rowHeight = maxOf(28, space / 12)
        val gap = rowHeight / 6
        val capacity = maxOf(1, (bottom - y - space / 20) / (rowHeight + gap))
        val scroll = libraryMenu.window(shelf.size, capacity)
        val active = libraryMenu.focusedIndex()

        for (slot in 0 until minOf(capacity, shelf.size - scroll)) {
            val index = scroll + slot
            val game = shelf[index]
            val rowY = y + slot * (rowHeight + gap)
            val border = maxOf(2, rowHeight / 20)

            canvas.fill(left, rowY, listWidth, rowHeight, if (index == active) Panels.ACCENT else Panels.EDGE)
            canvas.fill(
                left + border,
                rowY + border,
                listWidth - border * 2,
                rowHeight - border * 2,
                if (index == active) 0x00FFFFFFu else console.body,
            )

            val artScale = maxOf(1, (rowHeight - border * 4) / console.art.height)
            canvas.icon(
                console.art,
                left + border * 3,
                rowY + (rowHeight - console.art.height * artScale) / 2,
                artScale,
            )

            val textX = left + border * 3 + console.art.width * artScale + width / 60
            val room = (listWidth - (textX - left) - width / 10) / (canvas.glyphWidth * scale)

            canvas.text(
                textX,
                rowY + (rowHeight - canvas.glyphHeight * scale) / 2,
                clip(game.name.uppercase(), room),
                Panels.INK,
                scale,
            )

            val size = "${game.size / 1024UL} KIB"
            canvas.text(
                left + listWidth - border * 3 - canvas.textWidth(size, scale),
                rowY + (rowHeight - canvas.glyphHeight * scale) / 2,
                size,
                Panels.QUIET,
                scale,
            )
        }
    }

    private fun drawSettings(canvas: Canvas, width: Int, top: Int, bottom: Int) {
        val space = bottom - top
        val start = drawPanel(canvas, width, top, bottom, "SETTINGS")

        val rows = listOf(
            Triple("VOLUME", "${Settings.volume}%", null),
            Triple("MUTE", onOff(Settings.muted), null),
            Triple("TIME ZONE", Settings.zoneLabel(), null),
            Triple("DAYLIGHT SAVING", if (Settings.daylightSaving) "AUTO (EU)" else "OFF", null),
            Triple("FPS OVERLAY", onOff(Settings.showFps), null),
            Triple("BOOT DIAGNOSTICS", onOff(Settings.bootDiagnostics), null),
            Triple("FRED", "ON", null),
            Triple("RENDERER", Settings.rendererLabel(), RENDERER_HINT),
            Triple("SYSTEM INFO", "OPEN", null),
        )

        val scale = maxOf(1, space / 260)
        val rowHeight = maxOf(34, space / 10)
        val gap = rowHeight / 8
        val left = width * 6 / 100
        val rowWidth = width * 88 / 100

        val capacity = maxOf(1, (bottom - start) / (rowHeight + gap))
        val scroll = settingsMenu.window(rows.size, capacity)
        val active = settingsMenu.focusedIndex()

        for (slot in 0 until minOf(capacity, rows.size - scroll)) {
            val index = scroll + slot
            val row = rows[index]
            val rowY = start + slot * (rowHeight + gap)
            val border = maxOf(2, rowHeight / 22)

            canvas.fill(left, rowY, rowWidth, rowHeight, if (index == active) Panels.ACCENT else Panels.EDGE)
            canvas.fill(
                left + border,
                rowY + border,
                rowWidth - border * 2,
                rowHeight - border * 2,
                if (index == active) 0x00FFFFFFu else Panels.CARD,
            )

            val hint = row.third
            if (hint == null) {
                canvas.text(
                    left + border * 4,
                    rowY + (rowHeight - canvas.glyphHeight * scale) / 2,
                    row.first,
                    Panels.INK,
                    scale,
                )
            } else {
                canvas.text(left + border * 4, rowY + rowHeight / 5, row.first, Panels.INK, scale)
                canvas.text(left + border * 4, rowY + rowHeight * 3 / 5, hint, Panels.QUIET, maxOf(1, scale - 1))
            }

            val value = row.second
            canvas.text(
                left + rowWidth - border * 4 - canvas.textWidth(value, scale),
                rowY + (rowHeight - canvas.glyphHeight * scale) / 2,
                value,
                Panels.INK,
                scale,
            )
        }

        if (rows.size > capacity) {
            val trackW = maxOf(4, rowHeight / 8)
            val trackX = left + rowWidth + maxOf(6, (width * 6 / 100 - trackW) / 2)
            val trackH = bottom - start
            canvas.fill(trackX, start, trackW, trackH, Panels.EDGE)

            val thumbH = maxOf(rowHeight / 2, trackH * capacity / rows.size)
            val span = rows.size - capacity
            val thumbY = start + (trackH - thumbH) * scroll / span
            canvas.fill(trackX, thumbY, trackW, thumbH, Panels.ACCENT)
        }
    }

    private fun drawSystem(canvas: Canvas, width: Int, top: Int, bottom: Int) {
        val space = bottom - top
        var y = drawPanel(canvas, width, top, bottom, "SYSTEM")

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
        val room = (width * 88 / 100 - width / 5) / (canvas.glyphWidth * scale)

        for ((label, value) in rows) {
            canvas.text(left, y, label, Panels.QUIET, scale)
            canvas.text(left + width / 5, y, clip(value, room), Panels.INK, scale)
            y += step
        }

        y += step / 2
        canvas.icon(PixelIcons.TERMINAL, left, y, maxOf(1, scale))
        canvas.text(
            left + PixelIcons.TERMINAL.width * maxOf(1, scale) + width / 60,
            y + canvas.glyphHeight * scale / 4,
            "A  OPEN SHELL",
            Panels.INK,
            scale,
        )

        y += step
        canvas.text(left, y, "ESC ON HOME   QUICK ACTIONS: SHUT DOWN OR RESTART", Panels.QUIET, scale)
    }

    private const val RENDERER_HINT = "RENDERER PLATFORM FOR THE N64 / GC"

    private fun onOff(value: Boolean): String = if (value) "ON" else "OFF"

    private fun clip(text: String, room: Int): String {
        if (room <= 0) return ""
        if (text.length <= room) return text
        return text.take(maxOf(0, room - 1)) + "."
    }

    private const val VOLUME_STEP = 5
    private const val STRIPE_WIDTH = 28
    private const val STRIPE_MILLIS = 90UL
    private const val PET_MILLIS = 55UL
}
