package gba.core

import kapi.state.StateReader
import kapi.state.StateWriter

class PPU(private val interrupts: Interrupts) {
    val palette = ByteArray(0x400)
    val vram = ByteArray(0x18000)
    val oam = ByteArray(0x400)

    val frame = ShortArray(WIDTH * HEIGHT)
    private val working = ShortArray(WIDTH * HEIGHT)
    private var framePending = false

    var onHBlank: (() -> Unit)? = null
    var onVBlank: (() -> Unit)? = null

    private var dispcnt = 0
    private var dispstat = 0
    var vcount = 0
        private set

    private val bgcnt = IntArray(4)
    private val bghofs = IntArray(4)
    private val bgvofs = IntArray(4)

    private val bgpa = IntArray(2) { 0x100 }
    private val bgpb = IntArray(2)
    private val bgpc = IntArray(2)
    private val bgpd = IntArray(2) { 0x100 }
    private val bgRefX = IntArray(2)
    private val bgRefY = IntArray(2)
    private val internalX = IntArray(2)
    private val internalY = IntArray(2)

    private var bldcnt = 0
    private var bldalpha = 0
    private var bldy = 0
    private var mosaic = 0
    private val winRegs = IntArray(6)

    private var dots = 0

    private val bgLine = Array(4) { IntArray(WIDTH) }
    private val objLine = IntArray(WIDTH)
    private val objPriority = IntArray(WIDTH)
    private val objSemi = BooleanArray(WIDTH)

    fun save(writer: StateWriter) {
        writer.bytes(palette)
        writer.bytes(vram)
        writer.bytes(oam)
        writer.shorts(frame)
        writer.shorts(working)
        writer.bool(framePending)

        writer.int(dispcnt)
        writer.int(dispstat)
        writer.int(vcount)
        writer.int(dots)

        writer.ints(bgcnt)
        writer.ints(bghofs)
        writer.ints(bgvofs)
        writer.ints(bgpa)
        writer.ints(bgpb)
        writer.ints(bgpc)
        writer.ints(bgpd)
        writer.ints(bgRefX)
        writer.ints(bgRefY)
        writer.ints(internalX)
        writer.ints(internalY)
        writer.ints(winRegs)

        writer.int(bldcnt)
        writer.int(bldalpha)
        writer.int(bldy)
        writer.int(mosaic)
    }

    fun load(reader: StateReader) {
        reader.bytes(palette)
        reader.bytes(vram)
        reader.bytes(oam)
        reader.shorts(frame)
        reader.shorts(working)
        framePending = reader.bool()

        dispcnt = reader.int()
        dispstat = reader.int()
        vcount = reader.int()
        dots = reader.int()

        reader.ints(bgcnt)
        reader.ints(bghofs)
        reader.ints(bgvofs)
        reader.ints(bgpa)
        reader.ints(bgpb)
        reader.ints(bgpc)
        reader.ints(bgpd)
        reader.ints(bgRefX)
        reader.ints(bgRefY)
        reader.ints(internalX)
        reader.ints(internalY)
        reader.ints(winRegs)

        bldcnt = reader.int()
        bldalpha = reader.int()
        bldy = reader.int()
        mosaic = reader.int()
    }

    fun consumeFrame(): Boolean {
        if (!framePending) return false
        framePending = false
        return true
    }

    fun ioRead(offset: Int): Int = when (offset) {
        0x00 -> dispcnt
        0x04 -> dispstat
        0x06 -> vcount
        0x08, 0x0A, 0x0C, 0x0E -> bgcnt[(offset - 8) / 2]
        0x48 -> winRegs[4]
        0x4A -> winRegs[5]
        0x50 -> bldcnt
        0x52 -> bldalpha
        else -> 0
    }

