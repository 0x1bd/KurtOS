package n64.core

const val PI_DRAM_ADDR = 0
const val PI_CART_ADDR = 1
const val PI_RD_LEN = 2
const val PI_WR_LEN = 3
const val PI_STATUS = 4
const val PI_DOM1_LAT = 5
const val PI_DOM1_PWD = 6
const val PI_DOM1_PGS = 7
const val PI_DOM1_RLS = 8
const val PI_DOM2_LAT = 9
const val PI_DOM2_PWD = 10
const val PI_DOM2_PGS = 11
const val PI_DOM2_RLS = 12

const val RDRAM_PAGE = 2048
const val BUFFER = 128

const val PI_STATUS_DMA_BUSY = 1 shl 0
const val PI_STATUS_IO_BUSY = 1 shl 1
const val PI_STATUS_INTERRUPT = 1 shl 3

class PI(private val n64: N64) {
    val regs = IntArray(14)

    fun reset() {
        regs.fill(0)
    }

    fun read(reg: Int): Int {
        n64.cpu.addCycles(20)
        return when (reg) {
            PI_RD_LEN, PI_WR_LEN -> 0x7F
            PI_CART_ADDR -> regs[reg] and 0xFFFFFFFE.toInt()
            PI_DRAM_ADDR -> regs[reg] and 0xFFFFFE
            else -> if (reg < 14) regs[reg] else 0
        }
    }

    fun write(reg: Int, value: Int, mask: Int) {
        when (reg) {
            PI_RD_LEN -> {
                regs[reg] = (regs[reg] and mask.inv()) or (value and mask)
                dmaRead()
            }

            PI_WR_LEN -> {
                regs[reg] = (regs[reg] and mask.inv()) or (value and mask)
                dmaWrite()
            }

            PI_STATUS -> {
                if (value and mask and (1 shl 1) != 0) {
                    regs[PI_STATUS] = regs[PI_STATUS] and PI_STATUS_INTERRUPT.inv()
                    n64.mi.clearInterrupt(MI_INTR_PI)
                    n64.cpu.checkPendingInterrupts()
                }
                if (value and mask and (1 shl 0) != 0) regs[PI_STATUS] = 0
            }

            else -> if (reg < 14) regs[reg] = (regs[reg] and mask.inv()) or (value and mask)
        }
    }

    private fun dmaRead() {
        val cartAddr = regs[PI_CART_ADDR] and 1.inv()
        val dramAddr = regs[PI_DRAM_ADDR] and 0xFFFFFE
        var length = (regs[PI_RD_LEN] and 0xFFFFFF) + 1
        if (length >= 0x7F && (length and 1) != 0) length++

        n64.cartridge.dmaRead(cartAddr, dramAddr, length)

        n64.createEvent(EVENT_PI, cycles(length))
        regs[PI_DRAM_ADDR] = (regs[PI_DRAM_ADDR] + length + 7) and 7.inv()
        regs[PI_CART_ADDR] = (regs[PI_CART_ADDR] + length + 1) and 1.inv()
        regs[PI_STATUS] = regs[PI_STATUS] or PI_STATUS_DMA_BUSY
    }

    private fun dmaWrite() {
        var cart = regs[PI_CART_ADDR] and 1.inv()
        var dram = regs[PI_DRAM_ADDR] and 0xFFFFFE
        val misalign = dram and 7
        var remaining = (regs[PI_WR_LEN] and 0xFFFFFF) + 1

        var index = misalign
        var first = true
        var moved = 0

        while (remaining > 0) {
            val pageLeft = RDRAM_PAGE - (dram and (RDRAM_PAGE - 1))
            val room = BUFFER - index
            var block = minOf(remaining, room, pageLeft)
            var fetch = block

            if ((block and 1) != 0) {
                if (!first || block >= minOf(room, pageLeft) - 1) {
                    block++
                    fetch = block
                } else {
                    fetch = block + 1
                }
            }

            val written = if (first) maxOf(0, block - misalign) else block
            if (written > 0) {
                n64.cartridge.dmaWrite(cart, dram, written)
                dram += written
            }

            cart += fetch
            remaining -= fetch
            moved += fetch

            if (index + block >= BUFFER) index = 0
            dram = (dram + 7) and 7.inv()
            first = false
        }

        n64.createEvent(EVENT_PI, cycles(moved))
        regs[PI_DRAM_ADDR] = dram
        regs[PI_CART_ADDR] = cart
        regs[PI_STATUS] = regs[PI_STATUS] or PI_STATUS_DMA_BUSY
    }

