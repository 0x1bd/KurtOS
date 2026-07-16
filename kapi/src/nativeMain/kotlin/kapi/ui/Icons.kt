package kapi.ui

import kapi.Files

object Icons {
    private val cache = mutableMapOf<String, Icon>()

    fun get(name: String): Icon {
        val cached = cache[name]
        if (cached != null) return cached

        val data = Files.read("/sys/icons/$name.qoi", MAX_BYTES) ?: return Icon.EMPTY

        val icon = Qoi.decode(data) ?: Icon.EMPTY
        cache[name] = icon
        return icon
    }

    private const val MAX_BYTES = 262144u
}
