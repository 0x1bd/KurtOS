package snes.core

import kapi.state.StateReader
import kapi.state.StateWriter

class Ppu {
    val vram = ShortArray(0x8000)
    val cgram = ShortArray(256)
    val oam = ByteArray(544)
    val frame = ShortArray(WIDTH * HEIGHT)

    var forcedBlank = true
        private set

    private var brightness = 0

    private var oamAddress = 0
    private var oamReload = 0
    private var oamPriority = false
    private var oamLatch = 0
    private var oamFirst = 0

    private var vramAddress = 0
    private var vramIncrementHigh = false
    private var vramStep = 1
    private var vramRemap = 0
    private var vramLatch = 0

    private var cgramAddress = 0
    private var cgramLatch = 0
    private var cgramHigh = false

    private var bgMode = 0
    private var bg3Priority = false
    private val bgTileBig = BooleanArray(4)
    private val bgMapBase = IntArray(4)
    private val bgMapWide = BooleanArray(4)
    private val bgMapTall = BooleanArray(4)
    private val bgCharBase = IntArray(4)
    private val bgHofs = IntArray(4)
    private val bgVofs = IntArray(4)
    private var scrollLatch = 0
    private var scrollHighLatch = 0

    private var mosaicSize = 1
    private val bgMosaic = BooleanArray(4)

    private var objNameBase = 0
    private var objNameSelect = 0
    private var objSize = 0

    private val m7 = IntArray(6)
    private var m7Latch = 0
    private var m7Select = 0
    private var m7Hofs = 0
    private var m7Vofs = 0

    private var mainScreen = 0
    private var subScreen = 0
    private var mainWindowMask = 0
    private var subWindowMask = 0

    private val windowLeft = IntArray(2)
    private val windowRight = IntArray(2)
    private val windowEnable = IntArray(6)
    private val windowInvert = IntArray(6)
    private val windowLogic = IntArray(6)

    private var cgwsel = 0
    private var cgadsub = 0
    private var fixedColor = 0

    private var overscan = false
    private var interlace = false
    private var pseudoHires = false
    private var extbg = false

    private var latchedH = 0
    private var latchedV = 0
    private var counterLatched = false
    private var hFlip = false
    private var vFlip = false

    private var openBus = 0
    private var rangeOver = false
    private var timeOver = false

    private val bgColor = Array(4) { IntArray(WIDTH) }
    private val bgPriority = Array(4) { IntArray(WIDTH) }
    private val objColor = IntArray(WIDTH)
    private val objPriority = IntArray(WIDTH)
    private val objPalette = IntArray(WIDTH)

    private val mainColor = IntArray(WIDTH)
    private val mainLayer = IntArray(WIDTH)
    private val mainDepth = IntArray(WIDTH)
    private val subColor = IntArray(WIDTH)
    private val subLayer = IntArray(WIDTH)
    private val subDepth = IntArray(WIDTH)

    private val windowMask = Array(6) { BooleanArray(WIDTH) }

    private val offsetH = IntArray(WIDTH / 8 + 1)
    private val offsetV = IntArray(WIDTH / 8 + 1)

    private val spriteIndex = IntArray(32)
    private var spriteCount = 0

    var vCounter = 0
    var hCounter = 0

    private var activeLines = HEIGHT

    val visibleLines get() = activeLines

    val debugMode get() = bgMode
    val debugMain get() = mainScreen
    val debugSub get() = subScreen
    val debugM7 get() = m7
    val debugHofs get() = m7Hofs
    val debugVofs get() = m7Vofs
    val debugOverscan get() = overscan

    fun reset() {
        forcedBlank = true
        brightness = 0
        oamAddress = 0
        vramAddress = 0
        cgramAddress = 0
        bgMode = 0
        mosaicSize = 1
        mainScreen = 0
        subScreen = 0
        cgwsel = 0
        cgadsub = 0
        fixedColor = 0
        overscan = false
        activeLines = HEIGHT
        frame.fill(0)
    }

    fun readReg(addr: Int): Int {
        val value = when (addr) {
            0x2134 -> multiply() and 0xFF
            0x2135 -> (multiply() ushr 8) and 0xFF
            0x2136 -> (multiply() ushr 16) and 0xFF

            0x2137 -> {
                latchCounters()
                -1
            }

            0x2138 -> {
                val data = oam[oamAddress and 0x21F].toInt() and 0xFF
                oamAddress = (oamAddress + 1) and 0x3FF
                data
            }

            0x2139 -> {
                val data = vramLatch and 0xFF
                if (!vramIncrementHigh) {
                    vramLatch = vram[remapped()].toInt() and 0xFFFF
                    vramAddress = (vramAddress + vramStep) and 0x7FFF
                }
                data
            }

            0x213A -> {
                val data = (vramLatch ushr 8) and 0xFF
                if (vramIncrementHigh) {
                    vramLatch = vram[remapped()].toInt() and 0xFFFF
                    vramAddress = (vramAddress + vramStep) and 0x7FFF
                }
                data
            }

            0x213B -> {
                val word = cgram[cgramAddress and 0xFF].toInt() and 0xFFFF
                val data = if (!cgramHigh) word and 0xFF else (word ushr 8) and 0x7F

                if (cgramHigh) cgramAddress = (cgramAddress + 1) and 0xFF
                cgramHigh = !cgramHigh

                data
            }

            0x213C -> {
                val data = if (!hFlip) latchedH and 0xFF else (latchedH ushr 8) and 1
                hFlip = !hFlip
                data
            }

            0x213D -> {
                val data = if (!vFlip) latchedV and 0xFF else (latchedV ushr 8) and 1
                vFlip = !vFlip
                data
            }

            0x213E -> (if (timeOver) 0x80 else 0) or (if (rangeOver) 0x40 else 0) or PPU1_VERSION
            0x213F -> {
                val data = (if (counterLatched) 0x40 else 0) or PPU2_VERSION
                counterLatched = false
                hFlip = false
                vFlip = false
                data
            }

            else -> -1
        }

        if (value >= 0) openBus = value

        return value
    }

