package kapi.gfx

import kapi.gpu.GpuBuffer

class ResidentSurface(
    private val colorVram: GpuBuffer,
    private val auxVram: GpuBuffer,
    private val guestWords: IntArray,
    private val guestAux: ByteArray,
    private val mask: Int,
    private val invalidate: (Int, Int) -> Unit,
) {
    var resident = false
        private set
    var rebinds = 0
    var rebindBits = 0
    var flushes = 0
    var colorWordsRead = 0L
    var zWordsRead = 0L
    var auxWordsRead = 0L

    private var colorAddr = 0
    private var zAddr = 0
    private var width = 0
    private var bpp = 0
    private var depth = false
    private var colorAux = false
    private var rowLo = 0
    private var rowHi = -1
    private var dirty = false
    private var dirtyLo = 0
    private var dirtyHi = 0

    private val auxScratch = IntArray(guestAux.size / 4)

    fun prepare(
        colorAddr: Int,
        zAddr: Int,
        width: Int,
        colorSize: Int,
        depth: Boolean,
        colorAux: Boolean,
        loRow: Int,
        hiRow: Int,
    ) {
        val bpp = if (colorSize == 3) 4 else 2
        if (!resident || colorAddr != this.colorAddr || zAddr != this.zAddr ||
            width != this.width || bpp != this.bpp || depth != this.depth || colorAux != this.colorAux
        ) {
            rebinds++
            rebindBits = rebindBits or
                (if (!resident) 1 else 0) or
                (if (colorAddr != this.colorAddr) 2 else 0) or
                (if (zAddr != this.zAddr) 4 else 0) or
                (if (width != this.width) 8 else 0) or
                (if (bpp != this.bpp) 16 else 0) or
                (if (depth != this.depth) 32 else 0) or
                (if (colorAux != this.colorAux) 64 else 0)
            flush()
            this.colorAddr = colorAddr
            this.zAddr = zAddr
            this.width = width
            this.bpp = bpp
            this.depth = depth
            this.colorAux = colorAux
            resident = true
            rowLo = loRow
            rowHi = hiRow
            dirty = false
            upload(loRow, hiRow)
        } else {
            if (loRow < rowLo) {
                upload(loRow, rowLo - 1)
                rowLo = loRow
            }
            if (hiRow > rowHi) {
                upload(rowHi + 1, hiRow)
                rowHi = hiRow
            }
        }
    }

    fun touches(byteLo: Int, byteHi: Int): Boolean {
        if (!resident || rowHi < rowLo) return false
        val cLo = colorAddr + rowLo * width * bpp
        val cHi = colorAddr + (rowHi + 1) * width * bpp
        if (byteLo < cHi && byteHi > cLo) return true
        if (depth) {
            val zLo = zAddr + rowLo * width * 2
            val zHi = zAddr + (rowHi + 1) * width * 2
            if (byteLo < zHi && byteHi > zLo) return true
        }
        return false
    }

    fun markDirty(loRow: Int, hiRow: Int) {
        if (!dirty) {
            dirtyLo = loRow
            dirtyHi = hiRow
            dirty = true
        } else {
            if (loRow < dirtyLo) dirtyLo = loRow
            if (hiRow > dirtyHi) dirtyHi = hiRow
        }
    }

    fun flush() {
        if (!resident) return
        if (dirty) {
            flushes++
            val cLo = ((colorAddr + dirtyLo * width * bpp) and mask) ushr 2
            val cHi = ((colorAddr + (dirtyHi + 1) * width * bpp - 1) and mask) ushr 2
            colorVram.read(cLo, guestWords, cLo, cHi - cLo + 1)
            colorWordsRead += (cHi - cLo + 1).toLong()
            invalidate(cLo, cHi)
            if (colorAux) {
                val hLo = (((colorAddr + dirtyLo * width * bpp) and mask) ushr 1) ushr 2
                val hHi = (((colorAddr + (dirtyHi + 1) * width * bpp - 1) and mask) ushr 1) ushr 2
                auxVram.read(hLo, auxScratch, hLo, hHi - hLo + 1)
                auxWordsRead += (hHi - hLo + 1).toLong()
                unpackAux(hLo, hHi)
            }
            if (depth) {
                val zLoByte = (zAddr + dirtyLo * width * 2) and mask
                val zHiByte = (zAddr + (dirtyHi + 1) * width * 2 - 1) and mask
                val zLo = zLoByte ushr 2
                val zHi = zHiByte ushr 2
                colorVram.read(zLo, guestWords, zLo, zHi - zLo + 1)
                zWordsRead += (zHi - zLo + 1).toLong()
                invalidate(zLo, zHi)
                val hLo = (zLoByte ushr 1) ushr 2
                val hHi = (zHiByte ushr 1) ushr 2
                auxVram.read(hLo, auxScratch, hLo, hHi - hLo + 1)
                auxWordsRead += (hHi - hLo + 1).toLong()
                unpackAux(hLo, hHi)
            }
        }
        resident = false
        dirty = false
    }

    private fun upload(loRow: Int, hiRow: Int) {
        if (hiRow < loRow) return
        val cLo = ((colorAddr + loRow * width * bpp) and mask) ushr 2
        val cHi = ((colorAddr + (hiRow + 1) * width * bpp - 1) and mask) ushr 2
        colorVram.write(cLo, guestWords, cLo, cHi - cLo + 1)
        if (colorAux) {
            val hLo = (((colorAddr + loRow * width * bpp) and mask) ushr 1) ushr 2
            val hHi = (((colorAddr + (hiRow + 1) * width * bpp - 1) and mask) ushr 1) ushr 2
            packAux(hLo, hHi)
            auxVram.write(hLo, auxScratch, hLo, hHi - hLo + 1)
        }
        if (depth) {
            val zLoByte = (zAddr + loRow * width * 2) and mask
            val zHiByte = (zAddr + (hiRow + 1) * width * 2 - 1) and mask
            val zLo = zLoByte ushr 2
            val zHi = zHiByte ushr 2
            colorVram.write(zLo, guestWords, zLo, zHi - zLo + 1)
            val hLo = (zLoByte ushr 1) ushr 2
            val hHi = (zHiByte ushr 1) ushr 2
            packAux(hLo, hHi)
            auxVram.write(hLo, auxScratch, hLo, hHi - hLo + 1)
        }
    }

    private fun packAux(loWord: Int, hiWord: Int) {
        for (word in loWord..hiWord) {
            val b = word shl 2
            auxScratch[word] = (guestAux[b].toInt() and 0xFF) or
                ((guestAux[b + 1].toInt() and 0xFF) shl 8) or
                ((guestAux[b + 2].toInt() and 0xFF) shl 16) or
                ((guestAux[b + 3].toInt() and 0xFF) shl 24)
        }
    }

    private fun unpackAux(loWord: Int, hiWord: Int) {
        for (word in loWord..hiWord) {
            val value = auxScratch[word]
            val b = word shl 2
            guestAux[b] = value.toByte()
            guestAux[b + 1] = (value ushr 8).toByte()
            guestAux[b + 2] = (value ushr 16).toByte()
            guestAux[b + 3] = (value ushr 24).toByte()
        }
    }
}
