package spezi

import spezi.backend.target.TargetProfile
import spezi.domain.FnDef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BoundsTest {

    @Test
    fun aBoundsClauseCompiles() {
        assertCompiles("kernel fn k(out: global mut []u32, n: u32) bounds n { out[global_id_x()] = 1 }")
    }

    @Test
    fun theBoundIsRemembered() {
        val harness = Harness("kernel fn k(out: global mut []u32, n: u32) bounds n { out[0] = 1 }")
        harness.analyze()
        harness.assertNoErrors()
        val fn = harness.decls.filterIsInstance<FnDef>().first()
        assertEquals("n", render(fn.bounds!!))
    }

    @Test
    fun theBoundBecomesAGuardOnTheWorkItemIndex() {
        val ir = irOf("kernel fn k(out: global mut []u32, n: u32) bounds n { out[0] = 7u32 }")
        assertTrue(ir.contains("icmp uge"), ir)
    }

    @Test
    fun theGuardRunsBeforeTheBody() {
        val ir = irOf(
            "kernel fn k(out: global mut []u32, n: u32) bounds n { out[0] = 7u32 }",
            TargetProfile.amdgcn(),
        )
        val compare = ir.indexOf("icmp uge")
        val store = ir.indexOf("store i32 7")
        assertTrue(compare in 0 until store, "expected the bound check before the first store\n$ir")
    }

    @Test
    fun anExpressionBoundWorks() {
        assertCompiles(
            "kernel fn k(out: global mut []u32, w: u32, h: u32) bounds w * h { out[global_id_x()] = 1 }",
        )
    }

    @Test
    fun aBoundHasToBeAnIndex() {
        assertFailsWith(
            "kernel fn k(out: global mut []u32, n: i32) bounds n { out[0] = 1 }",
            "u32",
        )
    }

    @Test
    fun aPlainFunctionCannotBeBounded() {
        assertFailsWith("fn f(n: u32) -> void { } fn g(n: u32) bounds n -> void { }", "only allowed on a kernel")
    }

    @Test
    fun theGuardMakesLaterBarriersDivergent() {
        assertFailsWith(
            "kernel fn k(out: global mut []u32, n: u32) bounds n { barrier() }",
            "not reached by every lane",
        )
    }

    @Test
    fun boundsIsStillUsableAsNothingElse() {
        assertFailsWith("fn f() -> void { let bounds = 1u32 }", "expected a variable name")
    }
}
