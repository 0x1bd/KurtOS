package kernel.drivers.gpu.vega

import kernel.KLog

import kernel.fs.StorageService

class Ucode(
    val name: String,
    val version: UInt,
    val featureVersion: UInt,
    val data: ByteArray,
    val payloadOffset: Int,
    val payloadBytes: Int,
) {
    fun dwordAt(byteOffset: Int): UInt {
        return (data[byteOffset].toUInt() and 0xFFu) or
            ((data[byteOffset + 1].toUInt() and 0xFFu) shl 8) or
            ((data[byteOffset + 2].toUInt() and 0xFFu) shl 16) or
            ((data[byteOffset + 3].toUInt() and 0xFFu) shl 24)
    }

    fun payloadDword(index: Int): UInt = dwordAt(payloadOffset + index * 4)

    val payloadDwords: Int get() = payloadBytes / 4

    val jtOffsetBytes: Int get() = payloadOffset + dwordAt(0x24).toInt() * 4

    val jtSizeBytes: Int get() = dwordAt(0x28).toInt() * 4
}

object GpuFirmware {
    private val cache = mutableMapOf<String, Ucode>()

    fun load(name: String): Ucode? {
        cache[name]?.let { return it }

        val label = name.removePrefix("picasso_").removeSuffix(".bin")

        val volume = StorageService.system()
        if (volume == null) {
            KLog.step("gpu", label, false, "esp not mounted")
            return null
        }

        val data = volume.read("/firmware/$name", MAX_BYTES)
        if (data == null) {
            KLog.step("gpu", label, false, "not found")
            return null
        }

        if (data.size < 0x30) {
            KLog.step("gpu", label, false, "truncated (${data.size} bytes)")
            return null
        }

        val sizeBytes = dword(data, 0x00)
        val headerMajor = dword(data, 0x08) and 0xFFFFu
        val ucodeVersion = dword(data, 0x10)
        val ucodeSize = dword(data, 0x14)
        val ucodeOffset = dword(data, 0x18)
        val featureVersion = dword(data, 0x20)

        if (sizeBytes.toInt() != data.size || headerMajor !in 1u..2u) {
            KLog.step("gpu", label, false, "bad header size=${KLog.hex(sizeBytes)} ver=$headerMajor")
            return null
        }

        if (ucodeOffset.toInt() + ucodeSize.toInt() > data.size) {
            KLog.step("gpu", label, false, "payload out of range")
            return null
        }

        KLog.step("gpu", label, true, "v${KLog.hex(ucodeVersion)} ${ucodeSize} bytes")
        val ucode = Ucode(name, ucodeVersion, featureVersion, data, ucodeOffset.toInt(), ucodeSize.toInt())
        cache[name] = ucode
        return ucode
    }

    private fun dword(data: ByteArray, at: Int): UInt =
        (data[at].toUInt() and 0xFFu) or
            ((data[at + 1].toUInt() and 0xFFu) shl 8) or
            ((data[at + 2].toUInt() and 0xFFu) shl 16) or
            ((data[at + 3].toUInt() and 0xFFu) shl 24)

    private const val MAX_BYTES: UInt = 0x100000u
}