    var debugM7Writes = 0

    fun writeReg(addr: Int, value: Int) {
        if (addr in 0x211B..0x211E) debugM7Writes++
        when (addr) {
            0x2100 -> {
                forcedBlank = value and 0x80 != 0
                brightness = value and 0x0F
            }

            0x2101 -> {
                objSize = (value ushr 5) and 7
                objNameSelect = (value ushr 3) and 3
                objNameBase = (value and 7) shl 13
            }

            0x2102 -> {
                oamReload = (oamReload and 0x200) or (value shl 1)
                oamAddress = oamReload
                oamLatch = 0
            }

            0x2103 -> {
                oamPriority = value and 0x80 != 0
                oamReload = (oamReload and 0x1FE) or ((value and 1) shl 9)
                oamAddress = oamReload
                oamLatch = 0
            }

            0x2104 -> writeOam(value)

            0x2105 -> {
                bgMode = value and 7
                bg3Priority = value and 8 != 0
                for (i in 0 until 4) bgTileBig[i] = value and (0x10 shl i) != 0
            }

            0x2106 -> {
                mosaicSize = ((value ushr 4) and 0x0F) + 1
                for (i in 0 until 4) bgMosaic[i] = value and (1 shl i) != 0
            }

            0x2107, 0x2108, 0x2109, 0x210A -> {
                val bg = addr - 0x2107
                bgMapBase[bg] = (value and 0xFC) shl 8
                bgMapWide[bg] = value and 1 != 0
                bgMapTall[bg] = value and 2 != 0
            }

            0x210B -> {
                bgCharBase[0] = (value and 0x0F) shl 12
                bgCharBase[1] = ((value ushr 4) and 0x0F) shl 12
            }

            0x210C -> {
                bgCharBase[2] = (value and 0x0F) shl 12
                bgCharBase[3] = ((value ushr 4) and 0x0F) shl 12
            }

            0x210D -> {
                m7Hofs = ((value shl 8) or m7Latch) and 0x1FFF
                m7Latch = value
                bgHofs[0] = (value shl 8) or (scrollLatch and 0xF8) or (scrollHighLatch and 7)
                scrollLatch = value
                scrollHighLatch = value
            }

            0x210E -> {
                m7Vofs = ((value shl 8) or m7Latch) and 0x1FFF
                m7Latch = value
                bgVofs[0] = (value shl 8) or scrollLatch
                scrollLatch = value
            }

            0x210F, 0x2111, 0x2113 -> {
                val bg = (addr - 0x210D) / 2
                bgHofs[bg] = (value shl 8) or (scrollLatch and 0xF8) or (scrollHighLatch and 7)
                scrollLatch = value
                scrollHighLatch = value
            }

            0x2110, 0x2112, 0x2114 -> {
                val bg = (addr - 0x210E) / 2
                bgVofs[bg] = (value shl 8) or scrollLatch
                scrollLatch = value
            }

            0x2115 -> {
                vramIncrementHigh = value and 0x80 != 0
                vramRemap = (value ushr 2) and 3
                vramStep = when (value and 3) {
                    0 -> 1
                    1 -> 32
                    else -> 128
                }
            }

            0x2116 -> {
                vramAddress = (vramAddress and 0x7F00) or value
                vramLatch = vram[remapped()].toInt() and 0xFFFF
            }

            0x2117 -> {
                vramAddress = (vramAddress and 0x00FF) or ((value and 0x7F) shl 8)
                vramLatch = vram[remapped()].toInt() and 0xFFFF
            }

            0x2118 -> {
                val index = remapped()
                vram[index] = ((vram[index].toInt() and 0xFF00) or value).toShort()
                if (!vramIncrementHigh) vramAddress = (vramAddress + vramStep) and 0x7FFF
            }

            0x2119 -> {
                val index = remapped()
                vram[index] = ((vram[index].toInt() and 0x00FF) or (value shl 8)).toShort()
                if (vramIncrementHigh) vramAddress = (vramAddress + vramStep) and 0x7FFF
            }

            0x211A -> m7Select = value

            0x211B, 0x211C, 0x211D, 0x211E -> {
                val index = addr - 0x211B
                m7[index] = ((value shl 8) or m7Latch) and 0xFFFF
                m7Latch = value
            }

            0x211F, 0x2120 -> {
                val index = addr - 0x211B
                m7[index] = ((value shl 8) or m7Latch) and 0x1FFF
                m7Latch = value
            }

            0x2121 -> {
                cgramAddress = value
                cgramHigh = false
            }

            0x2122 -> {
                if (!cgramHigh) {
                    cgramLatch = value
                } else {
                    cgram[cgramAddress] = (((value and 0x7F) shl 8) or cgramLatch).toShort()
                    cgramAddress = (cgramAddress + 1) and 0xFF
                }
                cgramHigh = !cgramHigh
            }

            0x2123, 0x2124, 0x2125 -> {
                val base = (addr - 0x2123) * 2
                windowEnable[base] = if (value and 0x02 != 0) 1 else 0
                windowInvert[base] = if (value and 0x01 != 0) 1 else 0
                windowEnable[base] = windowEnable[base] or (if (value and 0x08 != 0) 2 else 0)
                windowInvert[base] = windowInvert[base] or (if (value and 0x04 != 0) 2 else 0)
                windowEnable[base + 1] = if (value and 0x20 != 0) 1 else 0
                windowInvert[base + 1] = if (value and 0x10 != 0) 1 else 0
                windowEnable[base + 1] = windowEnable[base + 1] or (if (value and 0x80 != 0) 2 else 0)
                windowInvert[base + 1] = windowInvert[base + 1] or (if (value and 0x40 != 0) 2 else 0)
            }

            0x2126 -> windowLeft[0] = value
            0x2127 -> windowRight[0] = value
            0x2128 -> windowLeft[1] = value
            0x2129 -> windowRight[1] = value

            0x212A -> for (i in 0 until 4) windowLogic[i] = (value ushr (i * 2)) and 3
            0x212B -> {
                windowLogic[4] = value and 3
                windowLogic[5] = (value ushr 2) and 3
            }

            0x212C -> mainScreen = value
            0x212D -> subScreen = value
            0x212E -> mainWindowMask = value
            0x212F -> subWindowMask = value

            0x2130 -> cgwsel = value
            0x2131 -> cgadsub = value

            0x2132 -> {
                val intensity = value and 0x1F
                if (value and 0x20 != 0) fixedColor = (fixedColor and 0x7FE0.inv()) or intensity
                if (value and 0x40 != 0) fixedColor = (fixedColor and 0x03E0.inv()) or (intensity shl 5)
                if (value and 0x80 != 0) fixedColor = (fixedColor and 0x7C00.inv()) or (intensity shl 10)
            }

            0x2133 -> {
                interlace = value and 0x01 != 0
                overscan = value and 0x04 != 0
                pseudoHires = value and 0x08 != 0
                extbg = value and 0x40 != 0
            }
        }

        openBus = value
    }

