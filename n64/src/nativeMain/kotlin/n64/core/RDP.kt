package n64.core

const val DPC_START = 0
const val DPC_END = 1
const val DPC_CURRENT = 2
const val DPC_STATUS = 3
const val DPC_CLOCK = 4
const val DPC_BUFBUSY = 5
const val DPC_PIPEBUSY = 6
const val DPC_TMEM = 7

const val DPC_STATUS_XBUS = 1 shl 0
const val DPC_STATUS_FREEZE = 1 shl 1
const val DPC_STATUS_FLUSH = 1 shl 2
const val DPC_STATUS_START_GCLK = 1 shl 3
const val DPC_STATUS_CBUF_READY = 1 shl 7
const val DPC_STATUS_START_VALID = 1 shl 10

const val CYCLE_1 = 0
const val CYCLE_2 = 1
const val CYCLE_COPY = 2
const val CYCLE_FILL = 3

class Tile {
    var format = 0
    var size = 0
    var line = 0
    var tmem = 0
    var palette = 0
    var clampS = 0
    var mirrorS = 0
    var maskS = 0
    var shiftS = 0
    var clampT = 0
    var mirrorT = 0
    var maskT = 0
    var shiftT = 0
    var sl = 0
    var tl = 0
    var sh = 0
    var th = 0
    var clampDiffS = 0
    var clampDiffT = 0
    var clampEnS = false
    var clampEnT = false
    var maskSClamped = 0
    var maskTClamped = 0

    fun updateDerivs() {
        clampDiffS = ((sh shr 2) - (sl shr 2)) and 0x3FF
        clampDiffT = ((th shr 2) - (tl shr 2)) and 0x3FF
        clampEnS = clampS != 0 || maskS == 0
        clampEnT = clampT != 0 || maskT == 0
        maskSClamped = if (maskS <= 10) maskS else 10
        maskTClamped = if (maskT <= 10) maskT else 10
    }
}

class Rdp(private val n64: N64) {

    val regsDpc = IntArray(8)
    val regsDps = IntArray(4)

    val tmem = ByteArray(4096)
    private val tiles = Array(8) { Tile() }

    private val commands = IntArray(44)
    private val edgeCoefficients = IntArray(44)

    private val tcdivTable = IntArray(0x8000)

    private val zComTable = IntArray(0x40000)
    private val zDecTable = IntArray(0x4000)
    private val deltazLut = IntArray(0x10000)

    private val spanAccel = RdpSpanAccel(n64.rdram, n64.hidden, tmem, zComTable, zDecTable, deltazLut, tcdivTable) { lo, hi ->
        n64.rdpInvalidateWords(lo, hi)
    }
    private val spanUniforms = IntArray(RdpSpanAccel.UNIFORM_WORDS)
    private var tmemGeneration = 0

    private var spanDzPix = 0
    private var spanDzPixEnc = 0
    private var primDz = 0
    private var primDzEnc = 0

    init {
        for (z in 0 until 0x40000) {
            val exponent = (z shr 11) and 0x7F
            zComTable[z] = when {
                exponent < 0x40 -> (z shr 4) and 0x1FFC
                exponent < 0x60 -> ((z shr 3) and 0x1FFC) or 0x2000
                exponent < 0x70 -> ((z shr 2) and 0x1FFC) or 0x4000
                exponent < 0x78 -> ((z shr 1) and 0x1FFC) or 0x6000
                exponent < 0x7C -> (z and 0x1FFC) or 0x8000
                exponent < 0x7E -> ((z shl 1) and 0x1FFC) or 0xA000
                exponent < 0x7F -> ((z shl 2) and 0x1FFC) or 0xC000
                else -> ((z shl 2) and 0x1FFC) or 0xE000
            }
        }

        val decShift = intArrayOf(6, 5, 4, 3, 2, 1, 0, 0)
        val decAdd = intArrayOf(0x00000, 0x20000, 0x30000, 0x38000, 0x3C000, 0x3E000, 0x3F000, 0x3F800)
        for (i in 0 until 0x4000) {
            val exponent = (i shr 11) and 7
            val mantissa = i and 0x7FF
            zDecTable[i] = ((mantissa shl decShift[exponent]) + decAdd[exponent]) and 0x3FFFF
        }

        deltazLut[0] = 0
        for (i in 1 until 0x10000) {
            for (k in 15 downTo 0) {
                if (i and (1 shl k) != 0) {
                    deltazLut[i] = 1 shl k
                    break
                }
            }
        }

        for (i in 0 until 0x8000) {
            var k = 1
            while (k <= 14 && (i shl k) and 0x8000 == 0) k++
            val shift = k - 1
            var normout = (i shl shift) and 0x3FFF
            val wnorm = (normout and 0xFF) shl 2
            normout = normout shr 8

            val point = NORM_POINT[normout]
            val slope = (NORM_SLOPE[normout] or 0x3FF.inv()) + 1

            val tluRcp = (((slope * wnorm) shr 10) + point) and 0x7FFF
            tcdivTable[i] = shift or (tluRcp shl 4)
        }
    }

    private var cycleType = CYCLE_1
    private var perspective = false
    private var detailTexture = false
    private var sharpenTexture = false
    private var lodEnable = false
    private var tlutEnable = false
    private var tlutType = 0
    private var sampleType = 0
    private var midTexel = false
    private var bilerp0 = false
    private var bilerp1 = false
    private var convertOne = false
    private var keyEnable = false
    private var alphaDither = 0
    private var rgbDither = 0

    private var forceBlend = false
    private var alphaCoverageSelect = false
    private var coverageTimesAlpha = false
    private var zMode = 0
    private var coverageDestination = 0
    private var colorOnCoverage = false
    private var imageReadEnable = false
    private var zUpdate = false
    private var zCompare = false
    private var antialias = false
    private var zSourceSelect = false
    private var ditherAlpha = false
    private var alphaCompare = false

    private val blendA = IntArray(2)
    private val blendB = IntArray(2)
    private val blendC = IntArray(2)
    private val blendD = IntArray(2)

    private val combineRgbA = IntArray(2)
    private val combineRgbB = IntArray(2)
    private val combineRgbC = IntArray(2)
    private val combineRgbD = IntArray(2)
    private val combineAlphaA = IntArray(2)
    private val combineAlphaB = IntArray(2)
    private val combineAlphaC = IntArray(2)
    private val combineAlphaD = IntArray(2)

    private var fillColor = 0
    private var fogColor = 0
    private var blendColor = 0
    private var primColor = 0
    private var envColor = 0
    private var fogPacked = 0
    private var blendPacked = 0
    private var primPacked = 0
    private var envPacked = 0
    private var primDepth = 0
    private var primLodFrac = 0

    private var colorImage = 0
    private var colorFormat = 0
    private var colorSize = 0
    private var colorWidth = 0

    private var zImage = 0

    private var textureImage = 0
    private var textureFormat = 0
    private var textureSize = 0
    private var textureWidth = 0

    private var scissorXh = 0
    private var scissorYh = 0
    private var scissorXl = 0
    private var scissorYl = 0

    private var clipXh = 0
    private var clipYh = 0
    private var clipXl = 0
    private var clipYl = 0

