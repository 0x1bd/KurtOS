package spezi

import spezi.domain.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ParserTest {

    @Test
    fun arithmeticPrecedence() {
        assertEquals("(+ a (* b c))", exprOf("a + b * c"))
        assertEquals("(- (+ a b) c)", exprOf("a + b - c"))
        assertEquals("(* (+ a b) c)", exprOf("(a + b) * c"))
        assertEquals("(+ a (% b c))", exprOf("a + b % c"))
    }

    @Test
    fun bitwiseBindsTighterThanComparison() {
        assertEquals("(!= (& v 1) 0)", exprOf("v & 1 != 0"))
        assertEquals("(== (| a b) c)", exprOf("a | b == c"))
        assertEquals("(< (^ a b) c)", exprOf("a ^ b < c"))
    }

    @Test
    fun bitwisePrecedenceOrder() {
        assertEquals("(| a (^ b (& c d)))", exprOf("a | b ^ c & d"))
    }

    @Test
    fun shiftsBindLooserThanArithmetic() {
        assertEquals("(<< (+ a b) c)", exprOf("a + b << c"))
        assertEquals("(& (<< a b) c)", exprOf("a << b & c"))
    }

    @Test
    fun logicalOperatorsAreLowest() {
        assertEquals("(or (and a b) c)", exprOf("a and b or c"))
        assertEquals("(and (< a b) (> c d))", exprOf("a < b and c > d"))
    }

    @Test
    fun castBindsLooserThanUnary() {
        assertEquals("(as (& x) global mut *u16)", exprOf("&x as global mut *u16"))
        assertEquals("(as (-. x) f32)", exprOf("-x as f32"))
    }

    @Test
    fun castBindsTighterThanBinary() {
        assertEquals("(* (as a u32) b)", exprOf("a as u32 * b"))
    }

    @Test
    fun ternaryIsRightAssociative() {
        assertEquals("(?: a b (?: c d e))", exprOf("a ? b : c ? d : e"))
        assertEquals("(?: (< a b) x y)", exprOf("a < b ? x : y"))
    }

    @Test
    fun postfixChains() {
        assertEquals("(. ([] buf i) field)", exprOf("buf[i].field"))
        assertEquals("([] (. s field) 3)", exprOf("s.field[3]"))
        assertEquals("(* ([] p 2))", exprOf("*p[2]"))
    }

    @Test
    fun methodCallsDesugarToFreeFunctions() {
        assertEquals("(luma color)", exprOf("color.luma()"))
        assertEquals("(blend a b 2)", exprOf("a.blend(b, 2)"))
    }

    @Test
    fun structLiteralsUseCapitalisedNames() {
        assertEquals("(new Rgba 1 2)", exprOf("Rgba(1, 2)"))
        assertEquals("(f32x4 1)", exprOf("f32x4(1)"))
        assertEquals("(u32 x)", exprOf("u32(x)"))
    }

    @Test
    fun elseIfChainsNest() {
        val fn = parseFunction("fn f(v: u32) -> u32 { if v < 1u32 { return 1u32 } else if v < 2u32 { return 2u32 } else { return 3u32 } }")
        val outer = fn.body.stmts.first()
        assertIs<IfStmt>(outer)
        val middle = outer.elseBranch
        assertIs<IfStmt>(middle)
        assertIs<Block>(middle.elseBranch)
    }

    @Test
    fun switchArmsAndDefault() {
        val fn = parseFunction(
            "fn f(v: u32) -> u32 { switch v { case 0u32 -> { return 1u32 } case 1u32, 2u32 -> { return 2u32 } else -> { return 3u32 } } }",
        )
        val switch = fn.body.stmts.first()
        assertIs<SwitchStmt>(switch)
        assertEquals(2, switch.cases.size)
        assertEquals(1, switch.cases[0].values.size)
        assertEquals(2, switch.cases[1].values.size)
        assertTrue(switch.elseBlock != null)
    }

    @Test
    fun switchWithoutDefault() {
        val fn = parseFunction("fn f(v: u32) -> void { switch v { case 0u32 -> { return } } }")
        val switch = fn.body.stmts.first()
        assertIs<SwitchStmt>(switch)
        assertNull(switch.elseBlock)
    }

    @Test
    fun loopForms() {
        val fn = parseFunction(
            "fn f(n: u32) -> void { while n > 0u32 { break } for i in 0u32..n { continue } loop { break } }",
        )
        assertIs<WhileStmt>(fn.body.stmts[0])
        val forStmt = fn.body.stmts[1]
        assertIs<ForStmt>(forStmt)
        assertEquals("i", forStmt.varName)
        assertTrue(!forStmt.inclusive)
        assertIs<LoopStmt>(fn.body.stmts[2])
    }

    @Test
    fun inclusiveRange() {
        val fn = parseFunction("fn f(n: u32) -> void { for i in 0u32..=n { continue } }")
        val forStmt = fn.body.stmts[0]
        assertIs<ForStmt>(forStmt)
        assertTrue(forStmt.inclusive)
    }

    @Test
    fun compoundAssignmentCarriesItsOperator() {
        val fn = parseFunction("fn f() -> void { let mut a: u32 = 0u32 a += 3u32 }")
        val assign = fn.body.stmts[1]
        assertIs<Assign>(assign)
        assertEquals(TokenType.PLUS, assign.op)
    }

    @Test
    fun plainAssignmentHasNoOperator() {
        val fn = parseFunction("fn f() -> void { let mut a: u32 = 0u32 a = 3u32 }")
        val assign = fn.body.stmts[1]
        assertIs<Assign>(assign)
        assertNull(assign.op)
    }

    @Test
    fun typeSyntax() {
        val fn = parseFunction(
            "fn f(a: global mut []u32, b: uniform *Rgba, c: [16]u32, d: f32x4, e: shared []u8) -> void { }",
        )
        val types = fn.params.map { it.type }
        assertEquals(SliceType(Types.U32, AddressSpace.Global, true), types[0])
        assertEquals(PtrType(StructType("Rgba"), AddressSpace.Uniform, false), types[1])
        assertEquals(ArrayType(Types.U32, 16), types[2])
        assertEquals(VectorType(Types.F32, 4), types[3])
        assertEquals(SliceType(Types.U8, AddressSpace.Shared, false), types[4])
    }

    @Test
    fun nestedArrayTypes() {
        val fn = parseFunction("fn f(a: [4][8]u32) -> void { }")
        assertEquals(ArrayType(ArrayType(Types.U32, 8), 4), fn.params[0].type)
    }

    @Test
    fun kernelsAndExternsAndConsts() {
        val harness = Harness(
            """
            const LIMIT: u32 = 8
            extern fn host_log(v: u32) -> void
            struct Rgba { r: f32, g: f32 }
            kernel fn go(out: global mut []u32) { }
            fn helper() -> u32 { return LIMIT }
            """.trimIndent(),
        )
        val decls = harness.parse()
        harness.assertNoErrors()
        assertEquals(1, decls.filterIsInstance<ConstDef>().size)
        assertEquals(1, decls.filterIsInstance<ExternFnDef>().size)
        assertEquals(1, decls.filterIsInstance<StructDef>().size)
        val fns = decls.filterIsInstance<FnDef>()
        assertEquals(2, fns.size)
        assertTrue(fns.first { it.name == "go" }.isKernel)
        assertTrue(!fns.first { it.name == "helper" }.isKernel)
    }

    @Test
    fun errorsRecoverToTheNextDeclaration() {
        val harness = Harness("fn broken( -> void { } fn good() -> u32 { return 1u32 }")
        val decls = harness.parse()
        assertTrue(harness.errors.isNotEmpty())
        assertTrue(decls.filterIsInstance<FnDef>().any { it.name == "good" }, "recovery should reach 'good'")
    }

    private fun parseFunction(source: String): FnDef {
        val harness = Harness(source)
        val decls = harness.parse()
        harness.assertNoErrors()
        return decls.filterIsInstance<FnDef>().first()
    }
}