    fun latchCounters() {
        latchedH = hCounter
        latchedV = vCounter
        counterLatched = true
    }

    fun startFrame() {
        rangeOver = false
        timeOver = false
        activeLines = if (overscan) OVERSCAN_HEIGHT else HEIGHT
    }

    fun renderLine(line: Int) {
        if (line < 1 || line > activeLines) return

        val y = line - 1
        val row = y - (activeLines - HEIGHT) / 2

        if (row < 0 || row >= HEIGHT) return

        if (forcedBlank) {
            frame.fill(0, row * WIDTH, row * WIDTH + WIDTH)
            return
        }

        evaluateWindows()
        evaluateSprites(y)

        if (bgMode == 7) renderMode7(line) else renderTiles(y)

        composite(row)
    }

    private fun renderTiles(y: Int) {
        val layers = LAYER_COUNT[bgMode]

        if (bgMode == 2 || bgMode == 4 || bgMode == 6) computeOffsets(y)

        for (bg in 0 until layers) {
            renderBackground(bg, y)
        }
    }

    private fun renderBackground(bg: Int, y: Int) {
        val color = bgColor[bg]
        val priority = bgPriority[bg]

        color.fill(0)

        val bpp = BPP[bgMode][bg]
        if (bpp == 0) return

        val mosaic = if (bgMosaic[bg] && mosaicSize > 1) mosaicSize else 1
        val lineY = if (mosaic > 1) y - (y % mosaic) else y

        val big = bgTileBig[bg]
        val shift = if (big) 4 else 3
        val base = bgMapBase[bg]
        val wide = bgMapWide[bg]
        val tall = bgMapTall[bg]
        val charBase = bgCharBase[bg]
        val words = bpp * 4
        val offsets = bgMode == 2 || bgMode == 4 || bgMode == 6

        val hofs = bgHofs[bg]
        val vofs = bgVofs[bg]

        var x = 0
        while (x < WIDTH) {
            val column = x shr 3

            var scrollX = hofs
            var scrollY = vofs

            if (offsets && column > 0 && bg < 2) {
                val h = offsetH[column]
                val v = offsetV[column]
                val enable = 0x2000 shl bg

                if (bgMode == 4) {
                    if (h and enable != 0) {
                        if (h and 0x8000 != 0) {
                            scrollY = h and 0x3FF
                        } else {
                            scrollX = (h and 0x3F8) or (hofs and 7)
                        }
                    }
                } else {
                    if (h and enable != 0) scrollX = (h and 0x3F8) or (hofs and 7)
                    if (v and enable != 0) scrollY = v and 0x3FF
                }
            }

            val sx = (x + scrollX) and 0x3FF
            val sy = (lineY + scrollY) and 0x3FF

            val tileX = sx shr shift
            val tileY = sy shr shift

            var screen = 0
            if (wide && tileX and 0x20 != 0) screen += 1
            if (tall && tileY and 0x20 != 0) screen += if (wide) 2 else 1

            val entry = vram[(base + screen * 0x400 + (tileY and 0x1F) * 32 + (tileX and 0x1F)) and 0x7FFF].toInt() and 0xFFFF

            var character = entry and 0x3FF
            val palette = (entry ushr 10) and 7
            val prio = (entry ushr 13) and 1
            val flipX = entry and 0x4000 != 0
            val flipY = entry and 0x8000 != 0

            var fineX = sx and 7
            var fineY = sy and 7

            if (big) {
                var subX = (sx shr 3) and 1
                var subY = (sy shr 3) and 1
                if (flipX) subX = subX xor 1
                if (flipY) subY = subY xor 1
                character += subY * 16 + subX
            }

            if (flipY) fineY = fineY xor 7

            val row = (charBase + ((character and 0x3FF) * words) + fineY) and 0x7FFF

            val plane0 = vram[row].toInt() and 0xFFFF
            val plane1 = if (bpp >= 4) vram[(row + 8) and 0x7FFF].toInt() and 0xFFFF else 0
            val plane2 = if (bpp == 8) vram[(row + 16) and 0x7FFF].toInt() and 0xFFFF else 0
            val plane3 = if (bpp == 8) vram[(row + 24) and 0x7FFF].toInt() and 0xFFFF else 0

            val run = minOf(8 - fineX, WIDTH - x)

            for (i in 0 until run) {
                val column2 = fineX + i
                val bit = if (flipX) column2 else 7 - column2

                var index = ((plane0 ushr bit) and 1) or (((plane0 ushr (bit + 8)) and 1) shl 1)

                if (bpp >= 4) {
                    index = index or (((plane1 ushr bit) and 1) shl 2) or (((plane1 ushr (bit + 8)) and 1) shl 3)
                }

                if (bpp == 8) {
                    index = index or (((plane2 ushr bit) and 1) shl 4) or (((plane2 ushr (bit + 8)) and 1) shl 5)
                    index = index or (((plane3 ushr bit) and 1) shl 6) or (((plane3 ushr (bit + 8)) and 1) shl 7)
                }

                if (index != 0) {
                    color[x + i] = paletteIndex(bg, bpp, palette, index)
                    priority[x + i] = prio
                }
            }

            x += run
        }

        if (mosaic > 1) applyMosaic(color, priority, mosaic)
    }