    private val spanLx = IntArray(SPANS)
    private val spanRx = IntArray(SPANS)
    private val spanUnscrx = IntArray(SPANS)
    private val spanValid = BooleanArray(SPANS)
    private val spanMinorX = IntArray(SPANS * 4)
    private val spanMajorX = IntArray(SPANS * 4)
    private val spanInvalY = IntArray(SPANS * 4)

    private val spanR = IntArray(SPANS)
    private val spanG = IntArray(SPANS)
    private val spanB = IntArray(SPANS)
    private val spanA = IntArray(SPANS)
    private val spanS = IntArray(SPANS)
    private val spanT = IntArray(SPANS)
    private val spanW = IntArray(SPANS)
    private val spanZ = IntArray(SPANS)

    private var spansDr = 0
    private var spansDg = 0
    private var spansDb = 0
    private var spansDa = 0
    private var spansDs = 0
    private var spansDt = 0
    private var spansDw = 0
    private var spansDz = 0

    var triangles = 0
        private set
    var rectangles = 0
        private set
    var pixels = 0L
        private set
    var commandsSeen = 0L
        private set
    val debugOpcodes = IntArray(64)

    val gpuDispatches: Long get() = spanAccel.dispatched
    val gpuPixels: Long get() = spanAccel.offloadedPixels
    val gpuActive: Boolean get() = !spanAccel.disabled

    val spanFail: Int get() = spanAccel.failReason
    val spanSubmitted: Long get() = spanAccel.spansSubmitted
    val spanGroups: Long get() = spanAccel.groupsSubmitted
    val spanBreakTmem: Long get() = spanAccel.breakTmem
    val spanBreakUniform: Long get() = spanAccel.breakUniform
    val spanBreakCapacity: Long get() = spanAccel.breakCapacity
    val spanBreakForced: Long get() = spanAccel.breakForced
    val spanRebinds: Int get() = spanAccel.rebinds
    val spanFlushes: Int get() = spanAccel.flushes

    fun reset() {
        regsDpc.fill(0)
        regsDps.fill(0)
        tmem.fill(0)
        for (tile in tiles) {
            tile.format = 0
            tile.size = 0
            tile.line = 0
            tile.tmem = 0
            tile.palette = 0
            tile.clampS = 0
            tile.mirrorS = 0
            tile.maskS = 0
            tile.shiftS = 0
            tile.clampT = 0
            tile.mirrorT = 0
            tile.maskT = 0
            tile.shiftT = 0
            tile.sl = 0
            tile.tl = 0
            tile.sh = 0
            tile.th = 0
            tile.updateDerivs()
        }
        regsDpc[DPC_STATUS] = DPC_STATUS_CBUF_READY
        cycleType = CYCLE_1
        triangles = 0
        rectangles = 0
        pixels = 0
    }

    fun readDpc(reg: Int): Int {
        n64.cpu.addCycles(20)
        return when (reg) {
            DPC_CURRENT -> regsDpc[DPC_CURRENT]
            else -> if (reg < 8) regsDpc[reg] else 0
        }
    }

    fun writeDpc(reg: Int, value: Int, mask: Int) {
        when (reg) {
            DPC_START -> {
                if (regsDpc[DPC_STATUS] and DPC_STATUS_START_VALID == 0) {
                    regsDpc[DPC_START] = (value and mask) and 0xFFFFF8.inv().inv()
                    regsDpc[DPC_STATUS] = regsDpc[DPC_STATUS] or DPC_STATUS_START_VALID
                }
            }

            DPC_END -> {
                regsDpc[DPC_END] = (regsDpc[DPC_END] and mask.inv()) or (value and mask)
                if (regsDpc[DPC_STATUS] and DPC_STATUS_START_VALID != 0) {
                    regsDpc[DPC_CURRENT] = regsDpc[DPC_START]
                    regsDpc[DPC_STATUS] = regsDpc[DPC_STATUS] and DPC_STATUS_START_VALID.inv()
                }
                process()
            }

            DPC_STATUS -> updateStatus(value and mask)

            else -> if (reg < 8) regsDpc[reg] = (regsDpc[reg] and mask.inv()) or (value and mask)
        }
    }

    fun readDps(reg: Int): Int = if (reg < 4) regsDps[reg] else 0

    fun writeDps(reg: Int, value: Int, mask: Int) {
        if (reg < 4) regsDps[reg] = (regsDps[reg] and mask.inv()) or (value and mask)
    }

    private fun updateStatus(w: Int) {
        if (w and (1 shl 0) != 0) regsDpc[DPC_STATUS] = regsDpc[DPC_STATUS] and DPC_STATUS_XBUS.inv()
        if (w and (1 shl 1) != 0) regsDpc[DPC_STATUS] = regsDpc[DPC_STATUS] or DPC_STATUS_XBUS
        if (w and (1 shl 2) != 0) regsDpc[DPC_STATUS] = regsDpc[DPC_STATUS] and DPC_STATUS_FREEZE.inv()
        if (w and (1 shl 3) != 0) regsDpc[DPC_STATUS] = regsDpc[DPC_STATUS] or DPC_STATUS_FREEZE
        if (w and (1 shl 4) != 0) regsDpc[DPC_STATUS] = regsDpc[DPC_STATUS] and DPC_STATUS_FLUSH.inv()
        if (w and (1 shl 5) != 0) regsDpc[DPC_STATUS] = regsDpc[DPC_STATUS] or DPC_STATUS_FLUSH
        if (w and (1 shl 6) != 0) regsDpc[DPC_TMEM] = 0
        if (w and (1 shl 7) != 0) regsDpc[DPC_PIPEBUSY] = 0
        if (w and (1 shl 8) != 0) regsDpc[DPC_BUFBUSY] = 0
        if (w and (1 shl 9) != 0) regsDpc[DPC_CLOCK] = 0
    }

    private var pendingInterrupt = false
    private var workPixels = 0L

    fun taskFinished(cycles: Long) {
        if (!pendingInterrupt) return
        pendingInterrupt = false

        val work = workPixels
        workPixels = 0

        n64.createEvent(EVENT_DP, cycles + 100 + work / 2)
    }

    fun interruptEvent() {
        n64.mi.setInterrupt(MI_INTR_DP)
    }

    private fun readCommand(at: Int): Int =
        if (regsDpc[DPC_STATUS] and DPC_STATUS_XBUS != 0) {
            n64.rsp.readMem(at and 0xFFF)
        } else {
            n64.ramRead32(at)
        }

    private fun process() {
        if (regsDpc[DPC_STATUS] and DPC_STATUS_FREEZE != 0) return

        var current = regsDpc[DPC_CURRENT]
        val end = regsDpc[DPC_END]

        while (current < end) {
            val word0 = readCommand(current)
            val opcode = (word0 ushr 24) and 0x3F
            val length = COMMAND_LENGTH[opcode] * 8

            if (current + length > end) break

            for (i in 0 until length / 4) {
                commands[i] = readCommand(current + i * 4)
            }

            commandsSeen++
            debugOpcodes[opcode]++
            execute(opcode)
            current += length
        }

        regsDpc[DPC_CURRENT] = current
    }

    fun flushDisplay() {
        spanAccel.flush()
    }

