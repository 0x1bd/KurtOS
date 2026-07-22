package spezi.frontend.semantic

import spezi.domain.ArrayType
import spezi.domain.BoolType
import spezi.domain.FloatType
import spezi.domain.IntType
import spezi.domain.PtrType
import spezi.domain.SliceType
import spezi.domain.StructDef
import spezi.domain.StructType
import spezi.domain.Type
import spezi.domain.VectorType

class StructLayout(val size: Int, val align: Int, val offsets: IntArray)

class Layout(private val structs: Map<String, StructDef>) {

    private val cache = HashMap<String, StructLayout>()
    private val visiting = HashSet<String>()

    fun sizeOf(type: Type): Int = when (type) {
        BoolType -> 1
        is IntType -> type.bits / 8
        is FloatType -> type.bits / 8
        is VectorType -> sizeOf(type.elem) * roundLanes(type.lanes)
        is ArrayType -> sizeOf(type.elem) * type.length
        is PtrType -> 8
        is SliceType -> 16
        is StructType -> structLayout(type.name)?.size ?: 0
        else -> 0
    }

    fun alignOf(type: Type): Int = when (type) {
        is VectorType -> sizeOf(type.elem) * roundLanes(type.lanes)
        is ArrayType -> alignOf(type.elem)
        is SliceType -> 8
        is StructType -> structLayout(type.name)?.align ?: 1
        else -> sizeOf(type).coerceAtLeast(1)
    }

    fun structLayout(name: String): StructLayout? {
        cache[name]?.let { return it }
        val def = structs[name] ?: return null
        if (!visiting.add(name)) return null

        var offset = 0
        var align = 1
        val offsets = IntArray(def.fields.size)
        for ((index, field) in def.fields.withIndex()) {
            val fieldAlign = alignOf(field.type).coerceAtLeast(1)
            if (fieldAlign > align) align = fieldAlign
            offset = roundUp(offset, fieldAlign)
            offsets[index] = offset
            offset += sizeOf(field.type)
        }

        visiting.remove(name)
        val layout = StructLayout(roundUp(offset, align), align, offsets)
        cache[name] = layout
        return layout
    }

    fun isRecursive(name: String, seen: MutableSet<String> = HashSet()): Boolean {
        if (!seen.add(name)) return true
        val def = structs[name] ?: return false
        for (field in def.fields) {
            val nested = containedStruct(field.type) ?: continue
            if (isRecursive(nested, seen)) return true
        }
        seen.remove(name)
        return false
    }

    private fun containedStruct(type: Type): String? = when (type) {
        is StructType -> type.name
        is ArrayType -> containedStruct(type.elem)
        else -> null
    }

    private fun roundLanes(lanes: Int): Int = when {
        lanes <= 1 -> 1
        lanes <= 2 -> 2
        lanes <= 4 -> 4
        lanes <= 8 -> 8
        else -> 16
    }

    private fun roundUp(value: Int, align: Int): Int = (value + align - 1) / align * align
}