    private fun applyMosaic(color: IntArray, priority: IntArray, size: Int) {
        var x = 0
        while (x < WIDTH) {
            val c = color[x]
            val p = priority[x]
            var i = 1
            while (i < size && x + i < WIDTH) {
                color[x + i] = c
                priority[x + i] = p
                i++
            }
            x += size
        }
    }

    private fun paletteIndex(bg: Int, bpp: Int, palette: Int, index: Int): Int = when (bpp) {
        2 -> if (bgMode == 0) bg * 32 + palette * 4 + index else palette * 4 + index
        4 -> palette * 16 + index
        else -> index
    }

    private fun computeOffsets(y: Int) {
        offsetH.fill(0)
        offsetV.fill(0)

        val base = bgMapBase[2]
        val wide = bgMapWide[2]
        val tall = bgMapTall[2]
        val big = bgTileBig[2]
        val shift = if (big) 4 else 3
        val hofs = bgHofs[2]
        val vofs = bgVofs[2]

        for (column in 1 until WIDTH / 8 + 1) {
            val px = (column * 8 - 8 + (hofs and 0x3F8)) and 0x3FF
            val py = vofs and 0x3FF

            val tileX = px shr shift
            val tileY = py shr shift

            var screen = 0
            if (wide && tileX and 0x20 != 0) screen += 1
            if (tall && tileY and 0x20 != 0) screen += if (wide) 2 else 1

            val index = (base + screen * 0x400 + (tileY and 0x1F) * 32 + (tileX and 0x1F)) and 0x7FFF

            offsetH[column] = vram[index].toInt() and 0xFFFF

            if (bgMode != 4) {
                offsetV[column] = vram[(index + 32) and 0x7FFF].toInt() and 0xFFFF
            }
        }
    }

    var debugM7Lines = 0
    var debugM7Distinct = 0
    private val debugM7Seen = HashSet<Int>()

