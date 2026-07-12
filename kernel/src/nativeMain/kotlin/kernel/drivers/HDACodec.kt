package kernel.drivers

class HdaPath(
    val codec: Int,
    val dac: Int,
    val pin: Int,
    val nodes: IntArray,
    val inputs: IntArray,
)

class HdaCodec(private val controller: HDAController, private val address: Int) {
    private var functionGroup = 0

    fun findOutputPath(): HdaPath? {
        val group = findAudioFunctionGroup() ?: return null
        functionGroup = group

        var best: HdaPath? = null
        var bestScore = -1

        for (widget in subordinates(group)) {
            if (typeOf(widget) != WIDGET_PIN) continue

            val pinCapabilities = parameter(widget, PIN_CAP)
            if (pinCapabilities and PIN_CAP_OUTPUT == 0) continue

            val config = command(widget, GET_CONFIG_DEFAULT, 0)
            if ((config shr 30) and 0x03 == PORT_NOT_CONNECTED) continue

            val score = when ((config shr 20) and 0x0F) {
                DEVICE_SPEAKER -> 3
                DEVICE_HEADPHONE -> 2
                DEVICE_LINE_OUT -> 1
                else -> 0
            }
            if (score <= bestScore) continue

            val path = trace(widget, 0) ?: continue

            bestScore = score
            best = path
        }

        return best
    }

    private fun trace(widget: Int, depth: Int): HdaPath? {
        if (depth > MAX_DEPTH) return null

        if (typeOf(widget) == WIDGET_OUTPUT) {
            return HdaPath(address, widget, widget, intArrayOf(widget), intArrayOf(-1))
        }

        val sources = connections(widget)
        for (index in sources.indices) {
            val found = trace(sources[index], depth + 1) ?: continue

            return HdaPath(
                codec = address,
                dac = found.dac,
                pin = if (depth == 0) widget else found.pin,
                nodes = found.nodes + widget,
                inputs = found.inputs + index,
            )
        }

        return null
    }

    fun activate(path: HdaPath, streamNumber: Int, format: Int) {
        command(functionGroup, SET_POWER_STATE, POWER_D0)

        setFormat(path.dac, format)
        command(path.dac, SET_CONVERTER_STREAM, streamNumber shl 4)

        for (i in path.nodes.indices) {
            val widget = path.nodes[i]
            val input = path.inputs[i]

            command(widget, SET_POWER_STATE, POWER_D0)

            val type = typeOf(widget)
            if (input >= 0 && connections(widget).size > 1 && type != WIDGET_MIXER) {
                command(widget, SET_CONNECTION_SELECT, input)
            }

            unmute(widget, input)
        }

        val pin = path.pin
        command(pin, SET_PIN_CONTROL, PIN_OUTPUT_ENABLE or PIN_HEADPHONE_ENABLE)

        if (parameter(pin, PIN_CAP) and PIN_CAP_EAPD != 0) {
            command(pin, SET_EAPD, EAPD_ENABLE)
        }
    }

    private fun unmute(widget: Int, input: Int) {
        val capabilities = parameter(widget, AUDIO_WIDGET_CAP)

        if (capabilities and WIDGET_CAP_OUT_AMP != 0) {
            val gain = maxGain(widget, OUTPUT_AMP_CAP)
            setAmp(widget, AMP_OUTPUT or AMP_LEFT or AMP_RIGHT or gain)
        }

        if (capabilities and WIDGET_CAP_IN_AMP != 0) {
            val gain = maxGain(widget, INPUT_AMP_CAP)
            val index = if (input < 0) 0 else input
            setAmp(widget, AMP_INPUT or AMP_LEFT or AMP_RIGHT or (index shl 8) or gain)
        }
    }

    private fun maxGain(widget: Int, capability: Int): Int {
        val amp = parameter(widget, capability)
        val steps = (amp shr 8) and 0x7F
        return steps
    }

    fun describe(): List<String> {
        val lines = mutableListOf<String>()
        val group = findAudioFunctionGroup()

        if (group == null) {
            lines.add("codec $address: no audio function group")
            return lines
        }

        lines.add("codec $address: afg $group")

        for (widget in subordinates(group)) {
            val capabilities = parameter(widget, AUDIO_WIDGET_CAP)
            val type = (capabilities shr 20) and 0x0F

            val name = when (type) {
                WIDGET_OUTPUT -> "dac"
                0x1 -> "adc"
                WIDGET_MIXER -> "mixer"
                WIDGET_SELECTOR -> "select"
                WIDGET_PIN -> "pin"
                else -> "type$type"
            }

            val builder = StringBuilder()
            builder.append("  nid $widget ".padEnd(11))
            builder.append(name.padEnd(7))

            if (capabilities and WIDGET_CAP_OUT_AMP != 0) builder.append(" oamp")
            if (capabilities and WIDGET_CAP_IN_AMP != 0) builder.append(" iamp")

            if (type == WIDGET_PIN) {
                val config = command(widget, GET_CONFIG_DEFAULT, 0)
                val pinCapabilities = parameter(widget, PIN_CAP)

                builder.append(" dev=${(config shr 20) and 0x0F}")
                builder.append(" conn=${(config shr 30) and 0x03}")
                builder.append(" ctl=0x${command(widget, GET_PIN_CONTROL, 0).toString(16)}")
                if (pinCapabilities and PIN_CAP_OUTPUT != 0) builder.append(" out")
                if (pinCapabilities and PIN_CAP_EAPD != 0) builder.append(" eapd")
            }

            val sources = connections(widget)
            if (sources.isNotEmpty()) {
                builder.append(" <- ")
                builder.append(sources.joinToString(","))
            }

            lines.add(builder.toString())
        }

        return lines
    }

