package kernel.drivers.usb

import kapi.Pad

class HidField(
    val reportId: Int,
    val bitOffset: Int,
    val bitSize: Int,
    val logicalMin: Int,
    val logicalMax: Int,
    val kind: Int,
    val target: Int,
)

class HidReportMap(
    private val fields: List<HidField>,
    private val usesReportIds: Boolean,
    val topUsage: Int,
) {
    val gamepad: Boolean
        get() = topUsage == HidReport.USAGE_JOYSTICK ||
            topUsage == HidReport.USAGE_GAMEPAD ||
            topUsage == HidReport.USAGE_MULTI_AXIS

    val inputs: Int get() = fields.size

    fun decode(report: ByteArray, length: Int, buttons: BooleanArray, axes: IntArray) {
        if (length <= 0) return

        val id = if (usesReportIds) report[0].toInt() and 0xFF else -1
        val base = if (usesReportIds) 1 else 0

        var matched = false
        for (field in fields) {
            if (usesReportIds && field.reportId != id) continue
            matched = true
            break
        }
        if (!matched) return

        for (button in buttons.indices) buttons[button] = false
        for (axis in axes.indices) axes[axis] = 0

        for (field in fields) {
            if (usesReportIds && field.reportId != id) continue

            val raw = extract(report, length, base, field)

            when (field.kind) {
                HidReport.KIND_BUTTON -> if (field.target < buttons.size) buttons[field.target] = raw != 0

                HidReport.KIND_AXIS -> {
                    if (field.target < axes.size) axes[field.target] = scale(raw, field)
                }

                HidReport.KIND_HAT -> applyHat(raw - field.logicalMin, buttons)
            }
        }
    }

    private fun scale(raw: Int, field: HidField): Int {
        val range = field.logicalMax - field.logicalMin
        if (range <= 0) return 0

        val positive = field.target == Pad.AXIS_LT || field.target == Pad.AXIS_RT
        if (positive) return (raw - field.logicalMin) * 32767 / range

        val centered = (raw - field.logicalMin) * 2 - range
        var value = centered * 32767 / range
        if (value < -32767) value = -32767

        val inverted = field.target == Pad.AXIS_LY || field.target == Pad.AXIS_RY
        return if (inverted) -value else value
    }

    private fun applyHat(direction: Int, buttons: BooleanArray) {
        val up = direction == 7 || direction == 0 || direction == 1
        val right = direction in 1..3
        val down = direction in 3..5
        val left = direction in 5..7

        buttons[Pad.DPAD_UP] = up
        buttons[Pad.DPAD_RIGHT] = right
        buttons[Pad.DPAD_DOWN] = down
        buttons[Pad.DPAD_LEFT] = left
    }

    private fun extract(report: ByteArray, length: Int, base: Int, field: HidField): Int {
        var value = 0

        for (bit in 0 until field.bitSize) {
            val absolute = field.bitOffset + bit
            val index = base + (absolute ushr 3)
            if (index >= length) break

            if ((report[index].toInt() shr (absolute and 7)) and 1 != 0) {
                value = value or (1 shl bit)
            }
        }

        if (field.logicalMin < 0 && field.bitSize in 1..31) {
            val sign = 1 shl (field.bitSize - 1)
            if (value and sign != 0) value -= sign shl 1
        }

        return value
    }
}

object HidReport {
    fun parse(descriptor: ByteArray): HidReportMap? {
        val fields = mutableListOf<HidField>()

        var usagePage = 0
        var logicalMinRaw = 0
        var logicalMinSize = 0
        var logicalMaxRaw = 0
        var logicalMaxSize = 0
        var reportSize = 0
        var reportCount = 0
        var reportId = 0
        var usesIds = false

        val globalsStack = mutableListOf<IntArray>()

        val usages = mutableListOf<Int>()
        var usageMin = -1
        var usageMax = -1

        var depth = 0
        var topUsage = 0

        val offsets = IntArray(256)

        var i = 0
        while (i < descriptor.size) {
            val prefix = descriptor[i].toInt() and 0xFF

            if (prefix == LONG_ITEM) {
                val dataSize = if (i + 1 < descriptor.size) descriptor[i + 1].toInt() and 0xFF else 0
                i += 3 + dataSize
                continue
            }

            var size = prefix and 0x03
            if (size == 3) size = 4

            val type = (prefix shr 2) and 0x03
            val tag = (prefix shr 4) and 0x0F

            var value = 0
            for (b in 0 until size) {
                val at = i + 1 + b
                if (at < descriptor.size) {
                    value = value or ((descriptor[at].toInt() and 0xFF) shl (8 * b))
                }
            }

            when (type) {
                TYPE_GLOBAL -> when (tag) {
                    TAG_USAGE_PAGE -> usagePage = value

                    TAG_LOGICAL_MIN -> {
                        logicalMinRaw = value
                        logicalMinSize = size
                    }

                    TAG_LOGICAL_MAX -> {
                        logicalMaxRaw = value
                        logicalMaxSize = size
                    }

                    TAG_REPORT_SIZE -> reportSize = value
                    TAG_REPORT_ID -> {
                        reportId = value
                        usesIds = true
                    }

                    TAG_REPORT_COUNT -> reportCount = value

                    TAG_PUSH -> globalsStack.add(
                        intArrayOf(
                            usagePage, logicalMinRaw, logicalMinSize,
                            logicalMaxRaw, logicalMaxSize, reportSize, reportCount, reportId,
                        )
                    )

                    TAG_POP -> globalsStack.removeLastOrNull()?.let {
                        usagePage = it[0]
                        logicalMinRaw = it[1]
                        logicalMinSize = it[2]
                        logicalMaxRaw = it[3]
                        logicalMaxSize = it[4]
                        reportSize = it[5]
                        reportCount = it[6]
                        reportId = it[7]
                    }
                }

                TYPE_LOCAL -> when (tag) {
                    TAG_USAGE -> usages.add(qualify(value, size, usagePage))
                    TAG_USAGE_MIN -> usageMin = qualify(value, size, usagePage)
                    TAG_USAGE_MAX -> usageMax = qualify(value, size, usagePage)
                }

                TYPE_MAIN -> {
                    when (tag) {
                        TAG_COLLECTION -> {
                            if (depth == 0 && topUsage == 0 && usages.isNotEmpty()) topUsage = usages[0]
                            depth++
                        }

                        TAG_END_COLLECTION -> if (depth > 0) depth--

                        TAG_INPUT -> {
                            val min = signed(logicalMinRaw, logicalMinSize)
                            var max = signed(logicalMaxRaw, logicalMaxSize)
                            if (max < min) max = logicalMaxRaw

                            val constant = value and 0x01 != 0
                            val variable = value and 0x02 != 0

                            if (!constant && variable) {
                                for (index in 0 until reportCount) {
                                    val usage = usageAt(usages, usageMin, usageMax, index)
                                    val field = mapUsage(usage)

                                    if (field != null) {
                                        fields.add(
                                            HidField(
                                                reportId = reportId,
                                                bitOffset = offsets[reportId] + index * reportSize,
                                                bitSize = reportSize,
                                                logicalMin = min,
                                                logicalMax = max,
                                                kind = field shr 16,
                                                target = field and 0xFFFF,
                                            )
                                        )
                                    }
                                }
                            }

                            offsets[reportId] += reportSize * reportCount
                        }
                    }

                    usages.clear()
                    usageMin = -1
                    usageMax = -1
                }
            }

            i += 1 + size
        }

        if (fields.isEmpty()) return null
        return HidReportMap(fields, usesIds, topUsage)
    }