    private fun renderMode7(y: Int) {
        debugM7Lines++
        if (debugM7Seen.add((m7[0] shl 16) or m7[3])) debugM7Distinct = debugM7Seen.size

        val color = bgColor[0]
        val priority = bgPriority[0]
        val color2 = bgColor[1]
        val priority2 = bgPriority[1]

        color.fill(0)
        color2.fill(0)
        priority.fill(0)
        priority2.fill(0)

        val mosaic = if (bgMosaic[0] && mosaicSize > 1) mosaicSize else 1
        val lineY = if (mosaic > 1) y - (y % mosaic) else y

        val a = signed16(m7[0])
        val b = signed16(m7[1])
        val c = signed16(m7[2])
        val d = signed16(m7[3])
        val cx = signed13(m7[4])
        val cy = signed13(m7[5])

        val hofs = signed13(m7Hofs)
        val vofs = signed13(m7Vofs)

        val flipX = m7Select and 1 != 0
        val flipY = m7Select and 2 != 0
        val over = (m7Select ushr 6) and 3

        val screenY = if (flipY) 255 - lineY else lineY

        val originX = clip(hofs - cx)
        val originY = clip(vofs - cy)

        var px = ((a * originX) and 0x3F.inv()) + ((b * originY) and 0x3F.inv()) +
            ((b * screenY) and 0x3F.inv()) + (cx shl 8)

        var py = ((c * originX) and 0x3F.inv()) + ((d * originY) and 0x3F.inv()) +
            ((d * screenY) and 0x3F.inv()) + (cy shl 8)

        val stepX = if (flipX) -a else a
        val stepY = if (flipX) -c else c

        if (flipX) {
            px += a * 255
            py += c * 255
        }

        for (x in 0 until WIDTH) {
            val vx = px shr 8
            val vy = py shr 8

            px += stepX
            py += stepY

            var tx = vx
            var ty = vy
            var outside = false

            if (tx and 0x3FF.inv() != 0 || ty and 0x3FF.inv() != 0) {
                when (over) {
                    0 -> {
                        tx = tx and 0x3FF
                        ty = ty and 0x3FF
                    }

                    2 -> {
                        outside = true
                    }

                    3 -> {
                        tx = tx and 7
                        ty = ty and 7
                    }

                    else -> {
                        tx = tx and 0x3FF
                        ty = ty and 0x3FF
                    }
                }
            }

            if (outside) continue

            val tile = if (over == 3 && (vx and 0x3FF.inv() != 0 || vy and 0x3FF.inv() != 0)) {
                0
            } else {
                vram[((ty shr 3) * 128 + (tx shr 3)) and 0x7FFF].toInt() and 0xFF
            }

            val pixel = (vram[(tile * 64 + (ty and 7) * 8 + (tx and 7)) and 0x7FFF].toInt() ushr 8) and 0xFF

            if (extbg) {
                val index = pixel and 0x7F
                if (index != 0) {
                    color2[x] = index
                    priority2[x] = (pixel ushr 7) and 1
                }
            } else if (pixel != 0) {
                color[x] = pixel
                priority[x] = 0
            }
        }

        if (mosaic > 1) {
            applyMosaic(color, priority, mosaic)
            if (extbg) applyMosaic(color2, priority2, mosaic)
        }
    }

    private fun evaluateSprites(y: Int) {
        objColor.fill(0)
        objPriority.fill(0)
        objPalette.fill(0)

        spriteCount = 0

        val first = if (oamPriority) (oamReload shr 2) and 0x7F else 0
        var slivers = 0

        for (i in 0 until 128) {
            val index = (first + i) and 0x7F
            val offset = index * 4

            val high = oam[512 + (index shr 2)].toInt() and 0xFF
            val bits = (high ushr ((index and 3) * 2)) and 3

            val large = bits and 2 != 0
            val width = SPRITE_WIDTH[objSize][if (large) 1 else 0]
            val height = SPRITE_HEIGHT[objSize][if (large) 1 else 0]

            var x = (oam[offset].toInt() and 0xFF) or ((bits and 1) shl 8)
            if (x >= 256) x -= 512

            val spriteY = oam[offset + 1].toInt() and 0xFF

            var row = y - spriteY
            if (row < 0) row += 256

            if (row >= height) continue
            if (x + width <= 0 || x >= WIDTH) continue

            if (spriteCount >= 32) {
                rangeOver = true
                break
            }

            spriteIndex[spriteCount++] = index
        }

        for (s in spriteCount - 1 downTo 0) {
            val index = spriteIndex[s]
            val offset = index * 4

            val high = oam[512 + (index shr 2)].toInt() and 0xFF
            val bits = (high ushr ((index and 3) * 2)) and 3

            val large = bits and 2 != 0
            val width = SPRITE_WIDTH[objSize][if (large) 1 else 0]
            val height = SPRITE_HEIGHT[objSize][if (large) 1 else 0]

            var x = (oam[offset].toInt() and 0xFF) or ((bits and 1) shl 8)
            if (x >= 256) x -= 512

            val spriteY = oam[offset + 1].toInt() and 0xFF
            val attr = oam[offset + 3].toInt() and 0xFF

            val character = (oam[offset + 2].toInt() and 0xFF) or ((attr and 1) shl 8)
            val palette = (attr ushr 1) and 7
            val prio = (attr ushr 4) and 3
            val flipX = attr and 0x40 != 0
            val flipY = attr and 0x80 != 0

            var row = y - spriteY
            if (row < 0) row += 256
            if (row >= height) continue

            if (flipY) row = height - 1 - row

            val tiles = width shr 3
            slivers += tiles

            if (slivers > 34) timeOver = true

            val base = if (attr and 1 != 0 || character >= 0x100) {
                objNameBase + ((objNameSelect + 1) shl 12)
            } else {
                objNameBase
            }

            for (tile in 0 until tiles) {
                val column = if (flipX) tiles - 1 - tile else tile
                val screenX = x + tile * 8

                if (screenX + 8 <= 0 || screenX >= WIDTH) continue

                val charX = ((character and 0x0F) + column) and 0x0F
                val charY = ((character shr 4) + (row shr 3)) and 0x0F
                val address = (base + (charY * 16 + charX) * 16 + (row and 7)) and 0x7FFF

                val plane0 = vram[address].toInt() and 0xFFFF
                val plane1 = vram[(address + 8) and 0x7FFF].toInt() and 0xFFFF

                for (i in 0 until 8) {
                    val px = screenX + i
                    if (px < 0 || px >= WIDTH) continue

                    val bit = if (flipX) i else 7 - i

                    var index2 = ((plane0 ushr bit) and 1) or (((plane0 ushr (bit + 8)) and 1) shl 1)
                    index2 = index2 or (((plane1 ushr bit) and 1) shl 2) or (((plane1 ushr (bit + 8)) and 1) shl 3)

                    if (index2 == 0) continue

                    objColor[px] = 128 + palette * 16 + index2
                    objPriority[px] = prio
                    objPalette[px] = palette
                }
            }
        }
    }

