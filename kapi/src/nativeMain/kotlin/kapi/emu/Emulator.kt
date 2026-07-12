package kapi.emu

object Button {
    const val A = 0x001
    const val B = 0x002
    const val SELECT = 0x004
    const val START = 0x008
    const val RIGHT = 0x010
    const val LEFT = 0x020
    const val UP = 0x040
    const val DOWN = 0x080
    const val R = 0x100
    const val L = 0x200
}

sealed class Video(val width: Int, val height: Int) {
    class Indexed(
        width: Int,
        height: Int,
        val paletteSize: Int,
        val frame: ByteArray,
        val palette: IntArray,
        val paletteVersion: () -> Int,
    ) : Video(width, height)

    class HighColor(
        width: Int,
        height: Int,
        val frame: ShortArray,
    ) : Video(width, height)
}

interface EmulatorSession {
    val video: Video
    val audioSamples: ShortArray
    val audioFrames: Int

    fun setButtons(buttons: Int)
    fun runFrame()
    fun drainAudio()
    fun describe(): String?
}

interface Emulator {
    val id: String
    val system: String
    val extensions: List<String>
    val frameMicros: ULong

    fun load(image: ByteArray): EmulatorSession?
}