    val diagCombine = LongArray(128)
    val diagModes = LongArray(128)
    val diagPixels = LongArray(128)
    var diagEnabled = false
    private var combineKey = 0L
    private var modesKey = 0L

    private fun diagRecord(before: Long) {
        val delta = workPixels - before
        if (delta == 0L) return
        for (i in 0 until 128) {
            if (diagPixels[i] != 0L && diagCombine[i] == combineKey && diagModes[i] == modesKey) {
                diagPixels[i] += delta
                return
            }
            if (diagPixels[i] == 0L) {
                diagCombine[i] = combineKey
                diagModes[i] = modesKey
                diagPixels[i] = delta
                return
            }
        }
    }

    private fun execute(opcode: Int) {
        if (diagEnabled && (opcode in 0x08..0x0F || opcode == 0x24 || opcode == 0x25 || opcode == 0x36)) {
            val before = workPixels
            executeInner(opcode)
            diagRecord(before)
            return
        }
        executeInner(opcode)
    }

    private fun executeInner(opcode: Int) {
        when (opcode) {
            0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F -> triangle(opcode)
            0x24, 0x25 -> textureRectangle(opcode == 0x25)
            0x27 -> {}
            0x29 -> {
                regsDpc[DPC_STATUS] = regsDpc[DPC_STATUS] and DPC_STATUS_START_GCLK.inv()
                if (n64.rsp.running) pendingInterrupt = true else n64.createEvent(EVENT_DP, 100)
            }

            0x26, 0x28 -> {}
            0x2D -> {
                clipXh = (commands[0] ushr 12) and 0xFFF
                clipYh = commands[0] and 0xFFF
                clipXl = (commands[1] ushr 12) and 0xFFF
                clipYl = commands[1] and 0xFFF

                scissorXh = clipXh shr 2
                scissorYh = clipYh shr 2
                scissorXl = clipXl shr 2
                scissorYl = clipYl shr 2
            }

            0x2E -> {
                primDepth = (commands[1] ushr 16) and 0x7FFF
                primDz = commands[1] and 0xFFFF
                primDzEnc = dzCompress(primDz)
            }
            0x2F -> setOtherModes()
            0x30 -> loadTlut()
            0x32 -> setTileSize()
            0x33 -> loadBlock()
            0x34 -> loadTile()
            0x35 -> setTile()
            0x36 -> fillRectangle()
            0x37 -> fillColor = commands[1]
            0x38 -> {
                fogColor = commands[1]
                fogPacked = swizzle(fogColor)
            }

            0x39 -> {
                blendColor = commands[1]
                blendPacked = swizzle(blendColor)
            }
            0x3A -> {
                primLodFrac = commands[0] and 0xFF
                primColor = commands[1]
                primPacked = swizzle(primColor)
            }

            0x3B -> {
                envColor = commands[1]
                envPacked = swizzle(envColor)
            }
            0x3C -> setCombine()
            0x3D -> {
                textureFormat = (commands[0] ushr 21) and 7
                textureSize = (commands[0] ushr 19) and 3
                textureWidth = (commands[0] and 0x3FF) + 1
                textureImage = commands[1] and 0xFFFFFF
            }

            0x3E -> {
                val next = commands[1] and 0xFFFFFF
                if (next != zImage) spanAccel.flush()
                zImage = next
            }
            0x3F -> {
                colorFormat = (commands[0] ushr 21) and 7
                colorSize = (commands[0] ushr 19) and 3
                colorWidth = (commands[0] and 0x3FF) + 1
                val next = commands[1] and 0xFFFFFF
                if (next != colorImage) spanAccel.flush()
                colorImage = next
            }
        }
    }

    private fun setOtherModes() {
        val w0 = commands[0]
        val w1 = commands[1]
        modesKey = (w0.toLong() shl 32) or (w1.toLong() and 0xFFFFFFFFL)

        cycleType = (w0 ushr 20) and 3
        perspective = w0 and (1 shl 19) != 0
        detailTexture = w0 and (1 shl 18) != 0
        sharpenTexture = w0 and (1 shl 17) != 0
        lodEnable = w0 and (1 shl 16) != 0
        val tlutEnableNew = w0 and (1 shl 15) != 0
        val tlutTypeNew = (w0 ushr 14) and 1
        tlutEnable = tlutEnableNew
        tlutType = tlutTypeNew
        sampleType = (w0 ushr 13) and 1
        midTexel = w0 and (1 shl 12) != 0
        bilerp0 = w0 and (1 shl 11) != 0
        bilerp1 = w0 and (1 shl 10) != 0
        convertOne = w0 and (1 shl 9) != 0
        keyEnable = w0 and (1 shl 8) != 0
        rgbDither = (w0 ushr 6) and 3
        alphaDither = (w0 ushr 4) and 3

        blendA[0] = (w1 ushr 30) and 3
        blendA[1] = (w1 ushr 28) and 3
        blendB[0] = (w1 ushr 26) and 3
        blendB[1] = (w1 ushr 24) and 3
        blendC[0] = (w1 ushr 22) and 3
        blendC[1] = (w1 ushr 20) and 3
        blendD[0] = (w1 ushr 18) and 3
        blendD[1] = (w1 ushr 16) and 3

        forceBlend = w1 and (1 shl 14) != 0
        alphaCoverageSelect = w1 and (1 shl 13) != 0
        coverageTimesAlpha = w1 and (1 shl 12) != 0
        zMode = (w1 ushr 10) and 3
        coverageDestination = (w1 ushr 8) and 3
        colorOnCoverage = w1 and (1 shl 7) != 0
        imageReadEnable = w1 and (1 shl 6) != 0
        zUpdate = w1 and (1 shl 5) != 0
        zCompare = w1 and (1 shl 4) != 0
        antialias = w1 and (1 shl 3) != 0
        zSourceSelect = w1 and (1 shl 2) != 0
        ditherAlpha = w1 and (1 shl 1) != 0
        alphaCompare = w1 and (1 shl 0) != 0
    }

    private fun setCombine() {
        val w0 = commands[0]
        val w1 = commands[1]
        combineKey = (w0.toLong() shl 32) or (w1.toLong() and 0xFFFFFFFFL)

        combineRgbA[0] = (w0 ushr 20) and 0xF
        combineRgbC[0] = (w0 ushr 15) and 0x1F
        combineAlphaA[0] = (w0 ushr 12) and 7
        combineAlphaC[0] = (w0 ushr 9) and 7
        combineRgbA[1] = (w0 ushr 5) and 0xF
        combineRgbC[1] = w0 and 0x1F

        combineRgbB[0] = (w1 ushr 28) and 0xF
        combineRgbB[1] = (w1 ushr 24) and 0xF
        combineAlphaA[1] = (w1 ushr 21) and 7
        combineAlphaC[1] = (w1 ushr 18) and 7
        combineRgbD[0] = (w1 ushr 15) and 7
        combineAlphaB[0] = (w1 ushr 12) and 7
        combineAlphaD[0] = (w1 ushr 9) and 7
        combineRgbD[1] = (w1 ushr 6) and 7
        combineAlphaB[1] = (w1 ushr 3) and 7
        combineAlphaD[1] = w1 and 7
    }

