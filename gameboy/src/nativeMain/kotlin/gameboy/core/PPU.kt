package gameboy.core

class PPU(private val interrupts: Interrupts) {
    val frame = ByteArray(WIDTH * HEIGHT)
    val vram = ByteArray(0x2000)
    val oam = ByteArray(0xA0)

    internal var control = 0x91
    internal var scrollY = 0
    internal var scrollX = 0
    internal var line = 0
    internal var bgPalette = 0xFC
    internal var objPalette0 = 0xFF
    internal var objPalette1 = 0xFF
    internal var windowY = 0
    internal var windowX = 0
    internal var windowLine = 0

    private var status = 0x00
    private var compare = 0
    private var mode = 2
    private var dots = 0
    private var statLine = false

    private val renderer = PPURenderer(this)

    fun step(cycles: Int) {
        if (control and LCD_ENABLE == 0) {
            if (mode != 0 || line != 0) {
                mode = 0
                line = 0
                dots = 0
                windowLine = 0
                frame.fill(0)
            }
            return
        }

        dots += cycles

        when (mode) {
            2 -> if (dots >= OAM_DOTS) {
                dots -= OAM_DOTS
                setMode(3)
            }

            3 -> if (dots >= TRANSFER_DOTS) {
                dots -= TRANSFER_DOTS
                renderer.renderLine()
                setMode(0)
            }

            0 -> if (dots >= HBLANK_DOTS) {
                dots -= HBLANK_DOTS
                advanceLine()
                if (line == HEIGHT) {
                    setMode(1)
                    interrupts.request(Interrupts.VBLANK)
                } else {
                    setMode(2)
                }
            }

            1 -> if (dots >= LINE_DOTS) {
                dots -= LINE_DOTS
                advanceLine()
                if (line > LAST_LINE) {
                    line = 0
                    windowLine = 0
                    setMode(2)
                    checkCoincidence()
                }
            }
        }

        updateStatLine()
    }

    private fun advanceLine() {
        line++
        checkCoincidence()
    }

    private fun checkCoincidence() {
        status = if (line == compare) status or 0x04 else status and 0x04.inv()
    }

    private fun setMode(next: Int) {
        mode = next
        status = (status and 0xFC) or (next and 0x03)
    }

    private fun updateStatLine() {
        val lycMatch = (status and 0x40 != 0) && (status and 0x04 != 0)
        val modeMatch = when (mode) {
            0 -> status and 0x08 != 0
            1 -> status and 0x10 != 0
            2 -> status and 0x20 != 0
            else -> false
        }

        val level = lycMatch || modeMatch
        if (level && !statLine) interrupts.request(Interrupts.LCD)
        statLine = level
    }

    fun read(address: Int): Int = when (address) {
        0xFF40 -> control
        0xFF41 -> status or 0x80
        0xFF42 -> scrollY
        0xFF43 -> scrollX
        0xFF44 -> line
        0xFF45 -> compare
        0xFF47 -> bgPalette
        0xFF48 -> objPalette0
        0xFF49 -> objPalette1
        0xFF4A -> windowY
        0xFF4B -> windowX
        else -> 0xFF
    }

    fun write(address: Int, value: Int) {
        when (address) {
            0xFF40 -> {
                val wasEnabled = control and LCD_ENABLE != 0
                control = value and 0xFF
                if (wasEnabled && control and LCD_ENABLE == 0) {
                    line = 0
                    dots = 0
                    windowLine = 0
                    setMode(0)
                }
            }

            0xFF41 -> status = (status and 0x87) or (value and 0x78)
            0xFF42 -> scrollY = value and 0xFF
            0xFF43 -> scrollX = value and 0xFF
            0xFF44 -> line = 0
            0xFF45 -> {
                compare = value and 0xFF
                checkCoincidence()
            }

            0xFF47 -> bgPalette = value and 0xFF
            0xFF48 -> objPalette0 = value and 0xFF
            0xFF49 -> objPalette1 = value and 0xFF
            0xFF4A -> windowY = value and 0xFF
            0xFF4B -> windowX = value and 0xFF
        }
    }

    companion object {
        const val WIDTH = 160
        const val HEIGHT = 144

        const val LCD_ENABLE = 0x80
        const val WINDOW_MAP = 0x40
        const val WINDOW_ENABLE = 0x20
        const val TILE_DATA = 0x10
        const val BG_MAP = 0x08
        const val SPRITE_SIZE = 0x04
        const val SPRITE_ENABLE = 0x02
        const val BG_ENABLE = 0x01

        private const val LAST_LINE = 153
        private const val OAM_DOTS = 80
        private const val TRANSFER_DOTS = 172
        private const val HBLANK_DOTS = 204
        private const val LINE_DOTS = 456
    }
}
