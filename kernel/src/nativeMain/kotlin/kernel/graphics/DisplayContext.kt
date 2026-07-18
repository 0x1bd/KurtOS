package kernel.graphics

object DisplayContext {
    enum class Owner { CONSOLE, GPU }

    var owner: Owner = Owner.CONSOLE
        private set

    val consoleOwnsScanout: Boolean get() = owner == Owner.CONSOLE

    fun acquireGpu() {
        owner = Owner.GPU
    }

    fun releaseGpu() {
        owner = Owner.CONSOLE
    }
}
