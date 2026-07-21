package kapi.gfx

class RowBatch(
    private val stride: Int,
    maxSpans: Int,
    maxRows: Int,
    private val rowField: Int,
) {
    val spans = IntArray(maxSpans * stride)
    val sorted = IntArray(maxSpans * stride)
    val rowStart = IntArray(maxRows + 2)

    var spanCount = 0
        private set
    var maxRow = -1
        private set

    private val capacity = maxSpans
    private val cursor = IntArray(maxRows + 1)

    fun reset() {
        spanCount = 0
        maxRow = -1
    }

    val full: Boolean get() = spanCount >= capacity

    fun open(): Int {
        val base = spanCount * stride
        spanCount++
        return base
    }

    fun noteRow(row: Int) {
        if (row > maxRow) maxRow = row
    }

    fun bucketize() {
        val rows = maxRow + 1
        for (r in 0..rows) rowStart[r] = 0
        for (i in 0 until spanCount) rowStart[spans[i * stride + rowField] + 1]++
        for (r in 1..rows) rowStart[r] += rowStart[r - 1]
        for (r in 0 until rows) cursor[r] = rowStart[r]
        for (i in 0 until spanCount) {
            val row = spans[i * stride + rowField]
            val pos = cursor[row]
            cursor[row] = pos + 1
            spans.copyInto(sorted, pos * stride, i * stride, i * stride + stride)
        }
    }
}
