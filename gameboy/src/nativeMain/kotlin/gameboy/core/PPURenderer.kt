package gameboy.core

class PPURenderer(private val ppu: PPU) {
    private val backgroundShades = IntArray(PPU.WIDTH)
    private val visibleSprites = IntArray(MAX_SPRITES_PER_LINE)

    fun renderLine() {
        val line = ppu.line
        if (line >= PPU.HEIGHT) return

        val base = line * PPU.WIDTH
        val control = ppu.control

        if (control and PPU.BG_ENABLE != 0) {
            renderBackground(base, line)
            if (control and PPU.WINDOW_ENABLE != 0 && line >= ppu.windowY) renderWindow(base, line)
        } else {
            for (x in 0 until PPU.WIDTH) {
                backgroundShades[x] = 0
                ppu.frame[base + x] = 0
            }
        }

        if (control and PPU.SPRITE_ENABLE != 0) renderSprites(base, line)
    }

    private fun renderBackground(base: Int, line: Int) {
        val mapBase = if (ppu.control and PPU.BG_MAP != 0) 0x1C00 else 0x1800
        val y = (line + ppu.scrollY) and 0xFF
        val tileRow = (y shr 3) and 31

        for (x in 0 until PPU.WIDTH) {
            val mapX = (x + ppu.scrollX) and 0xFF
            val tileColumn = (mapX shr 3) and 31

            val tile = ppu.vram[mapBase + tileRow * 32 + tileColumn].toInt() and 0xFF
            val color = tilePixel(tile, mapX and 7, y and 7)

            backgroundShades[x] = color
            ppu.frame[base + x] = shade(color, ppu.bgPalette).toByte()
        }
    }

    private fun renderWindow(base: Int, line: Int) {
        val startX = ppu.windowX - 7
        if (startX >= PPU.WIDTH) return

        val mapBase = if (ppu.control and PPU.WINDOW_MAP != 0) 0x1C00 else 0x1800
        val y = ppu.windowLine
        val tileRow = (y shr 3) and 31

        var drawn = false
        for (x in 0 until PPU.WIDTH) {
            if (x < startX) continue

            val windowX = x - startX
            val tileColumn = (windowX shr 3) and 31

            val tile = ppu.vram[mapBase + tileRow * 32 + tileColumn].toInt() and 0xFF
            val color = tilePixel(tile, windowX and 7, y and 7)

            backgroundShades[x] = color
            ppu.frame[base + x] = shade(color, ppu.bgPalette).toByte()
            drawn = true
        }

        if (drawn) ppu.windowLine++
    }

    private fun tilePixel(tile: Int, x: Int, y: Int): Int {
        val address = if (ppu.control and PPU.TILE_DATA != 0) {
            tile * 16
        } else {
            0x1000 + (tile.toByte().toInt() * 16)
        }

        val low = ppu.vram[address + y * 2].toInt() and 0xFF
        val high = ppu.vram[address + y * 2 + 1].toInt() and 0xFF
        val bit = 7 - x

        return (((high shr bit) and 1) shl 1) or ((low shr bit) and 1)
    }

    private fun renderSprites(base: Int, line: Int) {
        val height = if (ppu.control and PPU.SPRITE_SIZE != 0) 16 else 8

        var count = 0
        var entry = 0
        while (entry < SPRITE_COUNT && count < MAX_SPRITES_PER_LINE) {
            val y = (ppu.oam[entry * 4].toInt() and 0xFF) - 16
            if (line >= y && line < y + height) {
                visibleSprites[count] = entry
                count++
            }
            entry++
        }

        var i = count - 1
        while (i >= 0) {
            drawSprite(base, line, visibleSprites[i], height)
            i--
        }
    }

    private fun drawSprite(base: Int, line: Int, entry: Int, height: Int) {
        val spriteY = (ppu.oam[entry * 4].toInt() and 0xFF) - 16
        val spriteX = (ppu.oam[entry * 4 + 1].toInt() and 0xFF) - 8
        val flags = ppu.oam[entry * 4 + 3].toInt() and 0xFF

        var tile = ppu.oam[entry * 4 + 2].toInt() and 0xFF
        if (height == 16) tile = tile and 0xFE

        var row = line - spriteY
        if (flags and FLIP_Y != 0) row = height - 1 - row

        val address = tile * 16 + row * 2
        val low = ppu.vram[address].toInt() and 0xFF
        val high = ppu.vram[address + 1].toInt() and 0xFF

        val palette = if (flags and PALETTE != 0) ppu.objPalette1 else ppu.objPalette0
        val behindBackground = flags and BEHIND_BG != 0

        for (x in 0 until 8) {
            val screenX = spriteX + x
            if (screenX < 0 || screenX >= PPU.WIDTH) continue

            val bit = if (flags and FLIP_X != 0) x else 7 - x
            val color = (((high shr bit) and 1) shl 1) or ((low shr bit) and 1)
            if (color == 0) continue

            if (behindBackground && backgroundShades[screenX] != 0) continue

            ppu.frame[base + screenX] = shade(color, palette).toByte()
        }
    }

    private fun shade(color: Int, palette: Int): Int = (palette shr (color * 2)) and 0x03

    private companion object {
        const val SPRITE_COUNT = 40
        const val MAX_SPRITES_PER_LINE = 10

        const val BEHIND_BG = 0x80
        const val FLIP_Y = 0x40
        const val FLIP_X = 0x20
        const val PALETTE = 0x10
    }
}