    private fun qualify(value: Int, size: Int, page: Int): Int =
        if (size == 4) value else (page shl 16) or (value and 0xFFFF)

    private fun signed(raw: Int, size: Int): Int = when (size) {
        1 -> raw.toByte().toInt()
        2 -> raw.toShort().toInt()
        else -> raw
    }

    private fun usageAt(usages: List<Int>, min: Int, max: Int, index: Int): Int {
        if (index < usages.size) return usages[index]

        if (min >= 0 && max >= min) {
            val offset = index - usages.size
            if (min + offset <= max) return min + offset
        }

        return usages.lastOrNull() ?: -1
    }

    private fun mapUsage(usage: Int): Int? {
        if (usage < 0) return null

        val page = usage ushr 16
        val id = usage and 0xFFFF

        if (page == PAGE_BUTTON) {
            if (id < 1 || id > BUTTON_ORDER.size) return null
            return (KIND_BUTTON shl 16) or BUTTON_ORDER[id - 1]
        }

        if (page == PAGE_GENERIC_DESKTOP) {
            return when (id) {
                0x30 -> (KIND_AXIS shl 16) or Pad.AXIS_LX
                0x31 -> (KIND_AXIS shl 16) or Pad.AXIS_LY
                0x32 -> (KIND_AXIS shl 16) or Pad.AXIS_RX
                0x35 -> (KIND_AXIS shl 16) or Pad.AXIS_RY
                0x33 -> (KIND_AXIS shl 16) or Pad.AXIS_LT
                0x34 -> (KIND_AXIS shl 16) or Pad.AXIS_RT
                0x39 -> KIND_HAT shl 16
                else -> null
            }
        }

        if (page == PAGE_SIMULATION) {
            return when (id) {
                0xC4 -> (KIND_AXIS shl 16) or Pad.AXIS_RT
                0xC5 -> (KIND_AXIS shl 16) or Pad.AXIS_LT
                else -> null
            }
        }

        return null
    }

    const val KIND_BUTTON = 0
    const val KIND_AXIS = 1
    const val KIND_HAT = 2

    const val USAGE_JOYSTICK = 0x00010004
    const val USAGE_GAMEPAD = 0x00010005
    const val USAGE_MULTI_AXIS = 0x00010008

    private const val PAGE_GENERIC_DESKTOP = 0x01
    private const val PAGE_SIMULATION = 0x02
    private const val PAGE_BUTTON = 0x09

    private const val LONG_ITEM = 0xFE

    private const val TYPE_MAIN = 0
    private const val TYPE_GLOBAL = 1
    private const val TYPE_LOCAL = 2

    private const val TAG_INPUT = 8
    private const val TAG_COLLECTION = 10
    private const val TAG_END_COLLECTION = 12

    private const val TAG_USAGE_PAGE = 0
    private const val TAG_LOGICAL_MIN = 1
    private const val TAG_LOGICAL_MAX = 2
    private const val TAG_REPORT_SIZE = 7
    private const val TAG_REPORT_ID = 8
    private const val TAG_REPORT_COUNT = 9
    private const val TAG_PUSH = 10
    private const val TAG_POP = 11

    private const val TAG_USAGE = 0
    private const val TAG_USAGE_MIN = 1
    private const val TAG_USAGE_MAX = 2

    private val BUTTON_ORDER = intArrayOf(
        Pad.A, Pad.B, Pad.X, Pad.Y,
        Pad.L, Pad.R, Pad.SELECT, Pad.START,
        Pad.GUIDE, Pad.L3, Pad.R3, Pad.LT, Pad.RT,
    )
}