    fun cycles(length: Int): Long {
        val latency = (regs[PI_DOM1_LAT] + 1).toLong()
        val pulse = (regs[PI_DOM1_PWD] + 1).toLong()
        val release = (regs[PI_DOM1_RLS] + 1).toLong()
        val pageSize = 1L shl ((regs[PI_DOM1_PGS] and 0xF) + 2)
        val pages = (length + pageSize - 1) / pageSize

        var total = (14L + latency) * pages
        total += (pulse + release) * (length / 2)
        total += 5L * pages
        return total * 3 / 2
    }

    fun dmaEvent() {
        regs[PI_STATUS] = regs[PI_STATUS] and (PI_STATUS_DMA_BUSY or PI_STATUS_IO_BUSY).inv()
        regs[PI_STATUS] = regs[PI_STATUS] or PI_STATUS_INTERRUPT
        n64.mi.setInterrupt(MI_INTR_PI)
    }
}

const val SI_DRAM_ADDR = 0
const val SI_PIF_ADDR_RD64B = 1
const val SI_PIF_ADDR_WR64B = 4
const val SI_STATUS = 6

const val SI_STATUS_DMA_BUSY = 1 shl 0
const val SI_STATUS_IO_BUSY = 1 shl 1
const val SI_STATUS_INTERRUPT = 1 shl 12

class Si(private val n64: N64) {
    val regs = IntArray(7)

    var direction = 0
        private set

    fun reset() {
        regs.fill(0)
        direction = 0
    }

    fun read(reg: Int): Int {
        n64.cpu.addCycles(20)
        return if (reg < 7) regs[reg] else 0
    }

    fun write(reg: Int, value: Int, mask: Int) {
        when (reg) {
            SI_STATUS -> {
                regs[SI_STATUS] = regs[SI_STATUS] and SI_STATUS_INTERRUPT.inv()
                n64.mi.clearInterrupt(MI_INTR_SI)
                n64.cpu.checkPendingInterrupts()
            }

            SI_PIF_ADDR_RD64B -> {
                if (reg < 7) regs[reg] = (regs[reg] and mask.inv()) or (value and mask)
                direction = DIR_READ
                val duration = n64.pif.updateRam()
                regs[SI_STATUS] = regs[SI_STATUS] or SI_STATUS_DMA_BUSY
                n64.createEvent(EVENT_SI, duration)
            }

            SI_PIF_ADDR_WR64B -> {
                if (reg < 7) regs[reg] = (regs[reg] and mask.inv()) or (value and mask)
                direction = DIR_WRITE
                copyPifRdram()
                regs[SI_STATUS] = regs[SI_STATUS] or SI_STATUS_DMA_BUSY
                n64.createEvent(EVENT_SI, 6000)
            }

            else -> if (reg < 7) regs[reg] = (regs[reg] and mask.inv()) or (value and mask)
        }
    }

    fun startWrite() {
        direction = DIR_WRITE
        regs[SI_STATUS] = regs[SI_STATUS] or (SI_STATUS_DMA_BUSY or SI_STATUS_IO_BUSY)
        n64.createEvent(EVENT_SI, 3200)
    }

    private fun copyPifRdram() {
        val dram = regs[SI_DRAM_ADDR] and RDRAM_MASK
        if (direction == DIR_WRITE) {
            for (i in 0 until 64 step 4) n64.pif.writeRamWord(i, n64.ramRead32(dram + i))
        } else {
            for (i in 0 until 64 step 4) n64.ramWrite32(dram + i, n64.pif.readRamWord(i))
        }
    }

    fun dmaEvent() {
        if (direction == DIR_WRITE) {
            n64.pif.processRam()
        } else if (direction == DIR_READ) {
            copyPifRdram()
        }
        direction = 0
        regs[SI_STATUS] = regs[SI_STATUS] and (SI_STATUS_DMA_BUSY or SI_STATUS_IO_BUSY).inv()
        regs[SI_STATUS] = regs[SI_STATUS] or SI_STATUS_INTERRUPT
        n64.mi.setInterrupt(MI_INTR_SI)
    }

    companion object {
        const val DIR_WRITE = 1
        const val DIR_READ = 2
    }
}