    private fun setTile() {
        val w0 = commands[0]
        val w1 = commands[1]
        val tile = tiles[(w1 ushr 24) and 7]

        tile.format = (w0 ushr 21) and 7
        tile.size = (w0 ushr 19) and 3
        tile.line = (w0 ushr 9) and 0x1FF
        tile.tmem = w0 and 0x1FF
        tile.palette = (w1 ushr 20) and 0xF
        tile.clampT = (w1 ushr 19) and 1
        tile.mirrorT = (w1 ushr 18) and 1
        tile.maskT = (w1 ushr 14) and 0xF
        tile.shiftT = (w1 ushr 10) and 0xF
        tile.clampS = (w1 ushr 9) and 1
        tile.mirrorS = (w1 ushr 8) and 1
        tile.maskS = (w1 ushr 4) and 0xF
        tile.shiftS = w1 and 0xF
        tile.updateDerivs()
    }

    private fun setTileSize() {
        val w0 = commands[0]
        val w1 = commands[1]
        val tile = tiles[(w1 ushr 24) and 7]

        tile.sl = (w0 ushr 12) and 0xFFF
        tile.tl = w0 and 0xFFF
        tile.sh = (w1 ushr 12) and 0xFFF
        tile.th = w1 and 0xFFF
        tile.updateDerivs()
    }

    private fun loadTile() {
        val w0 = commands[0]
        val w1 = commands[1]
        val tile = tiles[(w1 ushr 24) and 7]

        tile.sl = (w0 ushr 12) and 0xFFF
        tile.tl = w0 and 0xFFF
        tile.sh = (w1 ushr 12) and 0xFFF
        tile.th = w1 and 0xFFF
        tile.updateDerivs()

        val sl = tile.sl shr 2
        val tl = tile.tl shr 2
        val sh = tile.sh shr 2
        val th = tile.th shr 2

        val bytes = textureBytes(textureSize)

        spanAccel.flushIfTouches(textureImage, textureImage + (th + 1) * textureWidth * maxOf(bytes, 1))

        for (t in tl..th) {
            val row = t - tl
            for (s in sl..sh) {
                val column = s - sl
                when (textureSize) {
                    0 -> {
                        val source = textureImage + t * textureWidth / 2 + s / 2
                        val target = tile.tmem * 8 + row * tile.line * 8 + column / 2
                        writeTmem(target, row, n64.ramRead8(source))
                    }

                    1 -> {
                        val source = textureImage + t * textureWidth + s
                        val target = tile.tmem * 8 + row * tile.line * 8 + column
                        writeTmem(target, row, n64.ramRead8(source))
                    }

                    2 -> {
                        val source = textureImage + (t * textureWidth + s) * 2
                        val target = tile.tmem * 8 + row * tile.line * 8 + column * 2
                        writeTmem(target, row, n64.ramRead8(source))
                        writeTmem(target + 1, row, n64.ramRead8(source + 1))
                    }

                    3 -> {
                        val source = textureImage + (t * textureWidth + s) * 4
                        val target = tile.tmem * 8 + row * tile.line * 8 + column * 2
                        writeTmem32(target, row, n64.ramRead8(source), n64.ramRead8(source + 1))
                        writeTmem32(target + 1, row, n64.ramRead8(source + 2), n64.ramRead8(source + 3))
                    }
                }
            }
        }

        if (bytes == 0) return
    }

    private fun writeTmem(offset: Int, row: Int, value: Int) {
        var at = offset
        if (row and 1 != 0) at = at xor 4
        tmem[at and 0xFFF] = value.toByte()
        tmemGeneration++
    }

    private fun writeTmem32(offset: Int, row: Int, high: Int, low: Int) {
        var at = offset
        if (row and 1 != 0) at = at xor 4
        tmem[at and 0x7FF] = high.toByte()
        tmem[(at and 0x7FF) or 0x800] = low.toByte()
        tmemGeneration++
    }

    private fun loadBlock() {
        val w0 = commands[0]
        val w1 = commands[1]
        val tile = tiles[(w1 ushr 24) and 7]

        val sl = (w0 ushr 12) and 0xFFF
        val tl = w0 and 0xFFF
        val sh = (w1 ushr 12) and 0xFFF
        val dxt = w1 and 0xFFF

        tile.sl = sl
        tile.tl = tl
        tile.sh = sh
        tile.updateDerivs()

        val count = sh - sl + 1
        val bytes = when (textureSize) {
            0 -> count / 2
            1 -> count
            2 -> count * 2
            else -> count * 4
        }

        val source = textureImage + when (textureSize) {
            0 -> sl / 2 + tl * textureWidth / 2
            1 -> sl + tl * textureWidth
            2 -> (sl + tl * textureWidth) * 2
            else -> (sl + tl * textureWidth) * 4
        }

        spanAccel.flushIfTouches(source, source + bytes)

        var line = 0
        var accumulator = 0

        var i = 0
        while (i < bytes) {
            if (textureSize == 3) {
                writeTmem32(tile.tmem * 8 + i / 2, line, n64.ramRead8(source + i), n64.ramRead8(source + i + 1))
                writeTmem32(
                    tile.tmem * 8 + i / 2 + 1,
                    line,
                    n64.ramRead8(source + i + 2),
                    n64.ramRead8(source + i + 3),
                )
                i += 4
            } else {
                writeTmem(tile.tmem * 8 + i, line, n64.ramRead8(source + i))
                i++
            }

            if (i % 8 == 0 && dxt != 0) {
                accumulator += dxt
                if (accumulator >= 0x800) {
                    accumulator -= 0x800
                    line++
                }
            }
        }
    }

    private fun loadTlut() {
        val w0 = commands[0]
        val w1 = commands[1]
        val tile = tiles[(w1 ushr 24) and 7]

        val sl = ((w0 ushr 12) and 0xFFF) shr 2
        val sh = ((w1 ushr 12) and 0xFFF) shr 2

        for (i in sl..sh) {
            val value = n64.ramRead16(textureImage + i * 2)
            val at = (tile.tmem * 8 + (i - sl) * 8) and 0xFFF
            for (copy in 0 until 4) {
                val target = (at + copy * 2) and 0xFFF
                tmem[target] = (value ushr 8).toByte()
                tmem[target + 1] = value.toByte()
            }
        }
    }

    private fun textureBytes(size: Int): Int = when (size) {
        0 -> 0
        1 -> 1
        2 -> 2
        else -> 4
    }

    private fun fillRectangle() {
        rectangles++

        val xl = ((commands[0] ushr 12) and 0xFFF) shr 2
        val yl = (commands[0] and 0xFFF) shr 2
        val xh = ((commands[1] ushr 12) and 0xFFF) shr 2
        val yh = (commands[1] and 0xFFF) shr 2

        val left = maxOf(xh, scissorXh)
        val right = minOf(xl, scissorXl - 1)
        val top = maxOf(yh, scissorYh)
        val bottom = minOf(yl, scissorYl - 1)

        if (right < left || bottom < top) return

        if (cycleType == CYCLE_FILL) {
            buildFillUniform()
            pixels += spanAccel.renderFill(spanUniforms, top, bottom, left, right, tmemGeneration)
            return
        }

        buildSolidRectUniform(left, top)
        val drawn = spanAccel.renderTexrect(spanUniforms, top, bottom, left, right, tmemGeneration)
        pixels += drawn
        workPixels += drawn
    }

