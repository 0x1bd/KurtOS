package snes.core

interface Memory {
    fun read8(addr: Int): Int
    fun write8(addr: Int, value: Int)
    fun speed(addr: Int): Int
}

interface SpcMemory {
    fun read(address: Int): Int
    fun write(address: Int, value: Int)
}
