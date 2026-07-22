package spezi.backend.target

import spezi.domain.AddressSpace

enum class TargetKind(val id: String) {
    X86_64("x86_64"),
    AmdGcn("amdgcn"),
    Nvptx64("nvptx64"),
    SpirV64("spirv64"),
}

class TargetProfile(
    val kind: TargetKind,
    val triple: String,
    val cpu: String,
    val globalSpace: Int,
    val sharedSpace: Int,
    val uniformSpace: Int,
    val allocaSpace: Int,
    val kernelCallingConv: String?,
    val hardwareDispatch: Boolean,
    val objectSuffix: String,
) {

    val isGpu: Boolean get() = kind != TargetKind.X86_64

    fun spaceOf(space: AddressSpace): Int = when (space) {
        AddressSpace.Private -> allocaSpace
        AddressSpace.Global -> globalSpace
        AddressSpace.Shared -> sharedSpace
        AddressSpace.Uniform -> uniformSpace
    }

    fun clangArgs(): List<String> = when (kind) {
        TargetKind.X86_64 -> listOf("--target=$triple")
        TargetKind.AmdGcn -> listOf("--target=$triple", "-mcpu=$cpu", "-nogpulib", "-ffreestanding")
        TargetKind.Nvptx64 -> listOf("--target=$triple", "-march=$cpu")
        TargetKind.SpirV64 -> listOf("--target=$triple")
    }

    companion object {

        fun x86(cpu: String = "x86-64"): TargetProfile = TargetProfile(
            kind = TargetKind.X86_64,
            triple = "x86_64-unknown-none-elf",
            cpu = cpu,
            globalSpace = 0,
            sharedSpace = 0,
            uniformSpace = 0,
            allocaSpace = 0,
            kernelCallingConv = null,
            hardwareDispatch = false,
            objectSuffix = ".o",
        )

        fun amdgcn(cpu: String = "gfx902"): TargetProfile = TargetProfile(
            kind = TargetKind.AmdGcn,
            triple = "amdgcn-amd-amdhsa",
            cpu = cpu,
            globalSpace = 1,
            sharedSpace = 3,
            uniformSpace = 4,
            allocaSpace = 5,
            kernelCallingConv = "amdgpu_kernel",
            hardwareDispatch = true,
            objectSuffix = ".hsaco",
        )

        fun nvptx(cpu: String = "sm_60"): TargetProfile = TargetProfile(
            kind = TargetKind.Nvptx64,
            triple = "nvptx64-nvidia-cuda",
            cpu = cpu,
            globalSpace = 1,
            sharedSpace = 3,
            uniformSpace = 4,
            allocaSpace = 0,
            kernelCallingConv = "ptx_kernel",
            hardwareDispatch = true,
            objectSuffix = ".ptx",
        )

        fun spirv(cpu: String = ""): TargetProfile = TargetProfile(
            kind = TargetKind.SpirV64,
            triple = "spirv64-unknown-unknown",
            cpu = cpu,
            globalSpace = 1,
            sharedSpace = 3,
            uniformSpace = 2,
            allocaSpace = 0,
            kernelCallingConv = "spir_kernel",
            hardwareDispatch = true,
            objectSuffix = ".spv",
        )

        fun byName(name: String, cpu: String?): TargetProfile? = when (name) {
            "x86_64", "x86-64", "cpu" -> if (cpu == null) x86() else x86(cpu)
            "amdgcn", "amd", "gpu" -> if (cpu == null) amdgcn() else amdgcn(cpu)
            "nvptx64", "nvptx", "nvidia" -> if (cpu == null) nvptx() else nvptx(cpu)
            "spirv64", "spirv", "intel" -> if (cpu == null) spirv() else spirv(cpu)
            else -> null
        }
    }
}
