package kernel.drivers.gpu.vega

import kernel.KLog
import kernel.fs.StorageService

class Shader(
    val name: String,
    val rsrc1: UInt,
    val rsrc2: UInt,
    val rsrc3: UInt,
    val kernargSize: UInt,
    val props: UInt,
    val entryOffset: UInt,
    val isa: ByteArray,
) {
    val kernargSgprIndex: Int
        get() {
            var index = 0
            if (props and 0x1u != 0u) index += 4
            if (props and 0x2u != 0u) index += 2
            if (props and 0x4u != 0u) index += 2
            return index
        }

    val kernargEnabled: Boolean get() = props and 0x8u != 0u
}

object VegaShaderLoader {
    fun load(name: String): Shader? {
        val volume = StorageService.system()
        if (volume == null) {
            KLog.step("gpu", "shader $name", false, "esp not mounted")
            return null
        }

        val data = volume.read("/shaders/$name.kbin", MAX_BYTES)
        if (data == null || data.size < HEADER) {
            KLog.step("gpu", "shader $name", false, "not found")
            return null
        }

        if (data[0].toInt() != 'K'.code || data[1].toInt() != 'S'.code || data[2].toInt() != 'H'.code) {
            KLog.step("gpu", "shader $name", false, "bad magic")
            return null
        }

        val rsrc1 = dword(data, 4)
        val rsrc2 = dword(data, 8)
        val rsrc3 = dword(data, 12)
        val kernargSize = dword(data, 16)
        val isaSize = dword(data, 20).toInt()
        val props = (data[24].toUInt() and 0xFFu) or ((data[25].toUInt() and 0xFFu) shl 8)
        val entryOffset = (data[26].toUInt() and 0xFFu) or ((data[27].toUInt() and 0xFFu) shl 8)

        if (HEADER + isaSize > data.size) {
            KLog.step("gpu", "shader $name", false, "isa out of range")
            return null
        }

        val isa = data.copyOfRange(HEADER, HEADER + isaSize)
        KLog.step("gpu", "shader $name", true, "rsrc1 ${KLog.hex(rsrc1)} rsrc2 ${KLog.hex(rsrc2)} karg ${kernargSize} isa ${isaSize}")
        return Shader(name, rsrc1, rsrc2, rsrc3, kernargSize, props, entryOffset, isa)
    }

    private fun dword(data: ByteArray, at: Int): UInt =
        (data[at].toUInt() and 0xFFu) or
            ((data[at + 1].toUInt() and 0xFFu) shl 8) or
            ((data[at + 2].toUInt() and 0xFFu) shl 16) or
            ((data[at + 3].toUInt() and 0xFFu) shl 24)

    private const val HEADER = 28
    private const val MAX_BYTES: UInt = 0x100000u
}