    private fun textureRectangle(flip: Boolean) {
        rectangles++

        val xl = ((commands[0] ushr 12) and 0xFFF)
        val yl = (commands[0] and 0xFFF)
        val tileIndex = (commands[1] ushr 24) and 7
        val xh = ((commands[1] ushr 12) and 0xFFF)
        val yh = (commands[1] and 0xFFF)

        val s = (commands[2] ushr 16) and 0xFFFF
        val t = commands[2] and 0xFFFF
        var dsdx = (commands[3] ushr 16).toShort().toInt()
        var dtdy = (commands[3] and 0xFFFF).toShort().toInt()
        if (cycleType == CYCLE_COPY) {
            if (flip) dtdy = dtdy shr 2 else dsdx = dsdx shr 2
        }

        val x0 = xh shr 2
        val y0 = yh shr 2
        val x1 = if (cycleType == CYCLE_FILL || cycleType == CYCLE_COPY) (xl shr 2) else (xl shr 2) - 1
        val y1 = if (cycleType == CYCLE_FILL || cycleType == CYCLE_COPY) (yl shr 2) else (yl shr 2) - 1

        val left = maxOf(x0, scissorXh)
        val right = minOf(x1, scissorXl - 1)
        val top = maxOf(y0, scissorYh)
        val bottom = minOf(y1, scissorYl - 1)

        val tile = tiles[tileIndex]

        if (n64.debugTrace) {
            n64.debugLog.add(
                "texrect x=$x0-$x1 y=$y0-$y1 fmt=${tile.format}/${tile.size} tmem=${tile.tmem}" +
                    " line=${tile.line} pal=${tile.palette} tlut=$tlutEnable/$tlutType cycle=$cycleType" +
                    " s=$s t=$t dsdx=$dsdx dtdy=$dtdy" +
                    " combine=${combineRgbA[0]}/${combineRgbB[0]}/${combineRgbC[0]}/${combineRgbD[0]}" +
                    " alpha=${combineAlphaA[0]}/${combineAlphaB[0]}/${combineAlphaC[0]}/${combineAlphaD[0]} f=${n64.frameCount}",
            )
        }

        if (right < left || bottom < top) return

        val coordShift = if (cycleType == CYCLE_COPY) 0 else 5
        buildTexrectUniform(flip, tile, s, t, dsdx, dtdy, x0, y0, coordShift)
        val drawn = spanAccel.renderTexrect(spanUniforms, top, bottom, left, right, tmemGeneration)
        pixels += drawn
        workPixels += drawn
    }

    private fun triangle(opcode: Int) {
        triangles++

        val shade = opcode and 4 != 0
        val texture = opcode and 2 != 0
        val depth = opcode and 1 != 0

        var at = 8
        val edge = edgeCoefficients
        edge.fill(0)
        for (i in 0 until 8) edge[i] = commands[i]
        if (shade) {
            for (i in 0 until 16) edge[8 + i] = commands[at + i]
            at += 16
        }
        if (texture) {
            for (i in 0 until 16) edge[24 + i] = commands[at + i]
            at += 16
        }
        if (depth) {
            for (i in 0 until 4) edge[40 + i] = commands[at + i]
        }

        render(edge, shade, texture, depth)
    }

    private fun sign(value: Int, bits: Int): Int = (value shl (32 - bits)) shr (32 - bits)

