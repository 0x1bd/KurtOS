package spezi.domain

enum class AddressSpace(val keyword: String) {
    Private("private"),
    Global("global"),
    Shared("shared"),
    Uniform("uniform");

    companion object {

        fun fromKeyword(word: String): AddressSpace? = entries.firstOrNull { it.keyword == word }
    }
}

sealed interface Type {

    val name: String
}

sealed interface Scalar : Type

data object VoidType : Type {

    override val name = "void"
}

data object BoolType : Scalar {

    override val name = "bool"
}

data class IntType(val bits: Int, val signed: Boolean) : Scalar {

    override val name = (if (signed) "i" else "u") + bits
}

data class FloatType(val bits: Int) : Scalar {

    override val name = "f$bits"
}

data class VectorType(val elem: Scalar, val lanes: Int) : Type {

    override val name = "${elem.name}x$lanes"
}

data class ArrayType(val elem: Type, val length: Int) : Type {

    override val name = "[$length]${elem.name}"
}

data class PtrType(val pointee: Type, val space: AddressSpace, val mutable: Boolean) : Type {

    override val name = buildString {
        if (space != AddressSpace.Private) append(space.keyword).append(' ')
        if (mutable) append("mut ")
        append('*').append(pointee.name)
    }
}

data class SliceType(val elem: Type, val space: AddressSpace, val mutable: Boolean) : Type {

    override val name = buildString {
        if (space != AddressSpace.Private) append(space.keyword).append(' ')
        if (mutable) append("mut ")
        append("[]").append(elem.name)
    }
}

data class StructType(override val name: String) : Type

data object UnknownType : Type {

    override val name = "unknown"
}

data object ErrorType : Type {

    override val name = "<error>"
}

object Types {

    val I8 = IntType(8, true)
    val I16 = IntType(16, true)
    val I32 = IntType(32, true)
    val I64 = IntType(64, true)
    val U8 = IntType(8, false)
    val U16 = IntType(16, false)
    val U32 = IntType(32, false)
    val U64 = IntType(64, false)
    val F16 = FloatType(16)
    val F32 = FloatType(32)
    val F64 = FloatType(64)

    val scalarsByName: Map<String, Scalar> = buildMap {
        put("bool", BoolType)
        listOf(I8, I16, I32, I64, U8, U16, U32, U64, F16, F32, F64).forEach { put(it.name, it) }
    }

    val vectorLanes = intArrayOf(2, 3, 4, 8, 16)

    fun vectorByName(word: String): VectorType? {
        val split = word.lastIndexOf('x')
        if (split <= 0 || split == word.length - 1) return null
        val elem = scalarsByName[word.substring(0, split)] ?: return null
        if (elem == BoolType) return null
        val lanes = word.substring(split + 1).toIntOrNull() ?: return null
        if (lanes !in vectorLanes) return null
        return VectorType(elem, lanes)
    }
}

val Type.isInt: Boolean get() = this is IntType
val Type.isFloat: Boolean get() = this is FloatType
val Type.isNumeric: Boolean get() = this is IntType || this is FloatType
val Type.isSigned: Boolean get() = this is IntType && signed || this is FloatType

val Type.scalarBits: Int
    get() = when (this) {
        is IntType -> bits
        is FloatType -> bits
        BoolType -> 1
        else -> 0
    }

val Type.elementOrSelf: Type
    get() = when (this) {
        is VectorType -> elem
        else -> this
    }

val Type.laneCount: Int
    get() = when (this) {
        is VectorType -> lanes
        else -> 1
    }

fun Type.withLanes(elem: Scalar): Type = when (this) {
    is VectorType -> VectorType(elem, lanes)
    else -> elem
}

val Type.pointerSpace: AddressSpace?
    get() = when (this) {
        is PtrType -> space
        is SliceType -> space
        else -> null
    }

val Type.isIndexable: Boolean get() = this is SliceType || this is PtrType || this is ArrayType || this is VectorType

val Type.indexedElement: Type?
    get() = when (this) {
        is SliceType -> elem
        is PtrType -> pointee
        is ArrayType -> elem
        is VectorType -> elem
        else -> null
    }