    fun ioWrite(offset: Int, value: Int) {
        when (offset) {
            0x00 -> dispcnt = value
            0x04 -> dispstat = (dispstat and 0x7) or (value and 0xFFF8)
            0x08, 0x0A, 0x0C, 0x0E -> bgcnt[(offset - 8) / 2] = value
            0x10, 0x14, 0x18, 0x1C -> bghofs[(offset - 0x10) / 4] = value and 0x1FF
            0x12, 0x16, 0x1A, 0x1E -> bgvofs[(offset - 0x12) / 4] = value and 0x1FF

            0x20, 0x30 -> bgpa[(offset - 0x20) / 0x10] = value.toShort().toInt()
            0x22, 0x32 -> bgpb[(offset - 0x22) / 0x10] = value.toShort().toInt()
            0x24, 0x34 -> bgpc[(offset - 0x24) / 0x10] = value.toShort().toInt()
            0x26, 0x36 -> bgpd[(offset - 0x26) / 0x10] = value.toShort().toInt()

            0x28, 0x38 -> {
                val index = (offset - 0x28) / 0x10
                bgRefX[index] = (bgRefX[index] and 0xFFFF0000.toInt()) or value
                internalX[index] = signExtend28(bgRefX[index])
            }
            0x2A, 0x3A -> {
                val index = (offset - 0x2A) / 0x10
                bgRefX[index] = (bgRefX[index] and 0xFFFF) or (value shl 16)
                internalX[index] = signExtend28(bgRefX[index])
            }
            0x2C, 0x3C -> {
                val index = (offset - 0x2C) / 0x10
                bgRefY[index] = (bgRefY[index] and 0xFFFF0000.toInt()) or value
                internalY[index] = signExtend28(bgRefY[index])
            }
            0x2E, 0x3E -> {
                val index = (offset - 0x2E) / 0x10
                bgRefY[index] = (bgRefY[index] and 0xFFFF) or (value shl 16)
                internalY[index] = signExtend28(bgRefY[index])
            }

            0x40 -> winRegs[0] = value
            0x42 -> winRegs[1] = value
            0x44 -> winRegs[2] = value
            0x46 -> winRegs[3] = value
            0x48 -> winRegs[4] = value
            0x4A -> winRegs[5] = value
            0x4C -> mosaic = value
            0x50 -> bldcnt = value
            0x52 -> bldalpha = value
            0x54 -> bldy = value and 0x1F
        }
    }

    private fun signExtend28(value: Int): Int = (value shl 4) shr 4

    fun step(cycles: Int) {
        dots += cycles

        while (dots >= LINE_CYCLES) {
            dots -= LINE_CYCLES
            advanceLine()
        }

        val inHBlank = dots >= VISIBLE_CYCLES
        if (inHBlank && dispstat and 0x2 == 0) {
            dispstat = dispstat or 0x2

            if (vcount < HEIGHT) {
                renderLine()
                onHBlank?.invoke()
            }

            if (dispstat and 0x10 != 0) interrupts.request(Interrupts.HBLANK)
        }
    }

    private fun advanceLine() {
        dispstat = dispstat and 0x2.inv()
        vcount++

        if (vcount == HEIGHT) {
            dispstat = dispstat or 0x1
            working.copyInto(frame)
            framePending = true

            for (i in 0 until 2) {
                internalX[i] = signExtend28(bgRefX[i])
                internalY[i] = signExtend28(bgRefY[i])
            }

            if (dispstat and 0x8 != 0) interrupts.request(Interrupts.VBLANK)
            onVBlank?.invoke()
        }

        if (vcount >= TOTAL_LINES) {
            vcount = 0
            dispstat = dispstat and 0x1.inv()
        }

        if (vcount == 227) dispstat = dispstat and 0x1.inv()

        val compare = (dispstat ushr 8) and 0xFF
        if (vcount == compare) {
            dispstat = dispstat or 0x4
            if (dispstat and 0x20 != 0) interrupts.request(Interrupts.VCOUNT)
        } else {
            dispstat = dispstat and 0x4.inv()
        }
    }

