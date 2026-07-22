package spezi.frontend.semantic

import spezi.domain.*

enum class BuiltinGroup {
    Math,
    Bits,
    Geometry,
    Bitcast,
    Select,
    Dispatch,
    Sync,
}

enum class Builtin(
    val fnName: String,
    val group: BuiltinGroup,
    val arity: Int,
) {
    Sqrt("sqrt", BuiltinGroup.Math, 1),
    Rsqrt("rsqrt", BuiltinGroup.Math, 1),
    Floor("floor", BuiltinGroup.Math, 1),
    Ceil("ceil", BuiltinGroup.Math, 1),
    Round("round", BuiltinGroup.Math, 1),
    Trunc("trunc", BuiltinGroup.Math, 1),
    Fract("fract", BuiltinGroup.Math, 1),
    Sin("sin", BuiltinGroup.Math, 1),
    Cos("cos", BuiltinGroup.Math, 1),
    Tan("tan", BuiltinGroup.Math, 1),
    Exp("exp", BuiltinGroup.Math, 1),
    Exp2("exp2", BuiltinGroup.Math, 1),
    Log("log", BuiltinGroup.Math, 1),
    Log2("log2", BuiltinGroup.Math, 1),
    Pow("pow", BuiltinGroup.Math, 2),
    Atan2("atan2", BuiltinGroup.Math, 2),
    FMod("fmod", BuiltinGroup.Math, 2),
    Fma("fma", BuiltinGroup.Math, 3),
    Mix("mix", BuiltinGroup.Math, 3),
    Step("step", BuiltinGroup.Math, 2),
    SmoothStep("smoothstep", BuiltinGroup.Math, 3),

    Abs("abs", BuiltinGroup.Math, 1),
    Sign("sign", BuiltinGroup.Math, 1),
    Min("min", BuiltinGroup.Math, 2),
    Max("max", BuiltinGroup.Math, 2),
    Clamp("clamp", BuiltinGroup.Math, 3),

    PopCount("popcount", BuiltinGroup.Bits, 1),
    CountLeadingZeros("clz", BuiltinGroup.Bits, 1),
    CountTrailingZeros("ctz", BuiltinGroup.Bits, 1),
    BitReverse("bitreverse", BuiltinGroup.Bits, 1),
    ByteSwap("byteswap", BuiltinGroup.Bits, 1),
    RotateLeft("rotl", BuiltinGroup.Bits, 2),
    RotateRight("rotr", BuiltinGroup.Bits, 2),
    MulHigh("mulhi", BuiltinGroup.Bits, 2),
    BitFieldExtract("bits", BuiltinGroup.Bits, 3),

    Dot("dot", BuiltinGroup.Geometry, 2),
    Length("length", BuiltinGroup.Geometry, 1),
    Distance("distance", BuiltinGroup.Geometry, 2),
    Normalize("normalize", BuiltinGroup.Geometry, 1),
    Cross("cross", BuiltinGroup.Geometry, 2),

    FloatBits("f32_bits", BuiltinGroup.Bitcast, 1),
    BitsFloat("bits_f32", BuiltinGroup.Bitcast, 1),
    DoubleBits("f64_bits", BuiltinGroup.Bitcast, 1),
    BitsDouble("bits_f64", BuiltinGroup.Bitcast, 1),

    Select("select", BuiltinGroup.Select, 3),

    WorkitemIdX("workitem_id_x", BuiltinGroup.Dispatch, 0),
    WorkitemIdY("workitem_id_y", BuiltinGroup.Dispatch, 0),
    WorkitemIdZ("workitem_id_z", BuiltinGroup.Dispatch, 0),
    WorkgroupIdX("workgroup_id_x", BuiltinGroup.Dispatch, 0),
    WorkgroupIdY("workgroup_id_y", BuiltinGroup.Dispatch, 0),
    WorkgroupIdZ("workgroup_id_z", BuiltinGroup.Dispatch, 0),
    WorkgroupSizeX("workgroup_size_x", BuiltinGroup.Dispatch, 0),
    WorkgroupSizeY("workgroup_size_y", BuiltinGroup.Dispatch, 0),
    WorkgroupSizeZ("workgroup_size_z", BuiltinGroup.Dispatch, 0),
    GlobalIdX("global_id_x", BuiltinGroup.Dispatch, 0),
    GlobalIdY("global_id_y", BuiltinGroup.Dispatch, 0),
    GlobalIdZ("global_id_z", BuiltinGroup.Dispatch, 0),
    SubgroupSize("subgroup_size", BuiltinGroup.Dispatch, 0),
    LaneId("lane_id", BuiltinGroup.Dispatch, 0),

    Barrier("barrier", BuiltinGroup.Sync, 0),
    MemFence("memfence", BuiltinGroup.Sync, 0),
    MemFenceWorkgroup("memfence_workgroup", BuiltinGroup.Sync, 0),
    MemFenceDevice("memfence_device", BuiltinGroup.Sync, 0);

    val isFloatOnly: Boolean
        get() = group == BuiltinGroup.Math && this !in INTEGER_CAPABLE

    fun resolve(args: List<Type>): Type? {
        if (args.size != arity) return null
        return when (group) {
            BuiltinGroup.Math -> resolveMath(args)
            BuiltinGroup.Bits -> resolveBits(args)
            BuiltinGroup.Geometry -> resolveGeometry(args)
            BuiltinGroup.Bitcast -> resolveBitcast(args)
            BuiltinGroup.Select -> resolveSelect(args)
            BuiltinGroup.Dispatch -> Types.U32
            BuiltinGroup.Sync -> VoidType
        }
    }

    private fun resolveMath(args: List<Type>): Type? {
        val first = args[0]
        val element = first.elementOrSelf
        if (!element.isNumeric) return null
        if (isFloatOnly && !element.isFloat) return null
        if (this == MulHigh && !element.isInt) return null
        for (arg in args) if (arg != first) return null
        return first
    }

    private fun resolveBits(args: List<Type>): Type? {
        val first = args[0]
        if (!first.elementOrSelf.isInt) return null
        for (arg in args) if (arg != first) return null
        return first
    }

    private fun resolveGeometry(args: List<Type>): Type? {
        val first = args[0]
        if (first !is VectorType || !first.elem.isFloat) return null
        for (arg in args) if (arg != first) return null
        return when (this) {
            Dot, Length, Distance -> first.elem
            Normalize -> first
            Cross -> if (first.lanes == 3) first else null
            else -> null
        }
    }

    private fun resolveBitcast(args: List<Type>): Type? = when (this) {
        FloatBits -> if (args[0] == Types.F32) Types.U32 else null
        BitsFloat -> if (args[0] == Types.U32) Types.F32 else null
        DoubleBits -> if (args[0] == Types.F64) Types.U64 else null
        BitsDouble -> if (args[0] == Types.U64) Types.F64 else null
        else -> null
    }

    private fun resolveSelect(args: List<Type>): Type? {
        if (args[0] != BoolType) return null
        if (args[1] != args[2]) return null
        return args[1]
    }

    companion object {

        private val INTEGER_CAPABLE = setOf(Abs, Sign, Min, Max, Clamp)

        private val byName: Map<String, List<Builtin>> = entries.groupBy { it.fnName }

        fun candidates(name: String): List<Builtin> = byName[name] ?: emptyList()

        fun isBuiltinName(name: String): Boolean = byName.containsKey(name)
    }
}
