package spezi.domain

sealed interface AstNode {

    val loc: Token
}

class Param(val name: String, val type: Type, val loc: Token)

class Field(val name: String, val type: Type, val loc: Token)

class Program(val decls: List<AstNode>, override val loc: Token) : AstNode

class StructDef(
    val module: String,
    val name: String,
    val fields: List<Field>,
    override val loc: Token,
) : AstNode

class ConstDef(
    val module: String,
    val name: String,
    val declaredType: Type?,
    val value: Expr,
    override val loc: Token,
) : AstNode {

    var resolvedType: Type = UnknownType
    var evaluated: ConstValue? = null
}

class ConstValue(val asLong: Long, val asDouble: Double)

class FnDef(
    val module: String,
    val name: String,
    val isKernel: Boolean,
    val params: List<Param>,
    val retType: Type,
    val body: Block,
    override val loc: Token,
) : AstNode {

    var mangledName: String = name
    var usesShared: Boolean = false
}

class ExternFnDef(
    val name: String,
    val params: List<Param>,
    val retType: Type,
    override val loc: Token,
) : AstNode

sealed interface Stmt : AstNode

class Block(val stmts: List<Stmt>, override val loc: Token) : Stmt

class VarDecl(
    val name: String,
    val declaredType: Type?,
    val isMut: Boolean,
    val space: AddressSpace,
    val init: Expr?,
    override val loc: Token,
) : Stmt {

    var resolvedType: Type = UnknownType
    var addressTaken: Boolean = false
    var globalName: String? = null
}

class Assign(
    val target: Expr,
    val op: TokenType?,
    val value: Expr,
    override val loc: Token,
) : Stmt

class IfStmt(
    val cond: Expr,
    val thenBlock: Block,
    val elseBranch: Stmt?,
    override val loc: Token,
) : Stmt

class WhileStmt(val cond: Expr, val body: Block, override val loc: Token) : Stmt

class ForStmt(
    val varName: String,
    val start: Expr,
    val end: Expr,
    val inclusive: Boolean,
    val body: Block,
    override val loc: Token,
) : Stmt {

    var inductionType: Type = UnknownType
}

class LoopStmt(val body: Block, override val loc: Token) : Stmt

class SwitchCase(val values: List<Expr>, val body: Block, val loc: Token) {

    var constants: LongArray = LongArray(0)
}

class SwitchStmt(
    val subject: Expr,
    val cases: List<SwitchCase>,
    val elseBlock: Block?,
    override val loc: Token,
) : Stmt

class BreakStmt(override val loc: Token) : Stmt

class ContinueStmt(override val loc: Token) : Stmt

class ReturnStmt(val value: Expr?, override val loc: Token) : Stmt

sealed interface Expr : Stmt {

    var resolvedType: Type
}

class LiteralInt(val value: Long, val suffix: Type?, override val loc: Token) : Expr {

    override var resolvedType: Type = suffix ?: UnknownType
}

class LiteralFloat(val value: Double, val suffix: Type?, override val loc: Token) : Expr {

    override var resolvedType: Type = suffix ?: UnknownType
}

class LiteralBool(val value: Boolean, override val loc: Token) : Expr {

    override var resolvedType: Type = BoolType
}

class VarRef(val name: String, override val loc: Token) : Expr {

    override var resolvedType: Type = UnknownType
    var binding: Binding? = null
}

class BinOp(val left: Expr, val op: TokenType, val right: Expr, override val loc: Token) : Expr {

    override var resolvedType: Type = UnknownType
}

class LogicalOp(val left: Expr, val op: TokenType, val right: Expr, override val loc: Token) : Expr {

    override var resolvedType: Type = BoolType
}

class UnaryOp(val op: TokenType, val operand: Expr, override val loc: Token) : Expr {

    override var resolvedType: Type = UnknownType
}

class Ternary(val cond: Expr, val ifTrue: Expr, val ifFalse: Expr, override val loc: Token) : Expr {

    override var resolvedType: Type = UnknownType
}

class CastExpr(val expr: Expr, val targetType: Type, override val loc: Token) : Expr {

    override var resolvedType: Type = targetType
}

class Call(val name: String, val args: MutableList<Expr>, override val loc: Token) : Expr {

    override var resolvedType: Type = UnknownType
    var target: CallTarget = CallTarget.Unresolved
}

class StructLit(val typeName: String, val args: List<Expr>, override val loc: Token) : Expr {

    override var resolvedType: Type = UnknownType
}

class Access(val target: Expr, val member: String, override val loc: Token) : Expr {

    override var resolvedType: Type = UnknownType
    var kind: AccessKind = AccessKind.Unresolved
    var fieldIndex: Int = -1
    var swizzle: IntArray = IntArray(0)
}

class Index(val target: Expr, val index: Expr, override val loc: Token) : Expr {

    override var resolvedType: Type = UnknownType
}

class AddrOf(val operand: Expr, override val loc: Token) : Expr {

    override var resolvedType: Type = UnknownType
}

class Deref(val operand: Expr, override val loc: Token) : Expr {

    override var resolvedType: Type = UnknownType
}

enum class AccessKind {
    Unresolved,
    Field,
    Swizzle,
    SliceLength,
}

sealed interface CallTarget {

    data object Unresolved : CallTarget

    class User(val fn: FnDef) : CallTarget

    class External(val fn: ExternFnDef) : CallTarget

    class Builtin(val builtin: spezi.frontend.semantic.Builtin, val overload: Int) : CallTarget

    class VectorSplat(val type: VectorType) : CallTarget

    class VectorBuild(val type: VectorType) : CallTarget

    class ScalarConvert(val type: Scalar) : CallTarget
}

sealed interface Binding {

    val type: Type

    class Local(val decl: VarDecl) : Binding {

        override val type: Type get() = decl.resolvedType
    }

    class Parameter(val param: Param, val index: Int) : Binding {

        override val type: Type get() = param.type
    }

    class Induction(val stmt: ForStmt) : Binding {

        override val type: Type get() = stmt.inductionType
    }

    class Constant(val def: ConstDef) : Binding {

        override val type: Type get() = def.resolvedType
    }
}