    private fun renderLine() {
        val line = vcount
        val base = line * WIDTH
        val backdrop = paletteColor(0)

        if (dispcnt and 0x80 != 0) {
            for (x in 0 until WIDTH) working[base + x] = 0x7FFF
            return
        }

        val mode = dispcnt and 0x7

        for (bg in 0 until 4) bgLine[bg].fill(TRANSPARENT)
        objLine.fill(TRANSPARENT)
        objSemi.fill(false)

        when (mode) {
            0 -> {
                for (bg in 0 until 4) {
                    if (dispcnt and (0x100 shl bg) != 0) renderTextBg(bg, line)
                }
            }
            1 -> {
                if (dispcnt and 0x100 != 0) renderTextBg(0, line)
                if (dispcnt and 0x200 != 0) renderTextBg(1, line)
                if (dispcnt and 0x400 != 0) renderAffineBg(2)
            }
            2 -> {
                if (dispcnt and 0x400 != 0) renderAffineBg(2)
                if (dispcnt and 0x800 != 0) renderAffineBg(3)
            }
            3 -> if (dispcnt and 0x400 != 0) {
                for (x in 0 until WIDTH) {
                    val index = (line * WIDTH + x) * 2
                    bgLine[2][x] = vram16(index)
                }
            }
            4 -> if (dispcnt and 0x400 != 0) {
                val pageBase = if (dispcnt and 0x10 != 0) 0xA000 else 0
                for (x in 0 until WIDTH) {
                    val color = vram[pageBase + line * WIDTH + x].toInt() and 0xFF
                    if (color != 0) bgLine[2][x] = paletteColor(color)
                }
            }
            5 -> if (dispcnt and 0x400 != 0 && line < 128) {
                val pageBase = if (dispcnt and 0x10 != 0) 0xA000 else 0
                for (x in 0 until 160) {
                    bgLine[2][x] = vram16(pageBase + (line * 160 + x) * 2)
                }
            }
        }

        if (dispcnt and 0x1000 != 0) renderSprites(line, mode >= 3)

        if (internalAffineActive(mode)) {
            for (i in 0 until 2) {
                internalX[i] += bgpb[i]
                internalY[i] += bgpd[i]
            }
        }

        composite(base, backdrop, mode)
    }

    private fun internalAffineActive(mode: Int): Boolean = mode in 1..5

    private fun composite(base: Int, backdrop: Int, mode: Int) {
        val blendMode = (bldcnt ushr 6) and 0x3
        val eva = minOf(bldalpha and 0x1F, 16)
        val evb = minOf((bldalpha ushr 8) and 0x1F, 16)

        for (x in 0 until WIDTH) {
            var topColor = backdrop
            var topLayer = 5
            var secondColor = topColor
            var secondLayer = 5
            var found = 0
            var topIsSemiObj = false

            for (priority in 0 until 4) {
                if (found >= 2) break

                if (objLine[x] != TRANSPARENT && objPriority[x] == priority) {
                    if (found == 0) {
                        topColor = objLine[x]
                        topLayer = 4
                        topIsSemiObj = objSemi[x]
                        found = 1
                    } else {
                        secondColor = objLine[x]
                        secondLayer = 4
                        found = 2
                        break
                    }
                }

                for (bg in 0 until 4) {
                    if (found >= 2) break
                    if (bgLine[bg][x] == TRANSPARENT) continue
                    if ((bgcnt[bg] and 0x3) != priority) continue
                    if (!bgEnabledInMode(bg, mode)) continue

                    if (found == 0) {
                        topColor = bgLine[bg][x]
                        topLayer = bg
                        found = 1
                    } else {
                        secondColor = bgLine[bg][x]
                        secondLayer = bg
                        found = 2
                    }
                }
            }

            var color = topColor

            val firstTarget = bldcnt and (1 shl topLayer) != 0
            val secondTarget = bldcnt and (0x100 shl secondLayer) != 0

            if (topIsSemiObj && secondTarget) {
                color = alphaBlend(topColor, secondColor, eva, evb)
            } else if (firstTarget) {
                when (blendMode) {
                    1 -> if (secondTarget) color = alphaBlend(topColor, secondColor, eva, evb)
                    2 -> color = brightness(topColor, bldy, true)
                    3 -> color = brightness(topColor, bldy, false)
                }
            }

            working[base + x] = color.toShort()
        }
    }

