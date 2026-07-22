package spezi

import spezi.domain.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SemanticTest {

    @Test
    fun untypedLiteralsTakeTheirContext() {
        assertEquals(Types.U32, typeOfLet("let x: u32 = 5"))
        assertEquals(Types.I64, typeOfLet("let x: i64 = 5"))
        assertEquals(Types.F32, typeOfLet("let x: f32 = 2"))
        assertEquals(VectorType(Types.F32, 4), typeOfLet("let x: f32x4 = 1.0"))
    }

    @Test
    fun bareLiteralsFallBackToI32AndF32() {
        assertEquals(Types.I32, typeOfLet("let x = 5"))
        assertEquals(Types.F32, typeOfLet("let x = 1.5"))
    }

    @Test
    fun suffixesWin() {
        assertEquals(Types.U8, typeOfLet("let x = 5u8"))
        assertEquals(Types.F64, typeOfLet("let x = 1.5f64"))
    }

    @Test
    fun literalsAdaptToTheOtherOperand() {
        assertEquals(Types.U32, typeOfLet("let a: u32 = 1 let x = a + 7"))
        assertEquals(Types.F32, typeOfLet("let a: f32 = 1.0f let x = a * 2"))
    }

    @Test
    fun mixedTypesNeedAnExplicitCast() {
        assertFailsWith("fn f(a: u32, b: i32) -> void { let c = a + b }", "cannot apply '+'")
        assertCompiles("fn f(a: u32, b: i32) -> void { let c = a + u32(b) }")
    }

    @Test
    fun scalarBroadcastsIntoVectors() {
        assertEquals(VectorType(Types.F32, 4), typeOfLet("let v: f32x4 = f32x4(1.0f) let x = v * 2.0f"))
        assertEquals(VectorType(Types.F32, 4), typeOfLet("let v: f32x4 = f32x4(1.0f) let x = 2.0f + v"))
    }

    @Test
    fun comparisonsProduceBool() {
        assertEquals(BoolType, typeOfLet("let a: u32 = 1 let x = a < 2"))
        assertEquals(BoolType, typeOfLet("let x = true and false"))
    }

    @Test
    fun vectorComparisonIsRejected() {
        assertFailsWith(
            "fn f() -> void { let a = f32x4(1.0f) let b = f32x4(2.0f) let c = a < b }",
            "comparing vectors is not supported",
        )
    }

    @Test
    fun swizzlesResolve() {
        assertEquals(Types.F32, typeOfLet("let v: f32x4 = f32x4(1.0f) let x = v.x"))
        assertEquals(VectorType(Types.F32, 3), typeOfLet("let v: f32x4 = f32x4(1.0f) let x = v.zyx"))
        assertEquals(VectorType(Types.F32, 2), typeOfLet("let v: f32x4 = f32x4(1.0f) let x = v.rg"))
    }

    @Test
    fun swizzleSetsCannotMix() {
        assertFailsWith("fn f() -> void { let v = f32x4(1.0f) let x = v.xg }", "not a vector component")
    }

    @Test
    fun swizzleCannotReadPastTheEnd() {
        assertFailsWith("fn f() -> void { let v = f32x2(1.0f) let x = v.z }", "reads past the end")
    }

    @Test
    fun sliceLengthIsReadOnly() {
        assertEquals(Types.U32, typeOfLet("let x = out.len", inKernel = true))
        assertFailsWith("$KERNEL_PROLOGUE out.len = 3u32 }", "read only")
    }

    @Test
    fun immutableBindingsCannotBeAssigned() {
        assertFailsWith("fn f() -> void { let a: u32 = 1 a = 2 }", "not mutable")
        assertCompiles("fn f() -> void { let mut a: u32 = 1 a = 2 }")
    }

    @Test
    fun parametersAreImmutable() {
        assertFailsWith("fn f(a: u32) -> void { a = 2 }", "parameter 'a' is not mutable")
    }

    @Test
    fun readOnlySlicesCannotBeWritten() {
        assertFailsWith(
            "kernel fn k(src: global []u32) { src[0] = 1u32 }",
            "read only slice",
        )
        assertCompiles("kernel fn k(src: global mut []u32) { src[0] = 1u32 }")
    }

    @Test
    fun loopVariablesAreImmutable() {
        assertFailsWith("fn f() -> void { for i in 0u32..4u32 { i = 1u32 } }", "loop variable")
    }

    @Test
    fun breakAndContinueNeedALoop() {
        assertFailsWith("fn f() -> void { break }", "'break' is only valid inside a loop")
        assertFailsWith("fn f() -> void { continue }", "'continue' is only valid inside a loop")
        assertCompiles("fn f() -> void { loop { break } }")
    }

    @Test
    fun kernelsMustReturnVoid() {
        assertFailsWith("kernel fn k(a: global mut []u32) -> u32 { return 1u32 }", "must return void")
    }

    @Test
    fun kernelPointerParametersNeedAnAddressSpace() {
        assertFailsWith("kernel fn k(a: []u32) { }", "must name an address space")
        assertFailsWith("kernel fn k(a: shared []u32) { }", "cannot live in 'shared' storage")
        assertCompiles("kernel fn k(a: global mut []u32) { }")
        assertCompiles("kernel fn k(a: uniform []u32) { }")
    }

    @Test
    fun kernelsCannotBeCalled() {
        assertFailsWith(
            "kernel fn k(a: global mut []u32) { } fn f(a: global mut []u32) -> void { k(a) }",
            "cannot be called from Spezi code",
        )
    }

    @Test
    fun returningAPointerIntoLocalStorageIsRejected() {
        assertFailsWith(
            "fn f() -> *u32 { let mut a: u32 = 1 return &a }",
            "points into private storage",
        )
    }

    @Test
    fun writingThroughAGlobalPointerIsFine() {
        assertCompiles(
            """
            kernel fn k(out: global mut []u32) {
                let p = &out[0]
                *p = 7u32
            }
            """.trimIndent(),
        )
    }

    @Test
    fun aLeadingStarStartsANewStatementRatherThanMultiplying() {
        assertFailsWith("kernel fn k(out: global mut []u32) { let p = &out[0] *p = 7u32 }", "unexpected =")
    }

    @Test
    fun sharedStorageOnlyInsideKernels() {
        assertFailsWith("fn f() -> void { let shared mut t: [64]u32 }", "only be declared inside a kernel")
        assertCompiles("kernel fn k(a: global mut []u32) { let shared mut tile: [64]u32 tile[0] = 1u32 }")
    }

    @Test
    fun sharedStorageCannotBeInitialised() {
        assertFailsWith(
            "kernel fn k(a: global mut []u32) { let shared mut t: [4]u32 = 0 }",
            "cannot have an initializer",
        )
    }

    @Test
    fun sharedGlobalsAreCollected() {
        val model = Harness("kernel fn k(a: global mut []u32) { let shared mut tile: [64]u32 tile[0] = 1u32 }").analyze()
        assertEquals(1, model.sharedGlobals.size)
        assertTrue(model.functions.first().usesShared)
    }

    @Test
    fun switchNeedsConstantCases() {
        assertFailsWith(
            "fn f(v: u32, w: u32) -> void { switch v { case w -> { return } } }",
            "compile time constants",
        )
    }

    @Test
    fun duplicateCasesAreRejected() {
        assertFailsWith(
            "fn f(v: u32) -> void { switch v { case 1u32 -> { return } case 1u32 -> { return } } }",
            "duplicate case value",
        )
    }

    @Test
    fun switchNeedsAnIntegerSubject() {
        assertFailsWith(
            "fn f(v: f32) -> void { switch v { case 1u32 -> { return } } }",
            "needs an integer or bool",
        )
    }

    @Test
    fun constantsFoldAtCompileTime() {
        val model = Harness("const A: u32 = 4 const B: u32 = A * 2 + 1 kernel fn k(o: global mut []u32) { }").analyze()
        val b = model.constants.first { it.name == "B" }
        assertEquals(9L, b.evaluated?.asLong)
    }

    @Test
    fun constantExpressionsAreFolded() {
        assertCompiles("const A: u32 = 1 << 4")
    }

    @Test
    fun builtinOverloadsResolveOnArgumentType() {
        assertEquals(Types.F32, typeOfLet("let x = sqrt(2.0f)"))
        assertEquals(Types.U32, typeOfLet("let a: u32 = 3 let x = min(a, 5)"))
        assertEquals(Types.F32, typeOfLet("let x = min(1.0f, 2.0f)"))
        assertEquals(VectorType(Types.F32, 4), typeOfLet("let x = abs(f32x4(1.0f))"))
    }

    @Test
    fun floatOnlyBuiltinsRejectIntegers() {
        assertFailsWith("fn f() -> void { let x = sqrt(4u32) }", "no overload")
    }

    @Test
    fun geometryBuiltinsNeedFloatVectors() {
        assertEquals(Types.F32, typeOfLet("let x = dot(f32x4(1.0f), f32x4(2.0f))"))
        assertEquals(Types.F32, typeOfLet("let x = length(f32x3(1.0f))"))
        assertEquals(VectorType(Types.F32, 3), typeOfLet("let x = cross(f32x3(1.0f), f32x3(2.0f))"))
        assertFailsWith("fn f() -> void { let x = cross(f32x4(1.0f), f32x4(2.0f)) }", "no overload")
        assertFailsWith("fn f() -> void { let x = dot(1.0f, 2.0f) }", "no overload")
    }

    @Test
    fun dispatchBuiltinsAreU32() {
        assertEquals(Types.U32, typeOfLet("let x = global_id_x()", inKernel = true))
        assertEquals(Types.U32, typeOfLet("let x = workgroup_size_y()", inKernel = true))
    }

    @Test
    fun builtinsCannotBeRedefined() {
        assertFailsWith("fn sqrt(x: f32) -> f32 { return x }", "is a builtin")
    }

    @Test
    fun buffersCanBeViewedAtAnotherWidth() {
        assertCompiles("kernel fn k(a: global mut []u32) { let w = a as global mut []u16 w[0] = 1 }")
        assertCompiles("kernel fn k(a: global mut []u8) { let w = a as global mut []u32 w[0] = 1 }")
    }

    @Test
    fun aViewCannotGainMutability() {
        assertFailsWith(
            "kernel fn k(a: global []u32) { let w = a as global mut []u16 }",
            "read only buffer cannot become a mutable view",
        )
    }

    @Test
    fun aViewNeedsElementSizesThatDivide() {
        assertFailsWith(
            "kernel fn k(a: global mut []u32) { let w = a as global mut [3]u8 }",
            "cannot cast",
        )
    }

    @Test
    fun bitfieldExtractionTakesThreeIntegers() {
        assertEquals(Types.U32, typeOfLet("let a: u32 = 7 let x = bits(a, 16, 8)"))
        assertFailsWith("fn f(a: f32) -> void { let x = bits(a, 16, 8) }", "no overload")
    }

    @Test
    fun memoryScopesAreDistinctBuiltins() {
        assertCompiles("kernel fn k(a: global mut []u32) { memfence() memfence_workgroup() memfence_device() }")
    }

    @Test
    fun castRules() {
        assertCompiles("fn f(a: u32) -> void { let b = a as i32 let c = a as f32 let d = a as bool }")
        assertCompiles("kernel fn k(a: global mut []u32) { let p = a as global mut *u16 }")
        assertCompiles("fn f() -> void { let v = f32x4(1.0f) let w = v as u32x4 }")
        assertFailsWith("fn f(a: f32x4) -> void { let b = a as f32x2 }", "cannot cast")
    }

    @Test
    fun redundantCastWarns() {
        val harness = Harness("fn f(a: u32) -> void { let b = a as u32 }")
        harness.analyze()
        assertTrue(harness.warnings.any { it.contains("does nothing") }, harness.warnings.toString())
    }

    @Test
    fun unsignedNegationIsRejected() {
        assertFailsWith("fn f(a: u32) -> void { let b = -a }", "unsigned")
    }

    @Test
    fun arrayParametersAreRejected() {
        assertFailsWith("fn f(a: [4]u32) -> void { }", "pass a slice instead")
    }

    @Test
    fun arraysCoerceToSlices() {
        assertCompiles(
            "fn sum(v: []u32) -> u32 { return v[0] } fn f() -> u32 { let mut a: [4]u32 return sum(a) }",
        )
    }

    @Test
    fun structFieldsAndConstructors() {
        assertEquals(Types.F32, typeOfLet("let c = Rgba(1.0f, 2.0f) let x = c.r", extra = "struct Rgba { r: f32, g: f32 }"))
        assertFailsWith("struct Rgba { r: f32 } fn f() -> void { let c = Rgba(1.0f, 2.0f) }", "has 1 fields")
        assertFailsWith("struct Rgba { r: f32 } fn f() -> void { let c = Rgba(1.0f) let x = c.q }", "has no field 'q'")
    }

    @Test
    fun recursiveStructsAreRejected() {
        assertFailsWith("struct A { b: B } struct B { a: A }", "contains itself")
    }

    @Test
    fun undefinedNamesAreReported() {
        assertFailsWith("fn f() -> void { let x = nope }", "'nope' is not defined")
        assertFailsWith("fn f() -> void { nope() }", "'nope' is not defined")
    }

    @Test
    fun functionOverloadsResolve() {
        assertCompiles(
            """
            fn twice(v: u32) -> u32 { return v * 2u32 }
            fn twice(v: f32) -> f32 { return v * 2.0f }
            fn f() -> void { let a = twice(2u32) let b = twice(2.0f) }
            """.trimIndent(),
        )
    }

    @Test
    fun returnTypeMismatchIsReported() {
        assertFailsWith("fn f() -> u32 { return 1.0f }", "returns 'u32'")
        assertFailsWith("fn f() -> void { return 1u32 }", "returns void but a value was given")
    }

    @Test
    fun everyPathMustReturn() {
        assertFailsWith("fn f(v: u32) -> u32 { if v > 1u32 { return 1u32 } }", "on every path")
        assertCompiles("fn f(v: u32) -> u32 { if v > 1u32 { return 1u32 } else { return 2u32 } }")
        assertCompiles("fn f(v: u32) -> u32 { if v > 1u32 { return 1u32 } return 2u32 }")
        assertCompiles("fn f(v: u32) -> u32 { loop { return 1u32 } }")
        assertFailsWith("fn f(v: u32) -> u32 { loop { break } }", "on every path")
        assertCompiles("kernel fn k(o: global mut []u32) { }")
    }

    @Test
    fun switchOnlyReturnsWhenItIsExhaustive() {
        assertCompiles(
            "fn f(v: u32) -> u32 { switch v { case 0u32 -> { return 1u32 } else -> { return 2u32 } } }",
        )
        assertFailsWith(
            "fn f(v: u32) -> u32 { switch v { case 0u32 -> { return 1u32 } } }",
            "on every path",
        )
    }

    private fun typeOfLet(body: String, inKernel: Boolean = false, extra: String = ""): Type {
        val source = if (inKernel) {
            "$extra $KERNEL_PROLOGUE $body }"
        } else {
            "$extra fn probe() -> void { $body }"
        }
        val harness = Harness(source)
        harness.analyze()
        harness.assertNoErrors()
        val fn = harness.decls.filterIsInstance<FnDef>().first { it.body.stmts.any { s -> s is VarDecl } }
        return fn.body.stmts.filterIsInstance<VarDecl>().last().resolvedType
    }
}
