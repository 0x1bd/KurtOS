package spezi.backend.llvm

import spezi.backend.target.TargetProfile
import spezi.domain.*

class LlvmTypes(private val profile: TargetProfile) {

    fun value(type: Type): String = when (type) {
        VoidType -> "void"
        BoolType -> "i1"
        is IntType -> "i${type.bits}"
        is FloatType -> when (type.bits) {
            16 -> "half"
            32 -> "float"
            else -> "double"
        }

        is VectorType -> "<${type.lanes} x ${value(type.elem)}>"
        is ArrayType -> "[${type.length} x ${storage(type.elem)}]"
        is PtrType -> pointer(type.space)
        is SliceType -> "{ ${pointer(type.space)}, i32 }"
        is StructType -> "%struct.${type.name}"
        else -> "void"
    }

    fun storage(type: Type): String = if (type == BoolType) "i8" else value(type)

    fun pointer(space: AddressSpace): String {
        val id = profile.spaceOf(space)
        return if (id == 0) "ptr" else "ptr addrspace($id)"
    }

    fun zero(type: Type): String = when (type) {
        BoolType -> "false"
        is IntType -> "0"
        is FloatType -> "0.0"
        else -> "zeroinitializer"
    }

    fun isMemoryMismatch(type: Type): Boolean = type == BoolType
}
