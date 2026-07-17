package kernel.drivers.gpu.vega

import kernel.drivers.gpu.GpuLog

import kernel.fs.StorageService

class Ucode(
    val name: String,
    val version: UInt,
    val featureVersion: UInt,
    val data: ByteArray,
    val payloadOffset: Int,
    val payloadBytes: Int,
) {
    fun payloadDword(index: Int): UInt {
        val at = payloadOffset + index * 4
        return (data[at].toUInt() and 0xFFu) or
            ((data[at + 1].toUInt() and 0xFFu) shl 8) or
            ((data[at + 2].toUInt() and 0xFFu) shl 16) or
            ((data[at + 3].toUInt() and 0xFFu) shl 24)
    }

    val payloadDwords: Int get() = payloadBytes / 4
}

object GpuFirmware {
    fun load(name: String): Ucode? {
        val label = name.removePrefix("picasso_").removeSuffix(".bin")

        val volume = StorageService.system()
        if (volume == null) {
            GpuLog.step(label, false, "esp not mounted")
            return null
        }

        val data = volume.read("/firmware/$name", MAX_BYTES)
        if (data == null) {
            GpuLog.step(label, false, "not found")
            return null
        }

        if (data.size < 0x30) {
            GpuLog.step(label, false, "truncated (${data.size} bytes)")
            return null
        }

        val sizeBytes = dword(data, 0x00)
        val headerMajor = dword(data, 0x08) and 0xFFFFu
        val ucodeVersion = dword(data, 0x10)
        val ucodeSize = dword(data, 0x14)
        val ucodeOffset = dword(data, 0x18)
        val featureVersion = dword(data, 0x20)

        if (sizeBytes.toInt() != data.size || headerMajor !in 1u..2u) {
            GpuLog.step(label, false, "bad header size=${GpuLog.hex(sizeBytes)} ver=$headerMajor")
            return null
        }

        if (ucodeOffset.toInt() + ucodeSize.toInt() > data.size) {
            GpuLog.step(label, false, "payload out of range")
            return null
        }

        GpuLog.step(label, true, "v${GpuLog.hex(ucodeVersion)} ${ucodeSize} bytes")
        return Ucode(name, ucodeVersion, featureVersion, data, ucodeOffset.toInt(), ucodeSize.toInt())
    }

    private fun dword(data: ByteArray, at: Int): UInt =
        (data[at].toUInt() and 0xFFu) or
            ((data[at + 1].toUInt() and 0xFFu) shl 8) or
            ((data[at + 2].toUInt() and 0xFFu) shl 16) or
            ((data[at + 3].toUInt() and 0xFFu) shl 24)

    private const val MAX_BYTES: UInt = 0x100000u
}
