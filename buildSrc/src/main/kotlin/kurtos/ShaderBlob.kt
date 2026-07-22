package kurtos

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ShaderDescriptor(
    val kernel: String,
    val rsrc1: Int,
    val rsrc2: Int,
    val rsrc3: Int,
    val kernargSize: Int,
    val props: Int,
    val entryOffset: Int,
    val isaSize: Int,
) {
    override fun toString(): String =
        "rsrc1=0x${rsrc1.toUInt().toString(16)} rsrc2=0x${rsrc2.toUInt().toString(16)} " +
            "rsrc3=0x${rsrc3.toUInt().toString(16)} kernarg=$kernargSize " +
            "props=0x${props.toUInt().toString(16)} entry=0x${entryOffset.toUInt().toString(16)} isa=$isaSize"
}

class ShaderExtractionException(message: String) : RuntimeException(message)

object ShaderBlob {

    private class Section(
        val nameOffset: Int,
        val address: Long,
        val offset: Long,
        val size: Long,
        val link: Int,
        val entrySize: Long,
    )

    fun extract(objectFile: File, blob: File, kernel: String?): ShaderDescriptor {
        val data = objectFile.readBytes()
        val elf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

        if (data.size < 64 || data[0] != 0x7F.toByte() || data[1] != 'E'.code.toByte() ||
            data[2] != 'L'.code.toByte() || data[3] != 'F'.code.toByte()
        ) {
            throw ShaderExtractionException("${objectFile.name} is not an ELF object")
        }

        val sectionOffset = elf.getLong(0x28)
        val sectionEntrySize = elf.getShort(0x3A).toInt() and 0xFFFF
        val sectionCount = elf.getShort(0x3C).toInt() and 0xFFFF
        val nameSectionIndex = elf.getShort(0x3E).toInt() and 0xFFFF

        val sections = (0 until sectionCount).map { index ->
            val at = (sectionOffset + index.toLong() * sectionEntrySize).toInt()
            Section(
                nameOffset = elf.getInt(at),
                address = elf.getLong(at + 0x10),
                offset = elf.getLong(at + 0x18),
                size = elf.getLong(at + 0x20),
                link = elf.getInt(at + 0x28),
                entrySize = elf.getLong(at + 0x38),
            )
        }

        val nameSection = sections[nameSectionIndex]
        fun nameOf(section: Section) = readString(data, nameSection.offset.toInt() + section.nameOffset)

        val text = sections.firstOrNull { nameOf(it) == ".text" }
            ?: throw ShaderExtractionException("${objectFile.name} has no .text section")
        val symbols = sections.firstOrNull { nameOf(it) == ".symtab" }
            ?: throw ShaderExtractionException("${objectFile.name} has no symbol table")
        val strings = sections[symbols.link]

        var descriptor: ByteArray? = null
        var chosen: String? = null
        var entryAddress: Long? = null
        val kernels = ArrayList<String>()

        var at = symbols.offset
        while (at < symbols.offset + symbols.size) {
            val cursor = at.toInt()
            val symbolName = readString(data, strings.offset.toInt() + elf.getInt(cursor))
            val info = data[cursor + 4].toInt() and 0xFF
            val sectionIndex = elf.getShort(cursor + 6).toInt() and 0xFFFF
            val value = elf.getLong(cursor + 8)

            if (symbolName.endsWith(".kd")) {
                val name = symbolName.removeSuffix(".kd")
                kernels.add(name)
                if (kernel == null || name == kernel) {
                    val owner = sections[sectionIndex]
                    val start = (owner.offset + (value - owner.address)).toInt()
                    descriptor = data.copyOfRange(start, start + 64)
                    chosen = name
                }
            } else if (info shr 4 == 1 && sectionIndex != 0 && nameOf(sections[sectionIndex]) == ".text") {
                if (kernel == null || symbolName == kernel) entryAddress = value
            }

            at += symbols.entrySize
        }

        if (kernel == null && kernels.size > 1) {
            throw ShaderExtractionException(
                "${objectFile.name} defines ${kernels.size} kernels (${kernels.sorted().joinToString(", ")}). " +
                    "Name the one to extract, otherwise the blob would describe whichever kernel came last.",
            )
        }

        val kd = descriptor ?: throw ShaderExtractionException(
            "${objectFile.name} has no kernel descriptor for ${kernel ?: "<any>"}" +
                if (kernels.isEmpty()) "" else " (found: ${kernels.joinToString(", ")})",
        )

        val scratch = ByteBuffer.wrap(kd).order(ByteOrder.LITTLE_ENDIAN).getInt(4)
        if (scratch != 0) {
            throw ShaderExtractionException(
                "$chosen needs $scratch bytes of scratch memory per lane and the dispatch path provides none. " +
                    "Reduce register pressure, or avoid taking the address of a local.",
            )
        }

        val kdBuffer = ByteBuffer.wrap(kd).order(ByteOrder.LITTLE_ENDIAN)
        val isa = data.copyOfRange(text.offset.toInt(), (text.offset + text.size).toInt())
        val entry = entryAddress?.let { (it - text.address).toInt() } ?: 0

        val result = ShaderDescriptor(
            kernel = chosen ?: "?",
            rsrc1 = kdBuffer.getInt(48),
            rsrc2 = kdBuffer.getInt(52),
            rsrc3 = kdBuffer.getInt(44),
            kernargSize = kdBuffer.getInt(8),
            props = kdBuffer.getShort(56).toInt() and 0xFFFF,
            entryOffset = entry,
            isaSize = isa.size,
        )

        val out = ByteBuffer.allocate(28 + isa.size).order(ByteOrder.LITTLE_ENDIAN)
        out.put('K'.code.toByte()).put('S'.code.toByte()).put('H'.code.toByte()).put('1'.code.toByte())
        out.putInt(result.rsrc1)
        out.putInt(result.rsrc2)
        out.putInt(result.rsrc3)
        out.putInt(result.kernargSize)
        out.putInt(isa.size)
        out.putShort(result.props.toShort())
        out.putShort(result.entryOffset.toShort())
        out.put(isa)
        blob.writeBytes(out.array())

        return result
    }

    private fun readString(data: ByteArray, at: Int): String {
        var end = at
        while (end < data.size && data[end] != 0.toByte()) end++
        return String(data, at, end - at, Charsets.US_ASCII)
    }
}
