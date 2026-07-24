package hal

object Arch {
    fun isrStub(vector: Int): ULong = Hal.arch.isrStub(vector)

    fun loadGdt(descriptorAddress: ULong, codeSelector: UShort, dataSelector: UShort) =
        Hal.arch.loadGdt(descriptorAddress, codeSelector, dataSelector)

    fun loadTaskRegister(selector: UShort) = Hal.arch.loadTaskRegister(selector)

    fun loadIdt(descriptorAddress: ULong) = Hal.arch.loadIdt(descriptorAddress)

    fun setLapicBase(base: ULong) = Hal.arch.setLapicBase(base)

    fun ticks(): ULong = Hal.arch.ticks()

    fun nextScancode(): Int = Hal.arch.nextScancode()

    fun enableKeyboardPoll() = Hal.arch.enableKeyboardPoll()

    fun droppedScancodes(): ULong = Hal.arch.droppedScancodes()

    fun keyboardInterrupts(): ULong = Hal.arch.keyboardInterrupts()

    fun usbInterrupts(): ULong = Hal.arch.usbInterrupts()

    fun smpStart(): Int = Hal.arch.smpStart()

    fun smpCpus(): Int = Hal.arch.smpCpus()
}