    private fun bgEnabledInMode(bg: Int, mode: Int): Boolean = when (mode) {
        0 -> dispcnt and (0x100 shl bg) != 0
        1 -> bg < 3 && dispcnt and (0x100 shl bg) != 0
        2 -> bg >= 2 && dispcnt and (0x100 shl bg) != 0
        else -> bg == 2 && dispcnt and 0x400 != 0
    }

    private fun alphaBlend(top: Int, bottom: Int, eva: Int, evb: Int): Int {
        val r = minOf(31, ((top and 0x1F) * eva + (bottom and 0x1F) * evb) shr 4)
        val g = minOf(31, (((top ushr 5) and 0x1F) * eva + ((bottom ushr 5) and 0x1F) * evb) shr 4)
        val b = minOf(31, (((top ushr 10) and 0x1F) * eva + ((bottom ushr 10) and 0x1F) * evb) shr 4)
        return r or (g shl 5) or (b shl 10)
    }

    private fun brightness(color: Int, amount: Int, increase: Boolean): Int {
        val factor = minOf(amount, 16)
        var r = color and 0x1F
        var g = (color ushr 5) and 0x1F
        var b = (color ushr 10) and 0x1F

        if (increase) {
            r += ((31 - r) * factor) shr 4
            g += ((31 - g) * factor) shr 4
            b += ((31 - b) * factor) shr 4
        } else {
            r -= (r * factor) shr 4
            g -= (g * factor) shr 4
            b -= (b * factor) shr 4
        }

        return r or (g shl 5) or (b shl 10)
    }

    private fun renderTextBg(bg: Int, line: Int) {
        val control = bgcnt[bg]
        val charBase = ((control ushr 2) and 0x3) * 0x4000
        val screenBase = ((control ushr 8) and 0x1F) * 0x800
        val eightBpp = control and 0x80 != 0
        val size = (control ushr 14) and 0x3

        val wide = size and 1 != 0
        val tall = size and 2 != 0

        val y = (line + bgvofs[bg]) and (if (tall) 511 else 255)
        val output = bgLine[bg]

        var x = 0
        while (x < WIDTH) {
            val scrolledX = (x + bghofs[bg]) and (if (wide) 511 else 255)

            var block = 0
            if (wide && scrolledX >= 256) block += 1
            if (tall && y >= 256) block += if (wide) 2 else 1

            val entryAddress = screenBase + block * 0x800 + ((y and 255) shr 3) * 64 + ((scrolledX and 255) shr 3) * 2
            val entry = vramRaw16(entryAddress)

            val tile = entry and 0x3FF
            val hflip = entry and 0x400 != 0
            val vflip = entry and 0x800 != 0
            val paletteRow = (entry ushr 12) and 0xF

            var row = y and 7
            if (vflip) row = 7 - row

            var column = scrolledX and 7
            val pixelsLeft = 8 - column

            for (i in 0 until minOf(pixelsLeft, WIDTH - x)) {
                var col = column + i
                if (hflip) col = 7 - col

                val colorIndex = if (eightBpp) {
                    val address = charBase + tile * 64 + row * 8 + col
                    if (address < 0x10000) vram[address].toInt() and 0xFF else 0
                } else {
                    val address = charBase + tile * 32 + row * 4 + (col shr 1)
                    if (address < 0x10000) {
                        val pair = vram[address].toInt() and 0xFF
                        if (col and 1 == 0) pair and 0xF else pair ushr 4
                    } else 0
                }

                if (colorIndex != 0) {
                    output[x + i] = if (eightBpp) {
                        paletteColor(colorIndex)
                    } else {
                        paletteColor(paletteRow * 16 + colorIndex)
                    }
                }
            }

            x += pixelsLeft
        }
    }