    private fun render(edge: IntArray, shade: Boolean, texture: Boolean, depth: Boolean) {
        val flip = edge[0] and 0x800000 != 0
        val tile = tiles[(edge[0] ushr 16) and 7]

        val yl = sign(edge[0], 14)
        val ym = sign(edge[1] shr 16, 14)
        val yh = sign(edge[1], 14)

        val xl = sign(edge[2], 28)
        val dxldy = sign(edge[3], 30)
        val xh = sign(edge[4], 28)
        val dxhdy = sign(edge[5], 30)
        val xm = sign(edge[6], 28)
        val dxmdy = sign(edge[7], 30)

        var red = high(edge[8]) or low(edge[12])
        var green = highShifted(edge[8]) or (edge[12] and 0xFFFF)
        var blue = high(edge[9]) or low(edge[13])
        var alpha = highShifted(edge[9]) or (edge[13] and 0xFFFF)

        val drdx = high(edge[10]) or low(edge[14])
        val dgdx = highShifted(edge[10]) or (edge[14] and 0xFFFF)
        val dbdx = high(edge[11]) or low(edge[15])
        val dadx = highShifted(edge[11]) or (edge[15] and 0xFFFF)

        val drde = high(edge[16]) or low(edge[20])
        val dgde = highShifted(edge[16]) or (edge[20] and 0xFFFF)
        val dbde = high(edge[17]) or low(edge[21])
        val dade = highShifted(edge[17]) or (edge[21] and 0xFFFF)

        val drdy = high(edge[18]) or low(edge[22])
        val dgdy = highShifted(edge[18]) or (edge[22] and 0xFFFF)
        val dbdy = high(edge[19]) or low(edge[23])
        val dady = highShifted(edge[19]) or (edge[23] and 0xFFFF)

        var s = high(edge[24]) or low(edge[28])
        var t = highShifted(edge[24]) or (edge[28] and 0xFFFF)
        var w = high(edge[25]) or low(edge[29])

        val dsdx = high(edge[26]) or low(edge[30])
        val dtdx = highShifted(edge[26]) or (edge[30] and 0xFFFF)
        val dwdx = high(edge[27]) or low(edge[31])

        val dsde = high(edge[32]) or low(edge[36])
        val dtde = highShifted(edge[32]) or (edge[36] and 0xFFFF)
        val dwde = high(edge[33]) or low(edge[37])

        val dsdy = high(edge[34]) or low(edge[38])
        val dtdy = highShifted(edge[34]) or (edge[38] and 0xFFFF)
        val dwdy = high(edge[35]) or low(edge[39])

        var z = edge[40]
        val dzdx = edge[41]
        val dzde = edge[42]
        val dzdy = edge[43]

        spansDs = dsdx and 0x1F.inv()
        spansDt = dtdx and 0x1F.inv()
        spansDw = dwdx and 0x1F.inv()
        spansDr = drdx and 0x1F.inv()
        spansDg = dgdx and 0x1F.inv()
        spansDb = dbdx and 0x1F.inv()
        spansDa = dadx and 0x1F.inv()
        spansDz = dzdx

        val dzdyDz = (dzdy shr 16) and 0xFFFF
        val dzdxDz = (dzdx shr 16) and 0xFFFF
        val dzSum = (if (dzdyDz and 0x8000 != 0) dzdyDz.inv() and 0x7FFF else dzdyDz) +
            (if (dzdxDz and 0x8000 != 0) dzdxDz.inv() and 0x7FFF else dzdxDz)
        spanDzPix = normalizeDzpix(dzSum and 0xFFFF)
        spanDzPixEnc = dzCompress(spanDzPix)

        var xleftInc = (dxmdy shr 2) and 1.inv()
        var xrightInc = (dxhdy shr 2) and 1.inv()
        var xleft = xm and 1.inv()
        var xright = xh and 1.inv()

        val signDxhdy = edge[5] < 0
        val doOffset = !(signDxhdy xor flip)

        val dsdiff: Int
        val dtdiff: Int
        val dwdiff: Int
        val drdiff: Int
        val dgdiff: Int
        val dbdiff: Int
        val dadiff: Int
        val dzdiff: Int

        if (doOffset) {
            dsdiff = offset(dsde, dsdy)
            dtdiff = offset(dtde, dtdy)
            dwdiff = offset(dwde, dwdy)
            drdiff = offset(drde, drdy)
            dgdiff = offset(dgde, dgdy)
            dbdiff = offset(dbde, dbdy)
            dadiff = offset(dade, dady)
            dzdiff = offset(dzde, dzdy)
        } else {
            dsdiff = 0
            dtdiff = 0
            dwdiff = 0
            drdiff = 0
            dgdiff = 0
            dbdiff = 0
            dadiff = 0
            dzdiff = 0
        }

        val copy = cycleType == CYCLE_COPY
        val dsdxh = if (copy) 0 else (dsdx shr 8) and 1.inv()
        val dtdxh = if (copy) 0 else (dtdx shr 8) and 1.inv()
        val dwdxh = if (copy) 0 else (dwdx shr 8) and 1.inv()
        val drdxh = if (copy) 0 else (drdx shr 8) and 1.inv()
        val dgdxh = if (copy) 0 else (dgdx shr 8) and 1.inv()
        val dbdxh = if (copy) 0 else (dbdx shr 8) and 1.inv()
        val dadxh = if (copy) 0 else (dadx shr 8) and 1.inv()
        val dzdxh = if (copy) 0 else (dzdx shr 8) and 1.inv()

        var xfrac = (xright shr 8) and 0xFF

        val ycur = yh and 3.inv()
        val ldflag = if (signDxhdy xor flip) 0 else 3

        var yllimit = if (yl and 0x2000 != 0) {
            yl
        } else if (yl and 0x1000 != 0) {
            clipYl
        } else if ((yl and 0xFFF) < clipYl) {
            yl
        } else {
            clipYl
        }

        var ylfar = yllimit or 3
        if ((yl shr 2) > (ylfar shr 2)) {
            ylfar += 4
        } else if ((yllimit shr 2) >= 0 && (yllimit shr 2) < SPANS - 1) {
            spanValid[(yllimit shr 2) + 1] = false
        }

        val yhlimit = if (yh and 0x2000 != 0) {
            clipYh
        } else if (yh and 0x1000 != 0) {
            yh
        } else if (yh >= clipYh) {
            yh
        } else {
            clipYh
        }

        val yhclose = yhlimit and 3.inv()

        val clipxlshift = clipXl shl 1
        val clipxhshift = clipXh shl 1

        var allover = 1
        var allunder = 1
        var allinval = 1
        var maxxmx = 0
        var minxmx = 0
        var maxxhx = 0
        var minxhx = 0

        for (k in ycur..ylfar) {
            if (k == ym) {
                xleft = xl and 1.inv()
                xleftInc = (dxldy shr 2) and 1.inv()
            }

            val spix = k and 3

            if (k >= yhclose) {
                var invaly = if (k < yhlimit || k >= yllimit) 1 else 0
                val j = k shr 2

                if (j in 0 until SPANS) {
                    if (spix == 0) {
                        maxxmx = 0
                        minxmx = 0xFFF
                        maxxhx = 0
                        minxhx = 0xFFF
                        allover = 1
                        allunder = 1
                        allinval = 1
                    }

                    var stickybit = if (((xright shr 1) and 0x1FFF) > 0) 1 else 0
                    var xrsc = ((xright shr 13) and 0x1FFE) or stickybit
                    var curunder =
                        if (xright and 0x8000000 != 0 || (xrsc < clipxhshift && xright and 0x4000000 == 0)) 1 else 0
                    xrsc = if (curunder != 0) clipxhshift else ((xright shr 13) and 0x3FFE) or stickybit
                    var curover = if (xrsc and 0x2000 != 0 || (xrsc and 0x1FFF) >= clipxlshift) 1 else 0
                    xrsc = if (curover != 0) clipxlshift else xrsc
                    allover = allover and curover
                    allunder = allunder and curunder

                    stickybit = if (((xleft shr 1) and 0x1FFF) > 0) 1 else 0
                    var xlsc = ((xleft shr 13) and 0x1FFE) or stickybit
                    curunder =
                        if (xleft and 0x8000000 != 0 || (xlsc < clipxhshift && xleft and 0x4000000 == 0)) 1 else 0
                    xlsc = if (curunder != 0) clipxhshift else ((xleft shr 13) and 0x3FFE) or stickybit
                    curover = if (xlsc and 0x2000 != 0 || (xlsc and 0x1FFF) >= clipxlshift) 1 else 0
                    xlsc = if (curover != 0) clipxlshift else xlsc
                    allover = allover and curover
                    allunder = allunder and curunder

                    val left = (xleft xor (1 shl 27)) and (0x3FFF shl 14)
                    val rightEdge = (xright xor (1 shl 27)) and (0x3FFF shl 14)
                    val curcross = if (flip) left < rightEdge else rightEdge < left

                    if (curcross) invaly = 1
                    allinval = allinval and invaly

                    spanMinorX[j * 4 + spix] = xlsc and 0x1FFF
                    spanMajorX[j * 4 + spix] = xrsc and 0x1FFF
                    spanInvalY[j * 4 + spix] = invaly

                    if (invaly == 0) {
                        val lx = (xlsc shr 3) and 0xFFF
                        val rx = (xrsc shr 3) and 0xFFF
                        if (flip) {
                            if (lx > maxxmx) maxxmx = lx
                            if (rx < minxhx) minxhx = rx
                        } else {
                            if (lx < minxmx) minxmx = lx
                            if (rx > maxxhx) maxxhx = rx
                        }
                    }

                    if (spix == ldflag) {
                        spanUnscrx[j] = sign(xright shr 16, 12)
                        xfrac = (xright shr 8) and 0xFF
                        spanS[j] = attribute(s, dsdiff, xfrac, dsdxh)
                        spanT[j] = attribute(t, dtdiff, xfrac, dtdxh)
                        spanW[j] = attribute(w, dwdiff, xfrac, dwdxh)
                        spanR[j] = attribute(red, drdiff, xfrac, drdxh)
                        spanG[j] = attribute(green, dgdiff, xfrac, dgdxh)
                        spanB[j] = attribute(blue, dbdiff, xfrac, dbdxh)
                        spanA[j] = attribute(alpha, dadiff, xfrac, dadxh)
                        spanZ[j] = attribute(z, dzdiff, xfrac, dzdxh)
                    }

                    if (spix == 3) {
                        spanLx[j] = if (flip) maxxmx else minxmx
                        spanRx[j] = if (flip) minxhx else maxxhx
                        spanValid[j] = allinval == 0 && allover == 0 && allunder == 0
                    }
                }
            }

            if (spix == 3) {
                s += dsde
                t += dtde
                w += dwde
                red += drde
                green += dgde
                blue += dbde
                alpha += dade
                z += dzde
            }

            xleft += xleftInc
            xright += xrightInc
        }

        renderSpans(yhlimit shr 2, yllimit shr 2, flip, tile, shade, texture, depth)
    }

