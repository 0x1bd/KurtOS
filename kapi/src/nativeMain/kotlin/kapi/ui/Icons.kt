package kapi.ui

import kapi.Resources

object Icons {
    private val cache = mutableMapOf<String, Icon>()

    fun get(name: String): Icon {
        val cached = cache[name]
        if (cached != null) return cached

        val data = Resources.bytes("icons/$name.qoi") ?: return Icon.EMPTY

        val icon = Qoi.decode(data) ?: Icon.EMPTY
        cache[name] = icon
        return icon
    }
}