    private fun renderAffineBg(bg: Int) {
        val index = bg - 2
        val control = bgcnt[bg]
        val charBase = ((control ushr 2) and 0x3) * 0x4000
        val screenBase = ((control ushr 8) and 0x1F) * 0x800
        val size = 128 shl ((control ushr 14) and 0x3)
        val wrap = control and 0x2000 != 0

        val output = bgLine[bg]

        var currentX = internalX[index]
        var currentY = internalY[index]

        for (x in 0 until WIDTH) {
            var texX = currentX shr 8
            var texY = currentY shr 8
            currentX += bgpa[index]
            currentY += bgpc[index]

            if (wrap) {
                texX = texX and (size - 1)
                texY = texY and (size - 1)
            } else if (texX < 0 || texY < 0 || texX >= size || texY >= size) {
                continue
            }

            val tile = vram[screenBase + (texY shr 3) * (size shr 3) + (texX shr 3)].toInt() and 0xFF
            val address = charBase + tile * 64 + (texY and 7) * 8 + (texX and 7)
            if (address >= 0x10000) continue

            val colorIndex = vram[address].toInt() and 0xFF
            if (colorIndex != 0) output[x] = paletteColor(colorIndex)
        }
    }

    private fun renderSprites(line: Int, bitmapMode: Boolean) {
        objPriority.fill(4)

        for (sprite in 0 until 128) {
            val attr0 = oamRaw16(sprite * 8)
            val attr1 = oamRaw16(sprite * 8 + 2)
            val attr2 = oamRaw16(sprite * 8 + 4)

            val affine = attr0 and 0x100 != 0
            if (!affine && attr0 and 0x200 != 0) continue

            val objMode = (attr0 ushr 10) and 0x3
            if (objMode == 2) continue

            val shape = (attr0 ushr 14) and 0x3
            val sizeIndex = (attr1 ushr 14) and 0x3

            val width = SPRITE_WIDTHS[shape * 4 + sizeIndex]
            val height = SPRITE_HEIGHTS[shape * 4 + sizeIndex]
            if (width == 0) continue

            var spriteY = attr0 and 0xFF
            if (spriteY >= 160) spriteY -= 256

            var spriteX = attr1 and 0x1FF
            if (spriteX >= 240) spriteX -= 512

            val doubleSize = affine && attr0 and 0x200 != 0
            val boundsWidth = if (doubleSize) width * 2 else width
            val boundsHeight = if (doubleSize) height * 2 else height

            if (line < spriteY || line >= spriteY + boundsHeight) continue

            val eightBpp = attr0 and 0x2000 != 0
            val priority = (attr2 ushr 10) and 0x3
            val paletteRow = (attr2 ushr 12) and 0xF
            var tile = attr2 and 0x3FF

            if (bitmapMode && tile < 512) continue
            if (eightBpp) tile = tile and 1.inv()

            val oneDimensional = dispcnt and 0x40 != 0
            val tileWidth = if (eightBpp) width / 8 * 2 else width / 8
            val rowStride = if (oneDimensional) tileWidth else 32

            val localLine = line - spriteY

            if (affine) {
                val group = (attr1 ushr 9) and 0x1F
                val pa = oamRaw16(group * 32 + 6).toShort().toInt()
                val pb = oamRaw16(group * 32 + 14).toShort().toInt()
                val pc = oamRaw16(group * 32 + 22).toShort().toInt()
                val pd = oamRaw16(group * 32 + 30).toShort().toInt()

                val centerX = boundsWidth / 2
                val centerY = boundsHeight / 2

                for (i in 0 until boundsWidth) {
                    val screenX = spriteX + i
                    if (screenX < 0 || screenX >= WIDTH) continue

                    val relX = i - centerX
                    val relY = localLine - centerY

                    val texX = ((pa * relX + pb * relY) shr 8) + width / 2
                    val texY = ((pc * relX + pd * relY) shr 8) + height / 2

                    if (texX < 0 || texX >= width || texY < 0 || texY >= height) continue

                    drawSpritePixel(screenX, tile, texX, texY, eightBpp, rowStride, paletteRow, priority, objMode == 1)
                }
            } else {
                val hflip = attr1 and 0x1000 != 0
                val vflip = attr1 and 0x2000 != 0

                var texY = localLine
                if (vflip) texY = height - 1 - texY

                for (i in 0 until width) {
                    val screenX = spriteX + i
                    if (screenX < 0 || screenX >= WIDTH) continue

                    var texX = i
                    if (hflip) texX = width - 1 - texX

                    drawSpritePixel(screenX, tile, texX, texY, eightBpp, rowStride, paletteRow, priority, objMode == 1)
                }
            }
        }
    }

