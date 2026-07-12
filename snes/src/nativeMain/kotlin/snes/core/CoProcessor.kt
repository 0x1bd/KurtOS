package snes.core

import kapi.state.StateReader
import kapi.state.StateWriter

interface CoProcessor {
    val id: Int

    fun read(addr: Int): Int
    fun write(addr: Int, value: Int)
    fun reset()
    fun save(writer: StateWriter)
    fun load(reader: StateReader)
}

object ChipId {
    const val NONE = 0
    const val DSP1 = 1
}
