package kapi.ui

enum class Axis { VERTICAL, HORIZONTAL, BOTH }

class Focusable(
    val id: String,
    val x: Int = 0,
    val y: Int = 0,
    val w: Int = 0,
    val h: Int = 0,
    val onActivate: (() -> Unit)? = null,
    val onAdjust: ((Int) -> Unit)? = null,
    val draw: ((Boolean) -> Unit)? = null,
)

class Menu(private val axis: Axis = Axis.VERTICAL, private val wrap: Boolean = true) {
    private val items = ArrayList<Focusable>()
    private var focusedId: String? = null

    var scroll: Int = 0
        private set

    fun begin() {
        items.clear()
    }

    fun add(item: Focusable) {
        items.add(item)
    }

    fun reset() {
        items.clear()
        focusedId = null
        scroll = 0
    }

    fun focus(index: Int) {
        if (index in items.indices) focusedId = items[index].id
    }

    fun focusedIndex(): Int {
        val id = focusedId ?: return 0
        val index = items.indexOfFirst { it.id == id }
        return if (index < 0) 0 else index
    }

    fun update(nav: NavFrame, onBack: (() -> Unit)? = null) {
        ensureFocus()

        if (nav.back) {
            onBack?.invoke()
            return
        }

        if (items.isEmpty()) return

        if (nav.moved) {
            val focused = items[focusedIndex()]
            val cross = crossAxis(nav)
            if (cross != 0 && focused.onAdjust != null) {
                focused.onAdjust.invoke(cross)
            } else {
                val step = moveAxis(nav)
                if (step != 0) moveFocus(step)
            }
        }

        if (nav.activate) items[focusedIndex()].onActivate?.invoke()
    }

    fun paint() {
        val id = focusedId
        for (item in items) item.draw?.invoke(item.id == id)
    }

    fun window(count: Int, capacity: Int): Int {
        val focused = focusedIndex()
        if (focused < scroll) scroll = focused
        if (focused >= scroll + capacity) scroll = focused - capacity + 1
        scroll = scroll.coerceIn(0, maxOf(0, count - capacity))
        return scroll
    }

    private fun moveAxis(nav: NavFrame): Int = when (axis) {
        Axis.VERTICAL -> nav.dy
        Axis.HORIZONTAL -> nav.dx
        Axis.BOTH -> if (nav.dy != 0) nav.dy else nav.dx
    }

    private fun crossAxis(nav: NavFrame): Int = when (axis) {
        Axis.VERTICAL -> nav.dx
        Axis.HORIZONTAL -> nav.dy
        Axis.BOTH -> 0
    }

    private fun ensureFocus() {
        if (items.isEmpty()) {
            focusedId = null
            return
        }
        if (focusedId != null && items.any { it.id == focusedId }) return
        focusedId = items[0].id
    }

    private fun moveFocus(step: Int) {
        if (items.size <= 1) return

        val delta = if (step > 0) 1 else -1
        val n = items.size
        val current = focusedIndex()
        val next = if (wrap) ((current + delta) % n + n) % n else (current + delta).coerceIn(0, n - 1)
        focusedId = items[next].id
    }
}
