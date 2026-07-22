package spezi

import platform.posix.STDERR_FILENO
import platform.posix.isatty
import spezi.backend.target.TargetProfile
import spezi.common.CompilationOptions
import spezi.driver.CompilationResult
import spezi.driver.CompilerDriver
import kotlin.system.exitProcess

private const val USAGE = """spezi - shader language compiler

usage: spezi [options] <input.spz>

options:
  -o <path>            output file (default: derived from the input name)
  --target <name>      x86_64 | amdgcn | nvptx64 | spirv64   (default: x86_64)
  --cpu <name>         target cpu, e.g. gfx902 or sm_60
  -O <0-3>             optimization level (default: 2)
  --emit-ir            stop after writing LLVM IR
  --emit-asm           stop after writing assembly
  -I <dir>             add a module search directory
  --no-bounds-checks   drop slice bounds checks (implied for GPU targets at -O2+)
  --bounds-checks      keep slice bounds checks
  --clang <path>       clang binary to drive (default: clang)
  -v, --verbose        print each stage and its timing
  -h, --help           show this message
"""

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        println(USAGE)
        exitProcess(2)
    }

    var input: String? = null
    var output: String? = null
    var targetName = "x86_64"
    var cpu: String? = null
    var optLevel = 2
    var emitIr = false
    var emitAsm = false
    var boundsChecks: Boolean? = null
    var clang = "clang"
    var verbose = false
    val includes = ArrayList<String>()

    var index = 0
    while (index < args.size) {
        val arg = args[index]
        when {
            arg == "-h" || arg == "--help" -> {
                println(USAGE)
                exitProcess(0)
            }

            arg == "-o" -> output = requireValue(args, ++index, "-o")
            arg == "--target" -> targetName = requireValue(args, ++index, "--target")
            arg == "--cpu" -> cpu = requireValue(args, ++index, "--cpu")
            arg == "--clang" -> clang = requireValue(args, ++index, "--clang")
            arg == "-I" -> includes.add(requireValue(args, ++index, "-I"))
            arg.startsWith("-I") && arg.length > 2 -> includes.add(arg.substring(2))
            arg == "--emit-ir" -> emitIr = true
            arg == "--emit-asm" -> emitAsm = true
            arg == "--no-bounds-checks" -> boundsChecks = false
            arg == "--bounds-checks" -> boundsChecks = true
            arg == "-v" || arg == "--verbose" -> verbose = true
            arg.startsWith("-O") && arg.length == 3 -> optLevel = arg[2].digitToIntOrNull() ?: 2
            arg == "-O" -> optLevel = requireValue(args, ++index, "-O").toIntOrNull() ?: 2
            arg.startsWith("-") -> fail("unknown option '$arg'")
            input != null -> fail("only one input file is supported, got '$arg' after '$input'")
            else -> input = arg
        }
        index++
    }

    if (input == null) fail("no input file")

    val profile = TargetProfile.byName(targetName, cpu)
    if (profile == null) fail("unknown target '$targetName'")

    val stem = input.substringBeforeLast('.')
    val defaultOutput = when {
        emitIr -> "$stem.ll"
        emitAsm -> "$stem.s"
        else -> stem + profile.objectSuffix
    }

    val options = CompilationOptions(
        input = input,
        output = output ?: defaultOutput,
        profile = profile,
        optLevel = optLevel,
        emitIr = emitIr,
        emitAsm = emitAsm,
        boundsChecks = boundsChecks ?: !(profile.isGpu && optLevel >= 2),
        includePaths = includes,
        clang = clang,
        verbose = verbose,
        color = isatty(STDERR_FILENO) == 1,
        extraClangArgs = emptyList(),
    )

    when (val result = CompilerDriver.compile(options)) {
        is CompilationResult.Success -> {
            print(result.report)
            if (verbose) println("spezi: wrote ${options.output} in ${result.elapsedMillis} ms")
        }

        is CompilationResult.Failure -> {
            print(result.report)
            println("spezi: compilation failed with ${result.errorCount} error(s)")
            exitProcess(1)
        }
    }
}

private fun requireValue(args: Array<String>, index: Int, option: String): String {
    if (index >= args.size) fail("'$option' needs a value")
    return args[index]
}

private fun fail(message: String): Nothing {
    println("spezi: $message")
    exitProcess(2)
}
