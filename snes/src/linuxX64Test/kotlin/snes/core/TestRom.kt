package snes.core

object TestRom {
    const val ENTRY = 0x8000

    fun build(program: ByteArray, sramCode: Int = 0): ByteArray {
        val image = ByteArray(0x8000)

        val body = if (program.isEmpty()) byteArrayOf(STP) else program
        body.copyInto(image, 0)

        val header = 0x7FC0
        val title = "KURTOS SNES TEST"

        for (i in 0 until 21) {
            image[header + i] = if (i < title.length) title[i].code.toByte() else 0x20
        }

        image[header + 0x15] = 0x20
        image[header + 0x16] = 0x00
        image[header + 0x17] = 0x08
        image[header + 0x18] = sramCode.toByte()
        image[header + 0x1C] = 0x00
        image[header + 0x1D] = 0x00
        image[header + 0x1E] = 0xFF.toByte()
        image[header + 0x1F] = 0xFF.toByte()

        image[header + 0x3C] = (ENTRY and 0xFF).toByte()
        image[header + 0x3D] = ((ENTRY shr 8) and 0xFF).toByte()

        return image
    }

    class Assembler {
        private val bytes = ArrayList<Byte>()

        fun byte(value: Int): Assembler {
            bytes.add((value and 0xFF).toByte())
            return this
        }

        fun word(value: Int): Assembler = byte(value and 0xFF).byte((value shr 8) and 0xFF)

        fun sei(): Assembler = byte(0x78)

        fun clc(): Assembler = byte(0x18)

        fun xce(): Assembler = byte(0xFB)

        fun rep(mask: Int): Assembler = byte(0xC2).byte(mask)

        fun sep(mask: Int): Assembler = byte(0xE2).byte(mask)

        fun ldaImmediate8(value: Int): Assembler = byte(0xA9).byte(value)

        fun ldaImmediate16(value: Int): Assembler = byte(0xA9).word(value)

        fun ldxImmediate16(value: Int): Assembler = byte(0xA2).word(value)

        fun staLong(address: Int): Assembler =
            byte(0x8F).byte(address and 0xFF).byte((address shr 8) and 0xFF).byte((address shr 16) and 0xFF)

        fun stxLong(address: Int): Assembler = byte(0x86).byte(address and 0xFF)

        fun staAbsolute(address: Int): Assembler = byte(0x8D).word(address)

        fun stz(address: Int): Assembler = byte(0x9C).word(address)

        fun inx(): Assembler = byte(0xE8)

        fun stp(): Assembler = byte(0xDB)

        fun nop(): Assembler = byte(0xEA)

        fun build(): ByteArray = bytes.toByteArray()
    }

    private const val STP: Byte = 0xDB.toByte()
}