    private fun renderSpans(
        start: Int,
        end: Int,
        flip: Boolean,
        tile: Tile,
        shade: Boolean,
        texture: Boolean,
        depth: Boolean,
    ) {
        val first = maxOf(start, 0)
        val last = minOf(end, SPANS - 1)
        if (last < first) return

        buildSpanUniforms(flip, shade, texture, depth, tile)
        val drawn = spanAccel.render(
            first, last, spanUniforms,
            spanValid, spanLx, spanRx, spanUnscrx,
            spanR, spanG, spanB, spanA,
            spanZ, spanS, spanT, spanW,
            spanMinorX, spanMajorX, spanInvalY,
            tmemGeneration,
        )
        pixels += drawn
        workPixels += drawn
    }

    private fun buildSpanUniforms(flip: Boolean, shade: Boolean, texture: Boolean, depth: Boolean, tile: Tile) {
        val u = spanUniforms
        u[RdpSpanAccel.U_TEXRECT] = 0
        u[RdpSpanAccel.U_COPY] = 0
        u[RdpSpanAccel.U_FILL] = 0
        u[RdpSpanAccel.U_FILLCOLOR] = 0
        u[RdpSpanAccel.U_RECTSHADE] = 0
        u[RdpSpanAccel.U_CYCLE2] = if (cycleType == CYCLE_2) 1 else 0
        u[RdpSpanAccel.U_FLIP] = if (flip) 1 else 0
        u[RdpSpanAccel.U_SPANS_DR] = spansDr
        u[RdpSpanAccel.U_SPANS_DG] = spansDg
        u[RdpSpanAccel.U_SPANS_DB] = spansDb
        u[RdpSpanAccel.U_SPANS_DA] = spansDa
        u[RdpSpanAccel.U_SPANS_DZ] = spansDz
        u[RdpSpanAccel.U_SPANS_DS] = spansDs
        u[RdpSpanAccel.U_SPANS_DT] = spansDt
        u[RdpSpanAccel.U_SPANS_DW] = spansDw
        u[RdpSpanAccel.U_SPANDZPIX] = spanDzPix
        u[RdpSpanAccel.U_SPANDZPIXENC] = spanDzPixEnc
        u[RdpSpanAccel.U_SCISSOR_XH] = scissorXh
        u[RdpSpanAccel.U_SCISSOR_XL] = scissorXl
        u[RdpSpanAccel.U_COLORSIZE] = colorSize
        u[RdpSpanAccel.U_COLORIMAGE] = colorImage
        u[RdpSpanAccel.U_COLORWIDTH] = colorWidth
        u[RdpSpanAccel.U_PRIM] = primPacked
        u[RdpSpanAccel.U_ENV] = envPacked
        u[RdpSpanAccel.U_BLEND] = blendPacked
        u[RdpSpanAccel.U_FOG] = fogPacked
        u[RdpSpanAccel.U_PRIMLOD] = primLodFrac
        u[RdpSpanAccel.U_ZIMAGE] = zImage
        u[RdpSpanAccel.U_ZMODE] = zMode
        u[RdpSpanAccel.U_ZCOMPARE] = if (zCompare) 1 else 0
        u[RdpSpanAccel.U_ZUPDATE] = if (zUpdate) 1 else 0
        u[RdpSpanAccel.U_ZSOURCE] = if (zSourceSelect) 1 else 0
        u[RdpSpanAccel.U_ALPHACOMPARE] = if (alphaCompare) 1 else 0
        u[RdpSpanAccel.U_CTA] = if (coverageTimesAlpha) 1 else 0
        u[RdpSpanAccel.U_ACS] = if (alphaCoverageSelect) 1 else 0
        u[RdpSpanAccel.U_FORCEBLEND] = if (forceBlend) 1 else 0
        u[RdpSpanAccel.U_PRIMDEPTH] = primDepth
        u[RdpSpanAccel.U_PRIMDZ] = primDz
        u[RdpSpanAccel.U_PRIMDZENC] = primDzEnc
        u[RdpSpanAccel.U_USEDEPTH] = if (depth) 1 else 0
        val c = RdpSpanAccel.U_CRGB
        u[c + 0] = combineRgbA[0]
        u[c + 1] = combineRgbA[1]
        u[c + 2] = combineRgbB[0]
        u[c + 3] = combineRgbB[1]
        u[c + 4] = combineRgbC[0]
        u[c + 5] = combineRgbC[1]
        u[c + 6] = combineRgbD[0]
        u[c + 7] = combineRgbD[1]
        val al = RdpSpanAccel.U_CALPHA
        u[al + 0] = combineAlphaA[0]
        u[al + 1] = combineAlphaA[1]
        u[al + 2] = combineAlphaB[0]
        u[al + 3] = combineAlphaB[1]
        u[al + 4] = combineAlphaC[0]
        u[al + 5] = combineAlphaC[1]
        u[al + 6] = combineAlphaD[0]
        u[al + 7] = combineAlphaD[1]
        val bl = RdpSpanAccel.U_BLENDSEL
        u[bl + 0] = blendA[0]
        u[bl + 1] = blendA[1]
        u[bl + 2] = blendB[0]
        u[bl + 3] = blendB[1]
        u[bl + 4] = blendC[0]
        u[bl + 5] = blendC[1]
        u[bl + 6] = blendD[0]
        u[bl + 7] = blendD[1]
        u[RdpSpanAccel.U_SHADE] = if (shade) 1 else 0
        u[RdpSpanAccel.U_TEXTURE] = if (texture) 1 else 0
        u[RdpSpanAccel.U_PERSP] = if (perspective) 1 else 0
        u[RdpSpanAccel.U_SAMPLETYPE] = sampleType
        u[RdpSpanAccel.U_TLUTEN] = if (tlutEnable) 1 else 0
        u[RdpSpanAccel.U_TLUTTYPE] = tlutType
        u[RdpSpanAccel.U_MIDTEXEL] = if (midTexel) 1 else 0
        u[RdpSpanAccel.T_FORMAT] = tile.format
        u[RdpSpanAccel.T_SIZE] = tile.size
        u[RdpSpanAccel.T_LINE] = tile.line
        u[RdpSpanAccel.T_TMEM] = tile.tmem
        u[RdpSpanAccel.T_PALETTE] = tile.palette
        u[RdpSpanAccel.T_CLAMPS] = tile.clampS
        u[RdpSpanAccel.T_MIRRORS] = tile.mirrorS
        u[RdpSpanAccel.T_MASKS] = tile.maskS
        u[RdpSpanAccel.T_SHIFTS] = tile.shiftS
        u[RdpSpanAccel.T_CLAMPT] = tile.clampT
        u[RdpSpanAccel.T_MIRRORT] = tile.mirrorT
        u[RdpSpanAccel.T_MASKT] = tile.maskT
        u[RdpSpanAccel.T_SHIFTT] = tile.shiftT
        u[RdpSpanAccel.T_SL] = tile.sl
        u[RdpSpanAccel.T_TL] = tile.tl
        u[RdpSpanAccel.T_SH] = tile.sh
        u[RdpSpanAccel.T_TH] = tile.th
        u[RdpSpanAccel.T_CLAMPDIFFS] = tile.clampDiffS
        u[RdpSpanAccel.T_CLAMPDIFFT] = tile.clampDiffT
        u[RdpSpanAccel.T_CLAMPENS] = if (tile.clampEnS) 1 else 0
        u[RdpSpanAccel.T_CLAMPENT] = if (tile.clampEnT) 1 else 0
        u[RdpSpanAccel.T_MASKSCLAMPED] = tile.maskSClamped
        u[RdpSpanAccel.T_MASKTCLAMPED] = tile.maskTClamped
    }

