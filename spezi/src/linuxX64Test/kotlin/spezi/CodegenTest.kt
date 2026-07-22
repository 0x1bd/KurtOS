package spezi

import spezi.backend.target.TargetProfile
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CodegenTest {

    @Test
    fun x86KernelsArePlainFunctionsWithADispatchPointer() {
        val ir = irOf("$KERNEL_PROLOGUE }")
        assertContains(ir, "target triple = \"x86_64-unknown-none-elf\"")
        assertContains(ir, "define void @k(ptr nocapture readonly %spz.dispatch.arg")
        assertContains(ir, "%spz.dispatch = type { [3 x i32], [3 x i32], [3 x i32] }")
    }

    @Test
    fun amdgcnKernelsUseTheKernelCallingConvention() {
        val ir = irOf("$KERNEL_PROLOGUE }", TargetProfile.amdgcn())
        assertContains(ir, "target triple = \"amdgcn-amd-amdhsa\"")
        assertContains(ir, "define amdgpu_kernel void @k(")
        assertFalse(ir.contains("spz.dispatch"), "GPU kernels must not take a software dispatch pointer")
    }

    @Test
    fun nvptxKernelsUseTheKernelCallingConvention() {
        val ir = irOf("$KERNEL_PROLOGUE }", TargetProfile.nvptx())
        assertContains(ir, "target triple = \"nvptx64-nvidia-cuda\"")
        assertContains(ir, "define ptx_kernel void @k(")
    }

    @Test
    fun slicesExpandToPointerAndLengthAtTheAbi() {
        val ir = irOf("$KERNEL_PROLOGUE }")
        assertContains(ir, "ptr noundef %arg.out, i32 noundef %arg.out.len")
    }

    @Test
    fun readOnlySlicesAreMarkedReadonly() {
        val ir = irOf("kernel fn k(src: global []u32) { }")
        assertContains(ir, "readonly %arg.src")
    }

    @Test
    fun globalPointersLandInAddressSpaceOne() {
        val ir = irOf("$KERNEL_PROLOGUE out[0] = 1u32 }", TargetProfile.amdgcn())
        assertContains(ir, "ptr addrspace(1)")
    }

    @Test
    fun allocasUseTheTargetPrivateAddressSpace() {
        val amd = irOf("$KERNEL_PROLOGUE let mut a: u32 = 1u32 a = 2u32 }", TargetProfile.amdgcn())
        assertContains(amd, "alloca i32, align 4, addrspace(5)")

        val x86 = irOf("$KERNEL_PROLOGUE let mut a: u32 = 1u32 a = 2u32 }")
        assertContains(x86, "alloca i32, align 4\n")
        assertFalse(x86.contains("addrspace(5)"))
    }

    @Test
    fun sharedStorageBecomesAnAddressSpaceThreeGlobal() {
        val ir = irOf(
            "kernel fn k(o: global mut []u32) { let shared mut tile: [64]u32 tile[0] = 1u32 }",
            TargetProfile.amdgcn(),
        )
        assertContains(ir, "addrspace(3) global [64 x i32]")
    }

    @Test
    fun dispatchLowersToAmdgcnIntrinsics() {
        val ir = irOf("$KERNEL_PROLOGUE let g = global_id_x() }", TargetProfile.amdgcn())
        assertContains(ir, "@llvm.amdgcn.workgroup.id.x()")
        assertContains(ir, "@llvm.amdgcn.workitem.id.x()")
        assertContains(ir, "@llvm.amdgcn.dispatch.ptr()")
    }

    @Test
    fun dispatchLowersToNvptxIntrinsics() {
        val ir = irOf("$KERNEL_PROLOGUE let g = global_id_x() }", TargetProfile.nvptx())
        assertContains(ir, "@llvm.nvvm.read.ptx.sreg.ctaid.x()")
        assertContains(ir, "@llvm.nvvm.read.ptx.sreg.ntid.x()")
        assertContains(ir, "@llvm.nvvm.read.ptx.sreg.tid.x()")
    }

    @Test
    fun dispatchLowersToStructLoadsOnCpu() {
        val ir = irOf("$KERNEL_PROLOGUE let g = global_id_x() }")
        assertContains(ir, "getelementptr inbounds %spz.dispatch, ptr %spz.dispatch.arg")
        assertFalse(ir.contains("llvm.amdgcn"))
    }

    @Test
    fun dispatchPointerIsThreadedIntoHelpersThatNeedIt() {
        val ir = irOf(
            """
            fn where_am_i() -> u32 { return global_id_x() }
            fn passthrough() -> u32 { return where_am_i() }
            kernel fn k(out: global mut []u32) { out[0] = passthrough() }
            """.trimIndent(),
        )
        assertContains(ir, "define internal i32 @spz_where_am_i(ptr nocapture readonly %spz.dispatch.arg)")
        assertContains(ir, "define internal i32 @spz_passthrough(ptr nocapture readonly %spz.dispatch.arg)")
        assertContains(ir, "call i32 @spz_where_am_i(ptr %spz.dispatch.arg)")
    }

    @Test
    fun helpersThatDoNotNeedDispatchDoNotGetIt() {
        val ir = irOf("fn twice(v: u32) -> u32 { return v * 2u32 } kernel fn k(o: global mut []u32) { o[0] = twice(2u32) }")
        assertContains(ir, "define internal i32 @spz_twice_u32(i32 %arg.v)")
    }

    @Test
    fun switchLowersToASwitchInstruction() {
        val ir = irOf(
            "fn f(v: u32) -> u32 { switch v { case 0u32 -> { return 1u32 } case 1u32, 2u32 -> { return 2u32 } else -> { return 3u32 } } return 0u32 }",
        )
        assertContains(ir, "switch i32")
        assertContains(ir, "i32 0, label")
        assertTrue(ir.contains("i32 1, label") && ir.contains("i32 2, label"), "multi value case arms")
    }

    @Test
    fun ternaryAndShortCircuitUsePhiNodes() {
        val ternary = irOf("fn f(c: bool, a: u32, b: u32) -> u32 { return c ? a : b }")
        assertContains(ternary, "phi i32")

        val logical = irOf("fn f(a: bool, b: bool) -> bool { return a and b }")
        assertContains(logical, "phi i1")
        assertContains(logical, "[ false,")
    }

    @Test
    fun orShortCircuitsOnTrue() {
        val ir = irOf("fn f(a: bool, b: bool) -> bool { return a or b }")
        assertContains(ir, "[ true,")
    }

    @Test
    fun signedAndUnsignedOperationsDiffer() {
        val unsigned = irOf("fn f(a: u32, b: u32) -> u32 { return a / b }")
        assertContains(unsigned, "udiv")
        val signed = irOf("fn f(a: i32, b: i32) -> i32 { return a / b }")
        assertContains(signed, "sdiv")

        val logical = irOf("fn f(a: u32, b: u32) -> u32 { return a >> b }")
        assertContains(logical, "lshr")
        val arithmetic = irOf("fn f(a: i32, b: i32) -> i32 { return a >> b }")
        assertContains(arithmetic, "ashr")
    }

    @Test
    fun unsignedComparisonsUseUnsignedPredicates() {
        assertContains(irOf("fn f(a: u32, b: u32) -> bool { return a < b }"), "icmp ult")
        assertContains(irOf("fn f(a: i32, b: i32) -> bool { return a < b }"), "icmp slt")
    }

    @Test
    fun boundsChecksTrapWhenEnabled() {
        val checked = irOf("$KERNEL_PROLOGUE out[n] = 1u32 }", boundsChecks = true)
        assertContains(checked, "@llvm.trap()")
        assertContains(checked, "icmp ult i32")

        val unchecked = irOf("$KERNEL_PROLOGUE out[n] = 1u32 }", boundsChecks = false)
        assertFalse(unchecked.contains("llvm.trap"))
    }

    @Test
    fun vectorBuiltinsLowerToIntrinsics() {
        val ir = irOf("fn f(v: f32x4) -> f32x4 { return sqrt(v) }")
        assertContains(ir, "@llvm.sqrt.v4f32")
        assertContains(ir, "<4 x float>")
    }

    @Test
    fun scalarBroadcastUsesShufflevectorWithATypedMask() {
        val ir = irOf("fn f(v: f32x4) -> f32x4 { return v * 2.0f }")
        assertContains(ir, "shufflevector <4 x float>")
        assertContains(ir, "<4 x i32> <i32 0, i32 0, i32 0, i32 0>")
    }

    @Test
    fun swizzleLowersToShuffleOrExtract() {
        val single = irOf("fn f(v: f32x4) -> f32 { return v.x }")
        assertContains(single, "extractelement <4 x float>")

        val multi = irOf("fn f(v: f32x4) -> f32x3 { return v.zyx }")
        assertContains(multi, "<3 x i32> <i32 2, i32 1, i32 0>")
    }

    @Test
    fun dotProductIsAnOrderedChainNotAReduction() {
        val ir = irOf("fn f(a: f32x4, b: f32x4) -> f32 { return dot(a, b) }")
        assertContains(ir, "fmul <4 x float>")
        assertEquals(3, countOccurrences(ir, "fadd float"), "dot must fold lanes in a fixed order")
        assertFalse(ir.contains("vector.reduce"), "reductions would not be bit reproducible")
    }

    @Test
    fun integerAndFloatMinMaxPickDifferentIntrinsics() {
        assertContains(irOf("fn f(a: u32, b: u32) -> u32 { return min(a, b) }"), "@llvm.umin.i32")
        assertContains(irOf("fn f(a: i32, b: i32) -> i32 { return min(a, b) }"), "@llvm.smin.i32")
        assertContains(irOf("fn f(a: f32, b: f32) -> f32 { return min(a, b) }"), "@llvm.minnum.f32")
    }

    @Test
    fun bitBuiltinsLowerToIntrinsics() {
        assertContains(irOf("fn f(a: u32) -> u32 { return popcount(a) }"), "@llvm.ctpop.i32")
        assertContains(irOf("fn f(a: u32) -> u32 { return clz(a) }"), "@llvm.ctlz.i32(i32 %arg.a, i1 false)")
        assertContains(irOf("fn f(a: u32, b: u32) -> u32 { return rotl(a, b) }"), "@llvm.fshl.i32")
    }

    @Test
    fun aViewScalesTheLengthAndKeepsBoundsChecks() {
        val ir = irOf(
            "kernel fn k(a: global mut []u32) { let w = a as global mut []u16 w[0] = 1 }",
            boundsChecks = true,
        )
        assertContains(ir, "mul i32")
        assertContains(ir, "getelementptr inbounds i16")
        assertContains(ir, "@llvm.trap()")
    }

    @Test
    fun memoryScopesLowerToDistinctFences() {
        val workgroup = irOf("kernel fn k(a: global mut []u32) { memfence_workgroup() }", TargetProfile.amdgcn())
        assertContains(workgroup, "fence syncscope(\"workgroup\")")
        val device = irOf("kernel fn k(a: global mut []u32) { memfence_device() }", TargetProfile.amdgcn())
        assertContains(device, "fence syncscope(\"agent\")")
    }

    @Test
    fun bitcastBuiltinsAreFree() {
        val ir = irOf("fn f(a: f32) -> u32 { return f32_bits(a) }")
        assertContains(ir, "bitcast float %arg.a to i32")
    }

    @Test
    fun noFastMathFlagsAreEmitted() {
        val ir = irOf("fn f(a: f32, b: f32) -> f32 { return a * b + a / b }")
        assertFalse(ir.contains("fast"), "float math must stay bit reproducible by default")
        assertFalse(ir.contains("reassoc"))
        assertFalse(ir.contains("nnan"))
    }

    @Test
    fun floatConstantsUseExactHexEncoding() {
        val ir = irOf("fn f() -> f32 { return 0.1f }")
        assertContains(ir, "0x3FB99999A0000000")
    }

    @Test
    fun constantsAreInlined() {
        val ir = irOf("const LIMIT: u32 = 40 fn f() -> u32 { return LIMIT }")
        assertContains(ir, "ret i32 40")
    }

    @Test
    fun gpuFunctionsAreAlwaysInlinedAndAvoidJumpTables() {
        val ir = irOf("fn helper(v: u32) -> u32 { return v } kernel fn k(o: global mut []u32) { o[0] = helper(1u32) }", TargetProfile.amdgcn())
        assertContains(ir, "alwaysinline")
        assertContains(ir, "\"no-jump-tables\"=\"true\"")
    }

    @Test
    fun structsBecomeNamedTypesAndBoolsArePackedInMemory() {
        val ir = irOf("struct Flags { on: bool, level: u32 } fn f() -> u32 { let s = Flags(true, 3u32) return s.level }")
        assertContains(ir, "%struct.Flags = type { i8, i32 }")
    }

    @Test
    fun bufferReinterpretationKeepsTheAddressSpace() {
        val ir = irOf(
            "kernel fn k(rdram: global mut []u32) { let w = rdram as global mut *u16 w[1u32] = 5u16 }",
            TargetProfile.amdgcn(),
        )
        assertContains(ir, "getelementptr inbounds i16, ptr addrspace(1)")
        assertContains(ir, "store i16")
    }

    @Test
    fun everyFunctionEndsWithATerminator() {
        val ir = irOf(
            """
            fn f(v: u32) -> u32 {
                if v > 1u32 { return 1u32 } else { return 2u32 }
            }
            kernel fn k(o: global mut []u32, n: u32) {
                for i in 0u32..n { if i == 3u32 { continue } o[i] = f(i) }
                while n > 0u32 { break }
                loop { break }
            }
            """.trimIndent(),
        )
        for (block in ir.split("\n\n")) {
            if (!block.startsWith("define")) continue
            val lines = block.trimEnd().lines().filter { it.isNotBlank() }
            val last = lines[lines.size - 2].trim()
            assertTrue(
                last.startsWith("ret") || last.startsWith("br") || last.startsWith("unreachable") ||
                    last.startsWith("switch") || last == "]",
                "function does not end in a terminator: $last",
            )
        }
    }

    private fun countOccurrences(haystack: String, needle: String): Int =
        haystack.split(needle).size - 1
}
