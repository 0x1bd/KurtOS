package gameboy.core

class PPURenderer(private val ppu: PPU) {
    private val backgroundColors = IntArray(PPU.WIDTH)
    private val backgroundPriority = BooleanArray(PPU.WIDTH)
    private val visibleSprites = IntArray(MAX_SPRITES_PER_LINE)

    fun renderLine() {
        val line = ppu.line
        if (line >= PPU.HEIGHT) return

        val base = line * PPU.WIDTH
        val control = ppu.control

        if (ppu.color || control and PPU.BG_ENABLE != 0) {
            renderBackground(base, line)
            if (control and PPU.WINDOW_ENABLE != 0 && line >= ppu.windowY) renderWindow(base, line)
        } else {
            for (x in 0 until PPU.WIDTH) {
                backgroundColors[x] = 0
                backgroundPriority[x] = false
                ppu.frame[base + x] = 0
            }
        }

        if (control and PPU.SPRITE_ENABLE != 0) renderSprites(base, line)
    }

    private fun renderBackground(base: Int, line: Int) {
        val mapBase = if (ppu.control and PPU.BG_MAP != 0) 0x1C00 else 0x1800
        val y = (line + ppu.scrollY) and 0xFF

        var x = 0
        while (x < PPU.WIDTH) {
            x += renderSpan(base, x, mapBase, (x + ppu.scrollX) and 0xFF, y)
        }
    }

    private fun renderWindow(base: Int, line: Int) {
        val startX = ppu.windowX - 7
        if (startX >= PPU.WIDTH) return

        val mapBase = if (ppu.control and PPU.WINDOW_MAP != 0) 0x1C00 else 0x1800
        val y = ppu.windowLine

        var x = if (startX < 0) 0 else startX
        while (x < PPU.WIDTH) {
            x += renderSpan(base, x, mapBase, x - startX, y)
        }

        ppu.windowLine++
    }

    private fun renderSpan(base: Int, screenX: Int, mapBase: Int, mapX: Int, mapY: Int): Int {
        val entry = mapBase + ((mapY shr 3) and 31) * 32 + ((mapX shr 3) and 31)
        val tile = ppu.tileByte(0, entry)

        val attributes = if (ppu.color) ppu.tileByte(1, entry) else 0
        val bank = if (attributes and TILE_BANK != 0) 1 else 0

        var row = mapY and 7
        if (attributes and FLIP_Y != 0) row = 7 - row

        val address = tileAddress(tile) + row * 2
        val low = ppu.tileByte(bank, address)
        val high = ppu.tileByte(bank, address + 1)

        val flipX = attributes and FLIP_X != 0
        val priority = attributes and BEHIND_BG != 0
        val paletteBase = PPU.BG_PALETTE_BASE + (attributes and PALETTE_MASK) * 4

        var offset = mapX and 7
        var x = screenX

        while (offset < 8 && x < PPU.WIDTH) {
            val bit = if (flipX) offset else 7 - offset
            val color = (((high shr bit) and 1) shl 1) or ((low shr bit) and 1)

            backgroundColors[x] = color
            backgroundPriority[x] = priority

            ppu.frame[base + x] = if (ppu.color) {
                (paletteBase + color).toByte()
            } else {
                shade(color, ppu.bgPalette).toByte()
            }

            offset++
            x++
        }

        return x - screenX
    }

    private fun tileAddress(tile: Int): Int = if (ppu.control and PPU.TILE_DATA != 0) {
        tile * 16
    } else {
        0x1000 + (tile.toByte().toInt() * 16)
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

        orderSprites(count)

        for (i in 0 until count) {
            drawSprite(base, line, visibleSprites[i], height)
        }
    }

    private fun orderSprites(count: Int) {
        for (i in 1 until count) {
            val candidate = visibleSprites[i]
            var j = i - 1
            while (j >= 0 && drawnAfter(visibleSprites[j], candidate)) {
                visibleSprites[j + 1] = visibleSprites[j]
                j--
            }
            visibleSprites[j + 1] = candidate
        }
    }

    private fun drawnAfter(existing: Int, candidate: Int): Boolean {
        if (!ppu.color) {
            val existingX = ppu.oam[existing * 4 + 1].toInt() and 0xFF
            val candidateX = ppu.oam[candidate * 4 + 1].toInt() and 0xFF
            if (existingX != candidateX) return existingX < candidateX
        }
        return existing < candidate
    }

    private fun drawSprite(base: Int, line: Int, entry: Int, height: Int) {
        val spriteY = (ppu.oam[entry * 4].toInt() and 0xFF) - 16
        val spriteX = (ppu.oam[entry * 4 + 1].toInt() and 0xFF) - 8
        val flags = ppu.oam[entry * 4 + 3].toInt() and 0xFF

        var tile = ppu.oam[entry * 4 + 2].toInt() and 0xFF
        if (height == 16) tile = tile and 0xFE

        var row = line - spriteY
        if (flags and FLIP_Y != 0) row = height - 1 - row

        val bank = if (ppu.color && flags and TILE_BANK != 0) 1 else 0
        val address = tile * 16 + row * 2
        val low = ppu.tileByte(bank, address)
        val high = ppu.tileByte(bank, address + 1)

        val masterPriority = ppu.control and PPU.BG_ENABLE != 0
        val behindBackground = flags and BEHIND_BG != 0

        for (x in 0 until 8) {
            val screenX = spriteX + x
            if (screenX < 0 || screenX >= PPU.WIDTH) continue

            val bit = if (flags and FLIP_X != 0) x else 7 - x
            val color = (((high shr bit) and 1) shl 1) or ((low shr bit) and 1)
            if (color == 0) continue

            if (hidden(screenX, masterPriority, behindBackground)) continue

            ppu.frame[base + screenX] = if (ppu.color) {
                (PPU.OBJ_PALETTE_BASE + (flags and PALETTE_MASK) * 4 + color).toByte()
            } else {
                val palette = if (flags and DMG_PALETTE != 0) ppu.objPalette1 else ppu.objPalette0
                shade(color, palette).toByte()
            }
        }
    }

    private fun hidden(x: Int, masterPriority: Boolean, behindBackground: Boolean): Boolean {
        if (backgroundColors[x] == 0) return false

        if (!ppu.color) return behindBackground

        if (!masterPriority) return false
        return backgroundPriority[x] || behindBackground
    }

    private fun shade(color: Int, palette: Int): Int = (palette shr (color * 2)) and 0x03

    private companion object {
        const val SPRITE_COUNT = 40
        const val MAX_SPRITES_PER_LINE = 10

        const val BEHIND_BG = 0x80
        const val FLIP_Y = 0x40
        const val FLIP_X = 0x20
        const val DMG_PALETTE = 0x10
        const val TILE_BANK = 0x08
        const val PALETTE_MASK = 0x07
    }
}