    private fun evaluateWindows() {
        for (layer in 0 until 6) {
            val mask = windowMask[layer]
            val enable = windowEnable[layer]

            if (enable == 0) {
                mask.fill(false)
                continue
            }

            val invert = windowInvert[layer]
            val logic = windowLogic[layer]

            for (x in 0 until WIDTH) {
                val in1 = if (enable and 1 != 0) {
                    val inside = x >= windowLeft[0] && x <= windowRight[0]
                    if (invert and 1 != 0) !inside else inside
                } else {
                    false
                }

                val in2 = if (enable and 2 != 0) {
                    val inside = x >= windowLeft[1] && x <= windowRight[1]
                    if (invert and 2 != 0) !inside else inside
                } else {
                    false
                }

                mask[x] = when {
                    enable == 1 -> in1
                    enable == 2 -> in2
                    logic == 0 -> in1 || in2
                    logic == 1 -> in1 && in2
                    logic == 2 -> in1 != in2
                    else -> in1 == in2
                }
            }
        }
    }

    private fun composite(row: Int) {
        val backdrop = cgram[0].toInt() and 0x7FFF
        val order = ORDER[if (bgMode == 1 && bg3Priority) 8 else bgMode]

        mainColor.fill(backdrop)
        mainLayer.fill(BACKDROP)
        mainDepth.fill(0)

        val useSub = cgwsel and 0x02 != 0

        subColor.fill(if (useSub) backdrop else fixedColor)
        subLayer.fill(BACKDROP)
        subDepth.fill(0)

        for (i in order.indices) {
            val layer = order[i] shr 2
            val prio = order[i] and 3
            val depth = i + 1

            drawLayer(layer, prio, depth, mainScreen, mainWindowMask, mainColor, mainLayer, mainDepth)

            if (useSub) {
                drawLayer(layer, prio, depth, subScreen, subWindowMask, subColor, subLayer, subDepth)
            }
        }

        val clip = (cgwsel ushr 6) and 3
        val prevent = (cgwsel ushr 4) and 3
        val colorMask = windowMask[COLOR_WINDOW]

        val subtract = cgadsub and 0x80 != 0
        val half = cgadsub and 0x40 != 0

        val base = row * WIDTH

        for (x in 0 until WIDTH) {
            val inside = colorMask[x]

            val clipped = when (clip) {
                0 -> false
                1 -> !inside
                2 -> inside
                else -> true
            }

            val blocked = when (prevent) {
                0 -> false
                1 -> !inside
                2 -> inside
                else -> true
            }

            var main = if (clipped) 0 else mainColor[x]

            val layer = mainLayer[x]
            val enabled = cgadsub and (1 shl layer) != 0
            val objMath = layer != OBJ || objPalette[x] >= 4

            if (!blocked && enabled && objMath) {
                val sub = subColor[x]
                val halve = half && !clipped && (!useSub || subLayer[x] != BACKDROP)
                main = if (subtract) blend(main, sub, true, halve) else blend(main, sub, false, halve)
            }

            frame[base + x] = apply(main).toShort()
        }
    }

    private fun drawLayer(
        layer: Int,
        prio: Int,
        depth: Int,
        screen: Int,
        mask: Int,
        color: IntArray,
        target: IntArray,
        depths: IntArray,
    ) {
        if (screen and (1 shl layer) == 0) return

        val windowed = mask and (1 shl layer) != 0
        val window = windowMask[layer]

        if (layer == OBJ) {
            for (x in 0 until WIDTH) {
                if (windowed && window[x]) continue
                if (objColor[x] == 0) continue
                if (objPriority[x] != prio) continue
                if (depth <= depths[x]) continue

                depths[x] = depth
                color[x] = cgram[objColor[x] and 0xFF].toInt() and 0x7FFF
                target[x] = OBJ
            }

            return
        }

        val pixels = bgColor[layer]
        val priorities = bgPriority[layer]

        val direct = cgwsel and 1 != 0 && (bgMode == 3 || bgMode == 4 || bgMode == 7)

        for (x in 0 until WIDTH) {
            if (windowed && window[x]) continue

            val index = pixels[x]
            if (index == 0) continue
            if (priorities[x] != prio) continue
            if (depth <= depths[x]) continue

            depths[x] = depth
            color[x] = if (direct && layer == 0) directColor(index) else cgram[index and 0xFF].toInt() and 0x7FFF
            target[x] = layer
        }
    }

    private fun directColor(index: Int): Int {
        val r = (index and 7) shl 2
        val g = ((index ushr 3) and 7) shl 2
        val b = ((index ushr 6) and 3) shl 3

        return r or (g shl 5) or (b shl 10)
    }

