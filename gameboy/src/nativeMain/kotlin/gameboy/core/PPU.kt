package gameboy.core

class PPU(private val interrupts: Interrupts, internal val color: Boolean) {
    val frame = ByteArray(WIDTH * HEIGHT)
    val vram = ByteArray(VRAM_BANKS * VRAM_BANK_SIZE)
    val oam = ByteArray(0xA0)

    val colors = IntArray(PALETTE_ENTRIES)

    private val backgroundColors = ColorPalette(BG_PALETTE_BASE, colors)
    private val spriteColors = ColorPalette(OBJ_PALETTE_BASE, colors)

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
    internal var vramBank = 0

    private var status = 0x00
    private var compare = 0
    private var mode = 2
    private var dots = 0
    private var statLine = false
    private var hblankPending = false

    private val renderer = PPURenderer(this)

    val paletteVersion: Int
        get() = backgroundColors.version + spriteColors.version

    fun consumeHBlank(): Boolean {
        if (!hblankPending) return false
        hblankPending = false
        return true
    }

    fun readVram(address: Int): Int =
        vram[vramBank * VRAM_BANK_SIZE + (address - 0x8000)].toInt() and 0xFF

    fun writeVram(address: Int, value: Int) {
        vram[vramBank * VRAM_BANK_SIZE + (address - 0x8000)] = value.toByte()
    }

    internal fun tileByte(bank: Int, offset: Int): Int =
        vram[bank * VRAM_BANK_SIZE + offset].toInt() and 0xFF

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
                hblankPending = true
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
        0xFF4F -> if (color) vramBank or 0xFE else 0xFF
        0xFF68 -> if (color) backgroundColors.readIndex() else 0xFF
        0xFF69 -> if (color) backgroundColors.readData() else 0xFF
        0xFF6A -> if (color) spriteColors.readIndex() else 0xFF
        0xFF6B -> if (color) spriteColors.readData() else 0xFF
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
            0xFF4F -> if (color) vramBank = value and 0x01
            0xFF68 -> if (color) backgroundColors.writeIndex(value)
            0xFF69 -> if (color) backgroundColors.writeData(value)
            0xFF6A -> if (color) spriteColors.writeIndex(value)
            0xFF6B -> if (color) spriteColors.writeData(value)
        }
    }

    companion object {
        const val WIDTH = 160
        const val HEIGHT = 144

        const val VRAM_BANKS = 2
        const val VRAM_BANK_SIZE = 0x2000

        const val PALETTE_ENTRIES = 64
        const val BG_PALETTE_BASE = 0
        const val OBJ_PALETTE_BASE = 32

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
