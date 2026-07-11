package hal

private const val COM1: UShort = 0x3F8u

private const val REG_DATA: UShort = 0u
private const val REG_INTERRUPT_ENABLE: UShort = 1u
private const val REG_FIFO_CONTROL: UShort = 2u
private const val REG_LINE_CONTROL: UShort = 3u
private const val REG_MODEM_CONTROL: UShort = 4u
private const val REG_LINE_STATUS: UShort = 5u
private const val REG_SCRATCH: UShort = 7u

private const val LINE_STATUS_DATA_READY: UByte = 0x01u
private const val LINE_STATUS_TX_EMPTY: UByte = 0x20u

private const val TX_SPIN_LIMIT = 100_000

object Serial {
    private var present = false
    private var probed = false

    val isPresent: Boolean
        get() {
            initialize()
            return present
        }

    fun initialize() {
        if (probed) return
        probed = true

        write(REG_SCRATCH, 0xA5u)
        Port.wait()
        if (read(REG_SCRATCH) != (0xA5u).toUByte()) {
            present = false
            return
        }

        write(REG_INTERRUPT_ENABLE, 0x00u)
        write(REG_LINE_CONTROL, 0x80u)
        write(REG_DATA, 0x01u)
        write(REG_INTERRUPT_ENABLE, 0x00u)
        write(REG_LINE_CONTROL, 0x03u)
        write(REG_FIFO_CONTROL, 0xC7u)
        write(REG_MODEM_CONTROL, 0x0Bu)

        present = true
    }

    fun putChar(c: Char) {
        initialize()
        if (!present) return

        if (c == '\n') writeByte('\r'.code.toUByte())
        writeByte(c.code.toUByte())
    }

    fun print(text: String) {
        for (c in text) putChar(c)
    }

    fun println(text: String) {
        print(text)
        putChar('\n')
    }

    fun tryReadChar(): Char? {
        initialize()
        if (!present) return null
        if (read(REG_LINE_STATUS) and LINE_STATUS_DATA_READY == (0u).toUByte()) return null
        return read(REG_DATA).toInt().toChar()
    }

    private fun writeByte(value: UByte) {
        var spins = 0
        while (read(REG_LINE_STATUS) and LINE_STATUS_TX_EMPTY == (0u).toUByte()) {
            if (++spins > TX_SPIN_LIMIT) break
        }
        write(REG_DATA, value)
    }

    private fun read(register: UShort): UByte = Port.read8((COM1 + register).toUShort())

    private fun write(register: UShort, value: UByte) = Port.write8((COM1 + register).toUShort(), value)
}
