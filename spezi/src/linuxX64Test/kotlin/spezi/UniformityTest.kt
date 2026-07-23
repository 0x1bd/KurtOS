package spezi

import spezi.backend.target.TargetProfile
import kotlin.test.Test
import kotlin.test.assertTrue

class UniformityTest {

    @Test
    fun kernelParametersAreUniform() {
        assertCompiles(
            "kernel fn k(out: global mut []u32, n: u32) { if n > 4u32 { barrier() } out[0] = n }",
        )
    }

    @Test
    fun workgroupIndexIsUniform() {
        assertCompiles(
            "kernel fn k(out: global mut []u32) { if workgroup_id_x() > 4u32 { barrier() } out[0] = 1 }",
        )
    }

    @Test
    fun branchOnGlobalIdRejectsBarrier() {
        assertFailsWith(
            "kernel fn k(out: global mut []u32) { if global_id_x() > 4u32 { barrier() } }",
            "not reached by every lane",
        )
    }

    @Test
    fun branchOnLaneIdRejectsBarrier() {
        assertFailsWith(
            "kernel fn k(out: global mut []u32) { if lane_id() == 0u32 { barrier() } }",
            "not reached by every lane",
        )
    }

    @Test
    fun divergenceFlowsThroughLocals() {
        assertFailsWith(
            "kernel fn k(out: global mut []u32) { let x = global_id_x() * 3u32 if x > 4u32 { barrier() } }",
            "not reached by every lane",
        )
    }

    @Test
    fun divergenceFlowsThroughAConditionalAssignment() {
        assertFailsWith(
            "kernel fn k(out: global mut []u32) {" +
                " let mut x = 0u32" +
                " if workitem_id_x() > 4u32 { x = 1u32 }" +
                " if x == 1u32 { barrier() } }",
            "not reached by every lane",
        )
    }

    @Test
    fun divergenceFlowsThroughACall() {
        assertFailsWith(
            "fn scale(v: u32) -> u32 { return v * 2u32 }" +
                " kernel fn k(out: global mut []u32) { if scale(global_id_x()) > 4u32 { barrier() } }",
            "not reached by every lane",
        )
    }

    @Test
    fun aUniformCallStaysUniform() {
        assertCompiles(
            "fn scale(v: u32) -> u32 { return v * 2u32 }" +
                " kernel fn k(out: global mut []u32, n: u32) { if scale(n) > 4u32 { barrier() } out[0] = n }",
        )
    }

    @Test
    fun barrierInsideACalleeIsFoundThroughTheCallGraph() {
        assertFailsWith(
            "fn sync() -> void { barrier() }" +
                " fn outer() -> void { sync() }" +
                " kernel fn k(out: global mut []u32) { if global_id_x() > 4u32 { outer() } }",
            "not reached by every lane",
        )
    }

    @Test
    fun aDivergentLoopRejectsBarrier() {
        assertFailsWith(
            "kernel fn k(out: global mut []u32) { for i in 0u32..global_id_x() { barrier() } }",
            "not reached by every lane",
        )
    }

    @Test
    fun aUniformLoopAllowsBarrier() {
        assertCompiles(
            "kernel fn k(out: global mut []u32, n: u32) { for i in 0u32..n { barrier() } out[0] = n }",
        )
    }

    @Test
    fun anEarlyReturnPoisonsTheRestOfTheKernel() {
        assertFailsWith(
            "kernel fn k(out: global mut []u32, n: u32) {" +
                " if global_id_x() >= n { return }" +
                " barrier() }",
            "not reached by every lane",
        )
    }

    @Test
    fun lanesReconvergeAfterALoopThatSomeLanesLeftEarly() {
        assertCompiles(
            "kernel fn k(out: global mut []u32, n: u32) {" +
                " for i in 0u32..n { if global_id_x() > i { break } }" +
                " barrier() }",
        )
    }

    @Test
    fun theBarrierErrorNamesTheBranch() {
        val harness = Harness(
            "kernel fn k(out: global mut []u32) {\n" +
                "    if global_id_x() > 4u32 {\n" +
                "        barrier()\n" +
                "    }\n" +
                "}",
            TargetProfile.amdgcn(),
        )
        harness.analyze()
        val note = harness.diagnostics.mapNotNull { it.note }.joinToString(" ")
        assertTrue(note.contains("line 2"), "expected the note to point at the branch, got '$note'")
    }
}