    private fun drawSpritePixel(
        screenX: Int,
        baseTile: Int,
        texX: Int,
        texY: Int,
        eightBpp: Boolean,
        rowStride: Int,
        paletteRow: Int,
        priority: Int,
        semi: Boolean,
    ) {
        if (objLine[screenX] != TRANSPARENT) return

        val tileX = texX shr 3
        val tileY = texY shr 3
        val pixelX = texX and 7
        val pixelY = texY and 7

        val colorIndex: Int
        if (eightBpp) {
            val tileNumber = baseTile + tileY * rowStride + tileX * 2
            val address = 0x10000 + (tileNumber and 0x3FF) * 32 + pixelY * 8 + pixelX
            colorIndex = vram[address].toInt() and 0xFF
        } else {
            val tileNumber = baseTile + tileY * rowStride + tileX
            val address = 0x10000 + (tileNumber and 0x3FF) * 32 + pixelY * 4 + (pixelX shr 1)
            val pair = vram[address].toInt() and 0xFF
            colorIndex = if (pixelX and 1 == 0) pair and 0xF else pair ushr 4
        }

        if (colorIndex == 0) return

        objLine[screenX] = if (eightBpp) {
            paletteColor(256 + colorIndex)
        } else {
            paletteColor(256 + paletteRow * 16 + colorIndex)
        }
        objPriority[screenX] = priority
        objSemi[screenX] = semi
    }

    private fun paletteColor(index: Int): Int {
        val address = index * 2
        return (palette[address].toInt() and 0xFF) or ((palette[address + 1].toInt() and 0x7F) shl 8)
    }

    private fun vram16(address: Int): Int =
        (vram[address].toInt() and 0xFF) or ((vram[address + 1].toInt() and 0x7F) shl 8)

    private fun vramRaw16(address: Int): Int =
        (vram[address].toInt() and 0xFF) or ((vram[address + 1].toInt() and 0xFF) shl 8)

    private fun oamRaw16(address: Int): Int =
        (oam[address].toInt() and 0xFF) or ((oam[address + 1].toInt() and 0xFF) shl 8)

    companion object {
        const val WIDTH = 240
        const val HEIGHT = 160

        const val TOTAL_LINES = 228
        const val VISIBLE_CYCLES = 960
        const val LINE_CYCLES = 1232

        private const val TRANSPARENT = -1

        private val SPRITE_WIDTHS = intArrayOf(
            8, 16, 32, 64,
            16, 32, 32, 64,
            8, 8, 16, 32,
            0, 0, 0, 0,
        )

        private val SPRITE_HEIGHTS = intArrayOf(
            8, 16, 32, 64,
            8, 8, 16, 32,
            16, 32, 32, 64,
            0, 0, 0, 0,
        )
    }
}