    private fun blend(main: Int, sub: Int, subtract: Boolean, half: Boolean): Int {
        var r = main and 0x1F
        var g = (main ushr 5) and 0x1F
        var b = (main ushr 10) and 0x1F

        val sr = sub and 0x1F
        val sg = (sub ushr 5) and 0x1F
        val sb = (sub ushr 10) and 0x1F

        if (subtract) {
            r -= sr
            g -= sg
            b -= sb
            if (r < 0) r = 0
            if (g < 0) g = 0
            if (b < 0) b = 0
        } else {
            r += sr
            g += sg
            b += sb
        }

        if (half) {
            r = r shr 1
            g = g shr 1
            b = b shr 1
        }

        if (r > 31) r = 31
        if (g > 31) g = 31
        if (b > 31) b = 31

        return r or (g shl 5) or (b shl 10)
    }

    private fun apply(color: Int): Int {
        if (brightness == 15) return color
        if (brightness == 0) return 0

        val r = BRIGHT[brightness * 32 + (color and 0x1F)]
        val g = BRIGHT[brightness * 32 + ((color ushr 5) and 0x1F)]
        val b = BRIGHT[brightness * 32 + ((color ushr 10) and 0x1F)]

        return r or (g shl 5) or (b shl 10)
    }

    private fun writeOam(value: Int) {
        if (oamAddress >= 0x200) {
            oam[0x200 + (oamAddress and 0x1F)] = value.toByte()
            oamAddress = (oamAddress + 1) and 0x3FF
            return
        }

        if (oamAddress and 1 == 0) {
            oamLatch = value
        } else {
            oam[(oamAddress - 1) and 0x1FF] = oamLatch.toByte()
            oam[oamAddress and 0x1FF] = value.toByte()
        }

        oamAddress = (oamAddress + 1) and 0x3FF
    }

    private fun remapped(): Int {
        val addr = vramAddress and 0x7FFF

        return when (vramRemap) {
            0 -> addr
            1 -> (addr and 0x7F00) or ((addr and 0x001F) shl 3) or ((addr ushr 5) and 7)
            2 -> (addr and 0x7E00) or ((addr and 0x003F) shl 3) or ((addr ushr 6) and 7)
            else -> (addr and 0x7C00) or ((addr and 0x007F) shl 3) or ((addr ushr 7) and 7)
        }
    }

    private fun multiply(): Int {
        val a = signed16(m7[0])
        val b = (m7[1] ushr 8) and 0xFF
        return a * signed8(b)
    }

    private fun signed8(value: Int): Int = if (value and 0x80 != 0) value - 0x100 else value

    private fun signed16(value: Int): Int = if (value and 0x8000 != 0) value - 0x10000 else value

    private fun signed13(value: Int): Int = if (value and 0x1000 != 0) value - 0x2000 else value

    private fun clip(value: Int): Int = if (value and 0x2000 != 0) value or 1023.inv() else value and 1023

    fun save(writer: StateWriter) {
        writer.shorts(vram)
        writer.shorts(cgram)
        writer.bytes(oam)
        writer.bool(forcedBlank)
        writer.int(brightness)
        writer.int(oamAddress)
        writer.int(oamReload)
        writer.bool(oamPriority)
        writer.int(oamLatch)
        writer.int(vramAddress)
        writer.bool(vramIncrementHigh)
        writer.int(vramStep)
        writer.int(vramRemap)
        writer.int(vramLatch)
        writer.int(cgramAddress)
        writer.int(cgramLatch)
        writer.bool(cgramHigh)
        writer.int(bgMode)
        writer.bool(bg3Priority)
        writer.int(mosaicSize)
        writer.ints(bgMapBase)
        writer.ints(bgCharBase)
        writer.ints(bgHofs)
        writer.ints(bgVofs)
        writer.int(scrollLatch)
        writer.int(scrollHighLatch)
        writer.int(objNameBase)
        writer.int(objNameSelect)
        writer.int(objSize)
        writer.ints(m7)
        writer.int(m7Latch)
        writer.int(m7Select)
        writer.int(m7Hofs)
        writer.int(m7Vofs)
        writer.int(mainScreen)
        writer.int(subScreen)
        writer.int(mainWindowMask)
        writer.int(subWindowMask)
        writer.ints(windowLeft)
        writer.ints(windowRight)
        writer.ints(windowEnable)
        writer.ints(windowInvert)
        writer.ints(windowLogic)
        writer.int(cgwsel)
        writer.int(cgadsub)
        writer.int(fixedColor)
        writer.bool(overscan)
        writer.bool(interlace)
        writer.bool(pseudoHires)
        writer.bool(extbg)

        for (i in 0 until 4) {
            writer.bool(bgTileBig[i])
            writer.bool(bgMapWide[i])
            writer.bool(bgMapTall[i])
            writer.bool(bgMosaic[i])
        }
    }