    private fun typeOf(widget: Int): Int = (parameter(widget, AUDIO_WIDGET_CAP) shr 20) and 0x0F

    private fun findAudioFunctionGroup(): Int? {
        for (node in subordinates(0)) {
            if (parameter(node, FUNCTION_GROUP_TYPE) and 0x7F == FUNCTION_GROUP_AUDIO) return node
        }
        return null
    }

    private fun subordinates(node: Int): IntArray {
        val value = parameter(node, SUBORDINATE_NODE_COUNT)
        val start = (value shr 16) and 0xFF
        val count = value and 0xFF

        if (count == 0) return IntArray(0)
        return IntArray(count) { start + it }
    }

    private fun connections(widget: Int): IntArray {
        val length = parameter(widget, CONNECTION_LIST_LENGTH)
        val count = length and 0x7F
        if (count == 0) return IntArray(0)

        val longForm = length and 0x80 != 0
        val perEntry = if (longForm) 2 else 4
        val result = IntArray(count)

        var index = 0
        while (index < count) {
            val response = command(widget, GET_CONNECTION_LIST_ENTRY, index)

            for (i in 0 until perEntry) {
                if (index + i >= count) break
                result[index + i] = if (longForm) {
                    (response shr (i * 16)) and 0x7FFF
                } else {
                    (response shr (i * 8)) and 0x7F
                }
            }

            index += perEntry
        }

        return result
    }

    private fun parameter(node: Int, id: Int): Int = command(node, GET_PARAMETER, id)

    private fun command(node: Int, verb: Int, payload: Int): Int =
        controller.verb12(address, node, verb, payload)

    private fun setFormat(node: Int, format: Int): Int =
        controller.verb4(address, node, SET_CONVERTER_FORMAT, format)

    private fun setAmp(node: Int, payload: Int): Int =
        controller.verb4(address, node, SET_AMP_GAIN, payload)

    private companion object {
        const val MAX_DEPTH = 8

        const val GET_PARAMETER = 0xF00
        const val GET_CONNECTION_LIST_ENTRY = 0xF02
        const val GET_CONFIG_DEFAULT = 0xF1C
        const val GET_PIN_CONTROL = 0xF07
        const val SET_PIN_CONTROL = 0x707
        const val SET_EAPD = 0x70C
        const val SET_POWER_STATE = 0x705
        const val SET_CONVERTER_STREAM = 0x706
        const val SET_CONNECTION_SELECT = 0x701
        const val SET_CONVERTER_FORMAT = 0x2
        const val SET_AMP_GAIN = 0x3

        const val SUBORDINATE_NODE_COUNT = 0x04
        const val FUNCTION_GROUP_TYPE = 0x05
        const val AUDIO_WIDGET_CAP = 0x09
        const val PIN_CAP = 0x0C
        const val INPUT_AMP_CAP = 0x0D
        const val CONNECTION_LIST_LENGTH = 0x0E
        const val OUTPUT_AMP_CAP = 0x12

        const val FUNCTION_GROUP_AUDIO = 0x01

        const val WIDGET_OUTPUT = 0x0
        const val WIDGET_MIXER = 0x2
        const val WIDGET_SELECTOR = 0x3
        const val WIDGET_PIN = 0x4

        const val WIDGET_CAP_IN_AMP = 0x02
        const val WIDGET_CAP_OUT_AMP = 0x04

        const val PIN_CAP_OUTPUT = 0x10
        const val PIN_CAP_EAPD = 0x10000

        const val PORT_NOT_CONNECTED = 0x01
        const val DEVICE_LINE_OUT = 0x00
        const val DEVICE_SPEAKER = 0x01
        const val DEVICE_HEADPHONE = 0x02

        const val PIN_OUTPUT_ENABLE = 0x40
        const val PIN_HEADPHONE_ENABLE = 0x80
        const val EAPD_ENABLE = 0x02

        const val POWER_D0 = 0x00

        const val AMP_OUTPUT = 0x8000
        const val AMP_INPUT = 0x4000
        const val AMP_LEFT = 0x2000
        const val AMP_RIGHT = 0x1000
    }
}
