package spezi.driver

import platform.posix.remove
import platform.posix.system
import spezi.backend.llvm.LlvmBackend
import spezi.common.CompilationOptions
import spezi.common.CompilationState
import spezi.common.Context
import spezi.common.Files
import spezi.common.Level
import spezi.common.SourceFile
import spezi.domain.AstNode
import spezi.domain.Program
import spezi.domain.Token
import spezi.domain.TokenType
import spezi.frontend.Parser
import spezi.frontend.semantic.SemanticAnalyzer
import kotlin.time.TimeSource

sealed class CompilationResult {

    abstract val report: String

    class Success(override val report: String, val elapsedMillis: Long) : CompilationResult()

    class Failure(override val report: String, val errorCount: Int) : CompilationResult()
}

object CompilerDriver {

    fun compile(options: CompilationOptions): CompilationResult {
        val ctx = Context(options)
        val started = TimeSource.Monotonic.markNow()

        val source = SourceFile.load(options.input)
        if (source == null) {
            ctx.report(Level.ERROR, "cannot read '${options.input}'")
            return failure(ctx)
        }
        ctx.currentSource = source
        ctx.markLoaded(options.input)

        ctx.state = CompilationState.Parsing
        val decls = ArrayList<AstNode>()
        Parser(ctx, source, "").parseInto(decls)
        if (ctx.reporter.hasErrors) return failure(ctx)

        val program = Program(decls, Token(TokenType.EOF, "", source, 1, 1, 1))

        ctx.state = CompilationState.SemanticAnalysis
        val model = SemanticAnalyzer(ctx, program).analyze()
        if (ctx.reporter.hasErrors) return failure(ctx)

        ctx.state = CompilationState.Codegen
        val ir = LlvmBackend(ctx, model, options.profile, options.boundsChecks).emit()
        if (ctx.reporter.hasErrors) return failure(ctx)

        if (options.emitIr) {
            if (!Files.write(options.output, ir)) {
                ctx.report(Level.ERROR, "cannot write '${options.output}'")
                return failure(ctx)
            }
            return success(ctx, started.elapsedNow().inWholeMilliseconds)
        }

        val irPath = options.output + ".ll"
        if (!Files.write(irPath, ir)) {
            ctx.report(Level.ERROR, "cannot write '$irPath'")
            return failure(ctx)
        }

        ctx.state = CompilationState.Assembling
        val command = buildClangCommand(options, irPath)
        if (options.verbose) println("spezi: $command")
        val exit = system(command)
        if (exit != 0) {
            ctx.report(Level.ERROR, "clang failed with exit code ${exit shr 8}", null, command)
            ctx.report(Level.INFO, "the generated LLVM IR was left at '$irPath'")
            return failure(ctx)
        }
        remove(irPath)

        return success(ctx, started.elapsedNow().inWholeMilliseconds)
    }

    private fun buildClangCommand(options: CompilationOptions, irPath: String): String {
        val parts = ArrayList<String>()
        parts.add(options.clang)
        parts.add("-x")
        parts.add("ir")
        parts.addAll(options.profile.clangArgs())
        parts.add("-O${options.optLevel}")
        parts.add(if (options.emitAsm) "-S" else "-c")
        parts.addAll(options.extraClangArgs)
        parts.add(irPath)
        parts.add("-o")
        parts.add(options.output)
        return parts.joinToString(" ") { quote(it) }
    }

    private fun quote(value: String): String =
        if (value.all { it.isLetterOrDigit() || it in "-_./=+:," }) value
        else "'" + value.replace("'", "'\\''") + "'"

    private fun success(ctx: Context, elapsed: Long) =
        CompilationResult.Success(ctx.reporter.render(), elapsed)

    private fun failure(ctx: Context) =
        CompilationResult.Failure(ctx.reporter.render(), ctx.reporter.errorCount)
}
