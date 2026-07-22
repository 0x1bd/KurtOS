package spezi

import spezi.backend.llvm.LlvmBackend
import spezi.backend.target.TargetProfile
import spezi.common.CompilationOptions
import spezi.common.Context
import spezi.common.Diagnostic
import spezi.common.Level
import spezi.common.SourceFile
import spezi.domain.AstNode
import spezi.domain.Program
import spezi.domain.Token
import spezi.domain.TokenType
import spezi.frontend.Lexer
import spezi.frontend.Parser
import spezi.frontend.semantic.SemanticAnalyzer
import spezi.frontend.semantic.SemanticModel
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

fun optionsFor(profile: TargetProfile = TargetProfile.x86(), boundsChecks: Boolean = false) = CompilationOptions(
    input = "<test>",
    output = "<test>.o",
    profile = profile,
    optLevel = 0,
    emitIr = true,
    emitAsm = false,
    boundsChecks = boundsChecks,
    includePaths = emptyList(),
    clang = "clang",
    verbose = false,
    color = false,
    extraClangArgs = emptyList(),
)

class Harness(val source: String, profile: TargetProfile = TargetProfile.x86(), boundsChecks: Boolean = false) {

    val ctx = Context(optionsFor(profile, boundsChecks))
    private val file = SourceFile("<test>", source)

    val diagnostics: List<Diagnostic> get() = ctx.reporter.diagnostics
    val errors: List<String> get() = diagnostics.filter { it.level == Level.ERROR }.map { it.message }
    val warnings: List<String> get() = diagnostics.filter { it.level == Level.WARN }.map { it.message }

    fun tokens(): List<Token> = Lexer(ctx, file).tokenize()

    fun parse(): List<AstNode> {
        val decls = ArrayList<AstNode>()
        Parser(ctx, file, "").parseInto(decls)
        return decls
    }

    var decls: List<AstNode> = emptyList()
        private set

    fun analyze(): SemanticModel {
        decls = parse()
        val program = Program(decls, Token(TokenType.EOF, "", file, 1, 1, 1))
        return SemanticAnalyzer(ctx, program).analyze()
    }

    fun ir(): String {
        val model = analyze()
        assertNoErrors()
        return LlvmBackend(ctx, model, ctx.options.profile, ctx.options.boundsChecks).emit()
    }

    fun assertNoErrors() {
        if (errors.isNotEmpty()) fail("unexpected errors: ${errors.joinToString("; ")}")
    }
}

fun tokensOf(source: String): List<TokenType> =
    Harness(source).tokens().map { it.type }.dropLast(1)

fun lexOne(source: String): Token {
    val harness = Harness(source)
    val tokens = harness.tokens()
    harness.assertNoErrors()
    assertEquals(2, tokens.size, "expected exactly one token in '$source'")
    return tokens[0]
}

fun errorsOf(source: String): List<String> {
    val harness = Harness(source)
    harness.analyze()
    return harness.errors
}

fun irOf(source: String, profile: TargetProfile = TargetProfile.x86(), boundsChecks: Boolean = false): String =
    Harness(source, profile, boundsChecks).ir()

fun assertFailsWith(source: String, fragment: String) {
    val errors = errorsOf(source)
    assertTrue(
        errors.any { it.contains(fragment) },
        "expected an error containing '$fragment', got ${if (errors.isEmpty()) "no errors" else errors.toString()}",
    )
}

fun assertCompiles(source: String) {
    val harness = Harness(source)
    harness.analyze()
    harness.assertNoErrors()
}

const val KERNEL_PROLOGUE = "kernel fn k(out: global mut []u32, n: u32) {"

fun render(node: AstNode): String = when (node) {
    is spezi.domain.LiteralInt -> node.value.toString()
    is spezi.domain.LiteralFloat -> node.value.toString()
    is spezi.domain.LiteralBool -> node.value.toString()
    is spezi.domain.VarRef -> node.name
    is spezi.domain.BinOp -> "(${node.op.text} ${render(node.left)} ${render(node.right)})"
    is spezi.domain.LogicalOp -> "(${node.op.text} ${render(node.left)} ${render(node.right)})"
    is spezi.domain.UnaryOp -> "(${node.op.text}. ${render(node.operand)})"
    is spezi.domain.Ternary -> "(?: ${render(node.cond)} ${render(node.ifTrue)} ${render(node.ifFalse)})"
    is spezi.domain.CastExpr -> "(as ${render(node.expr)} ${node.targetType.name})"
    is spezi.domain.Call -> "(${node.name}${node.args.joinToString("") { " " + render(it) }})"
    is spezi.domain.StructLit -> "(new ${node.typeName}${node.args.joinToString("") { " " + render(it) }})"
    is spezi.domain.Access -> "(. ${render(node.target)} ${node.member})"
    is spezi.domain.Index -> "([] ${render(node.target)} ${render(node.index)})"
    is spezi.domain.AddrOf -> "(& ${render(node.operand)})"
    is spezi.domain.Deref -> "(* ${render(node.operand)})"
    else -> node::class.simpleName ?: "?"
}

fun exprOf(source: String): String {
    val harness = Harness("fn f() -> void { let probe = $source }")
    val decls = harness.parse()
    harness.assertNoErrors()
    val fn = decls.filterIsInstance<spezi.domain.FnDef>().first()
    val decl = fn.body.stmts.first() as spezi.domain.VarDecl
    return render(decl.init!!)
}