    private fun buildFillUniform() {
        val u = spanUniforms
        u.fill(0)
        u[RdpSpanAccel.U_FILL] = 1
        u[RdpSpanAccel.U_FILLCOLOR] = fillColor
        u[RdpSpanAccel.U_COLORSIZE] = colorSize
        u[RdpSpanAccel.U_COLORIMAGE] = colorImage
        u[RdpSpanAccel.U_COLORWIDTH] = colorWidth
        u[RdpSpanAccel.U_SCISSOR_XH] = scissorXh
        u[RdpSpanAccel.U_SCISSOR_XL] = scissorXl
    }

    private fun buildSolidRectUniform(left: Int, top: Int) {
        buildTexrectUniform(false, tiles[0], 0, 0, 0, 0, left, top, 0)
        val u = spanUniforms
        u[RdpSpanAccel.U_TEXTURE] = 0
        u[RdpSpanAccel.U_RECTSHADE] = 0
        for (i in RdpSpanAccel.T_FORMAT..RdpSpanAccel.T_MASKTCLAMPED) u[i] = 0
    }

    private fun buildTexrectUniform(
        flip: Boolean,
        tile: Tile,
        s: Int,
        t: Int,
        dsdx: Int,
        dtdy: Int,
        x0: Int,
        y0: Int,
        coordShift: Int,
    ) {
        buildSpanUniforms(false, false, true, false, tile)
        val u = spanUniforms
        u[RdpSpanAccel.U_TEXRECT] = 1
        u[RdpSpanAccel.U_RECTSHADE] = -1
        u[RdpSpanAccel.U_COPY] = if (cycleType == CYCLE_COPY) 1 else 0
        u[RdpSpanAccel.U_TR_X0] = x0
        u[RdpSpanAccel.U_TR_Y0] = y0
        u[RdpSpanAccel.U_TR_S] = s
        u[RdpSpanAccel.U_TR_T] = t
        u[RdpSpanAccel.U_TR_DSDX] = dsdx
        u[RdpSpanAccel.U_TR_DTDY] = dtdy
        u[RdpSpanAccel.U_TR_SHIFT] = coordShift
        u[RdpSpanAccel.U_TR_FLIP] = if (flip) 1 else 0
    }

    private fun high(value: Int): Int = value and 0xFFFF0000.toInt()

    private fun highShifted(value: Int): Int = (value shl 16) and 0xFFFF0000.toInt()

    private fun low(value: Int): Int = (value ushr 16) and 0xFFFF

    private fun offset(de: Int, dy: Int): Int {
        val deh = de and 0x1FF.inv()
        val dyh = dy and 0x1FF.inv()
        return deh - (deh shr 2) - dyh + (dyh shr 2)
    }

    private fun attribute(value: Int, diff: Int, xfrac: Int, dxh: Int): Int =
        ((value and 0x1FF.inv()) + diff - xfrac * dxh) and 0x3FF.inv()

    private fun sx16(value: Int): Int = value.toShort().toInt()

    private fun pack(r: Int, g: Int, b: Int, a: Int): Int =
        (r and 0xFF) or ((g and 0xFF) shl 8) or ((b and 0xFF) shl 16) or ((a and 0xFF) shl 24)

    private fun swizzle(value: Int): Int = pack(
        (value ushr 24) and 0xFF,
        (value ushr 16) and 0xFF,
        (value ushr 8) and 0xFF,
        value and 0xFF,
    )

    private fun normalizeDzpix(sum: Int): Int {
        if (sum and 0xC000 != 0) return 0x8000
        if (sum and 0xFFFF == 0) return 1
        if (sum == 1) return 3
        var count = 0x2000
        while (count > 0) {
            if (sum and count != 0) return count shl 1
            count = count shr 1
        }
        return 0
    }

    private fun dzCompress(value: Int): Int {
        var j = 0
        if (value and 0xFF00 != 0) j = j or 8
        if (value and 0xF0F0 != 0) j = j or 4
        if (value and 0xCCCC != 0) j = j or 2
        if (value and 0xAAAA != 0) j = j or 1
        return j
    }

    companion object {
        private const val SPANS = 1024


        private val NORM_POINT = intArrayOf(
            0x4000, 0x3F04, 0x3E10, 0x3D22, 0x3C3C, 0x3B5D, 0x3A83, 0x39B1,
            0x38E4, 0x381C, 0x375A, 0x369D, 0x35E5, 0x3532, 0x3483, 0x33D9,
            0x3333, 0x3291, 0x31F4, 0x3159, 0x30C3, 0x3030, 0x2FA1, 0x2F15,
            0x2E8C, 0x2E06, 0x2D83, 0x2D03, 0x2C86, 0x2C0B, 0x2B93, 0x2B1E,
            0x2AAB, 0x2A3A, 0x29CC, 0x2960, 0x28F6, 0x288E, 0x2828, 0x27C4,
            0x2762, 0x2702, 0x26A4, 0x2648, 0x25ED, 0x2594, 0x253D, 0x24E7,
            0x2492, 0x243F, 0x23EE, 0x239E, 0x234F, 0x2302, 0x22B6, 0x226C,
            0x2222, 0x21DA, 0x2193, 0x214D, 0x2108, 0x20C5, 0x2082, 0x2041,
        )

        private val NORM_SLOPE = intArrayOf(
            0xF03, 0xF0B, 0xF11, 0xF19, 0xF20, 0xF25, 0xF2D, 0xF32,
            0xF37, 0xF3D, 0xF42, 0xF47, 0xF4C, 0xF50, 0xF55, 0xF59,
            0xF5D, 0xF62, 0xF64, 0xF69, 0xF6C, 0xF70, 0xF73, 0xF76,
            0xF79, 0xF7C, 0xF7F, 0xF82, 0xF84, 0xF87, 0xF8A, 0xF8C,
            0xF8E, 0xF91, 0xF93, 0xF95, 0xF97, 0xF99, 0xF9B, 0xF9D,
            0xF9F, 0xFA1, 0xFA3, 0xFA4, 0xFA6, 0xFA8, 0xFA9, 0xFAA,
            0xFAC, 0xFAE, 0xFAF, 0xFB0, 0xFB2, 0xFB3, 0xFB5, 0xFB5,
            0xFB7, 0xFB8, 0xFB9, 0xFBA, 0xFBC, 0xFBC, 0xFBE, 0xFBE,
        )

        private val COMMAND_LENGTH = IntArray(64) { 1 }.also {
            it[0x08] = 4
            it[0x09] = 6
            it[0x0A] = 12
            it[0x0B] = 14
            it[0x0C] = 12
            it[0x0D] = 14
            it[0x0E] = 20
            it[0x0F] = 22
            it[0x24] = 2
            it[0x25] = 2
        }
    }
}
