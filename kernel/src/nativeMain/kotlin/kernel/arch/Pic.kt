package kernel.arch

import hal.Port

object Pic {
    private const val PIC1_COMMAND: UShort = 0x20u
    private const val PIC1_DATA: UShort = 0x21u
    private const val PIC2_COMMAND: UShort = 0xA0u
    private const val PIC2_DATA: UShort = 0xA1u

    fun disable() {
        Port.write8(PIC1_COMMAND, 0x11u)
        Port.wait()
        Port.write8(PIC2_COMMAND, 0x11u)
        Port.wait()

        Port.write8(PIC1_DATA, 0x30u)
        Port.wait()
        Port.write8(PIC2_DATA, 0x38u)
        Port.wait()

        Port.write8(PIC1_DATA, 0x04u)
        Port.wait()
        Port.write8(PIC2_DATA, 0x02u)
        Port.wait()

        Port.write8(PIC1_DATA, 0x01u)
        Port.wait()
        Port.write8(PIC2_DATA, 0x01u)
        Port.wait()

        Port.write8(PIC1_DATA, 0xFFu)
        Port.write8(PIC2_DATA, 0xFFu)
    }
}