    fun load(reader: StateReader) {
        reader.shorts(vram)
        reader.shorts(cgram)
        reader.bytes(oam)
        forcedBlank = reader.bool()
        brightness = reader.int()
        oamAddress = reader.int()
        oamReload = reader.int()
        oamPriority = reader.bool()
        oamLatch = reader.int()
        vramAddress = reader.int()
        vramIncrementHigh = reader.bool()
        vramStep = reader.int()
        vramRemap = reader.int()
        vramLatch = reader.int()
        cgramAddress = reader.int()
        cgramLatch = reader.int()
        cgramHigh = reader.bool()
        bgMode = reader.int()
        bg3Priority = reader.bool()
        mosaicSize = reader.int()
        reader.ints(bgMapBase)
        reader.ints(bgCharBase)
        reader.ints(bgHofs)
        reader.ints(bgVofs)
        scrollLatch = reader.int()
        scrollHighLatch = reader.int()
        objNameBase = reader.int()
        objNameSelect = reader.int()
        objSize = reader.int()
        reader.ints(m7)
        m7Latch = reader.int()
        m7Select = reader.int()
        m7Hofs = reader.int()
        m7Vofs = reader.int()
        mainScreen = reader.int()
        subScreen = reader.int()
        mainWindowMask = reader.int()
        subWindowMask = reader.int()
        reader.ints(windowLeft)
        reader.ints(windowRight)
        reader.ints(windowEnable)
        reader.ints(windowInvert)
        reader.ints(windowLogic)
        cgwsel = reader.int()
        cgadsub = reader.int()
        fixedColor = reader.int()
        overscan = reader.bool()
        interlace = reader.bool()
        pseudoHires = reader.bool()
        extbg = reader.bool()

        for (i in 0 until 4) {
            bgTileBig[i] = reader.bool()
            bgMapWide[i] = reader.bool()
            bgMapTall[i] = reader.bool()
            bgMosaic[i] = reader.bool()
        }
    }

    companion object {
        const val WIDTH = 256
        const val HEIGHT = 224
        const val OVERSCAN_HEIGHT = 239

        const val OBJ = 4
        const val BACKDROP = 5
        const val COLOR_WINDOW = 5

        private const val PPU1_VERSION = 1
        private const val PPU2_VERSION = 3

        private val BPP = arrayOf(
            intArrayOf(2, 2, 2, 2),
            intArrayOf(4, 4, 2, 0),
            intArrayOf(4, 4, 0, 0),
            intArrayOf(8, 4, 0, 0),
            intArrayOf(8, 2, 0, 0),
            intArrayOf(4, 2, 0, 0),
            intArrayOf(4, 0, 0, 0),
            intArrayOf(8, 8, 0, 0),
        )

        private val LAYER_COUNT = intArrayOf(4, 3, 2, 2, 2, 2, 1, 2)

        private fun entry(layer: Int, priority: Int) = (layer shl 2) or priority

        private val ORDER = arrayOf(
            intArrayOf(
                entry(3, 0), entry(2, 0), entry(OBJ, 0), entry(3, 1), entry(2, 1), entry(OBJ, 1),
                entry(1, 0), entry(0, 0), entry(OBJ, 2), entry(1, 1), entry(0, 1), entry(OBJ, 3),
            ),
            intArrayOf(
                entry(2, 0), entry(OBJ, 0), entry(1, 0), entry(0, 0), entry(OBJ, 1),
                entry(1, 1), entry(0, 1), entry(OBJ, 2), entry(2, 1), entry(OBJ, 3),
            ),
            intArrayOf(
                entry(1, 0), entry(OBJ, 0), entry(0, 0), entry(OBJ, 1),
                entry(1, 1), entry(OBJ, 2), entry(0, 1), entry(OBJ, 3),
            ),
            intArrayOf(
                entry(1, 0), entry(OBJ, 0), entry(0, 0), entry(OBJ, 1),
                entry(1, 1), entry(OBJ, 2), entry(0, 1), entry(OBJ, 3),
            ),
            intArrayOf(
                entry(1, 0), entry(OBJ, 0), entry(0, 0), entry(OBJ, 1),
                entry(1, 1), entry(OBJ, 2), entry(0, 1), entry(OBJ, 3),
            ),
            intArrayOf(
                entry(1, 0), entry(OBJ, 0), entry(0, 0), entry(OBJ, 1),
                entry(1, 1), entry(OBJ, 2), entry(0, 1), entry(OBJ, 3),
            ),
            intArrayOf(
                entry(OBJ, 0), entry(0, 0), entry(OBJ, 1), entry(OBJ, 2), entry(0, 1), entry(OBJ, 3),
            ),
            intArrayOf(
                entry(1, 0), entry(OBJ, 0), entry(0, 0), entry(OBJ, 1), entry(1, 1), entry(OBJ, 2), entry(OBJ, 3),
            ),
            intArrayOf(
                entry(2, 0), entry(OBJ, 0), entry(1, 0), entry(0, 0), entry(OBJ, 1),
                entry(1, 1), entry(0, 1), entry(OBJ, 2), entry(OBJ, 3), entry(2, 1),
            ),
        )

        private val SPRITE_WIDTH = arrayOf(
            intArrayOf(8, 16), intArrayOf(8, 32), intArrayOf(8, 64), intArrayOf(16, 32),
            intArrayOf(16, 64), intArrayOf(32, 64), intArrayOf(16, 32), intArrayOf(16, 32),
        )

        private val SPRITE_HEIGHT = arrayOf(
            intArrayOf(8, 16), intArrayOf(8, 32), intArrayOf(8, 64), intArrayOf(16, 32),
            intArrayOf(16, 64), intArrayOf(32, 64), intArrayOf(32, 64), intArrayOf(32, 32),
        )

        private val BRIGHT = IntArray(16 * 32) { index ->
            val level = index / 32
            val value = index % 32
            (value * level + 7) / 15
        }
    }
}
