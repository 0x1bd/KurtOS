package spezi.frontend.semantic

import spezi.common.Context
import spezi.common.Level
import spezi.domain.*

class UniformityModel(
    val divergentExprs: Set<Expr>,
    val divergentParams: Map<FnDef, BooleanArray>,
    val divergentReturns: Set<FnDef>,
) {

    fun isDivergent(expr: Expr): Boolean = divergentExprs.contains(expr)
}

class Uniformity(private val ctx: Context, private val functions: List<FnDef>) {

    private val divergentExprs = HashSet<Expr>()
    private val divergentParams = HashMap<FnDef, BooleanArray>()
    private val divergentReturns = HashSet<FnDef>()
    private val divergentLocals = HashSet<VarDecl>()
    private val divergentInductions = HashSet<ForStmt>()
    private val syncing = HashSet<FnDef>()

    private var changed = false
    private var reporting = false

    private var currentFn: FnDef? = null
    private var branchDepth = 0
    private var laneExited = false
    private var loopEscaped = false
    private val origins = ArrayList<Token>()

    fun analyze(): UniformityModel {
        for (fn in functions) divergentParams[fn] = BooleanArray(fn.params.size)
        findSyncing()

        var rounds = 0
        do {
            changed = false
            for (fn in functions) walkFn(fn)
            rounds++
        } while (changed && rounds < ROUND_LIMIT)

        reporting = true
        for (fn in functions) walkFn(fn)

        return UniformityModel(divergentExprs, divergentParams, divergentReturns)
    }

    private fun findSyncing() {
        for (fn in functions) if (hasSync(fn.body)) syncing.add(fn)
        var again = true
        while (again) {
            again = false
            for (fn in functions) {
                if (fn in syncing) continue
                if (callsSyncing(fn.body)) {
                    syncing.add(fn)
                    again = true
                }
            }
        }
    }

    private fun hasSync(node: Stmt): Boolean = anyExpr(node) { expr ->
        expr is Call && (expr.target as? CallTarget.Builtin)?.builtin == Builtin.Barrier
    }

    private fun callsSyncing(node: Stmt): Boolean = anyExpr(node) { expr ->
        expr is Call && ((expr.target as? CallTarget.User)?.fn?.let { it in syncing } ?: false)
    }

    private fun anyExpr(stmt: Stmt, predicate: (Expr) -> Boolean): Boolean = when (stmt) {
        is Block -> stmt.stmts.any { anyExpr(it, predicate) }
        is VarDecl -> stmt.init?.let { anyExpr(it, predicate) } ?: false
        is Assign -> anyExpr(stmt.target, predicate) || anyExpr(stmt.value, predicate)
        is IfStmt -> anyExpr(stmt.cond, predicate) || anyExpr(stmt.thenBlock, predicate) ||
            (stmt.elseBranch?.let { anyExpr(it, predicate) } ?: false)

        is WhileStmt -> anyExpr(stmt.cond, predicate) || anyExpr(stmt.body, predicate)
        is ForStmt -> anyExpr(stmt.start, predicate) || anyExpr(stmt.end, predicate) ||
            anyExpr(stmt.body, predicate)

        is LoopStmt -> anyExpr(stmt.body, predicate)
        is SwitchStmt -> anyExpr(stmt.subject, predicate) ||
            stmt.cases.any { case -> case.values.any { anyExpr(it, predicate) } || anyExpr(case.body, predicate) } ||
            (stmt.elseBlock?.let { anyExpr(it, predicate) } ?: false)

        is ReturnStmt -> stmt.value?.let { anyExpr(it, predicate) } ?: false
        is Expr -> predicate(stmt) || children(stmt).any { anyExpr(it, predicate) }
        else -> false
    }

    private fun children(expr: Expr): List<Expr> = when (expr) {
        is BinOp -> listOf(expr.left, expr.right)
        is LogicalOp -> listOf(expr.left, expr.right)
        is UnaryOp -> listOf(expr.operand)
        is Ternary -> listOf(expr.cond, expr.ifTrue, expr.ifFalse)
        is CastExpr -> listOf(expr.expr)
        is Call -> expr.args
        is StructLit -> expr.args
        is Access -> listOf(expr.target)
        is Index -> listOf(expr.target, expr.index)
        is AddrOf -> listOf(expr.operand)
        is Deref -> listOf(expr.operand)
        else -> emptyList()
    }

    private fun walkFn(fn: FnDef) {
        currentFn = fn
        branchDepth = 0
        laneExited = false
        loopEscaped = false
        origins.clear()
        walkStmt(fn.body)
        currentFn = null
    }

    private fun diverged(): Boolean = branchDepth > 0 || laneExited || loopEscaped

    private fun enter(divergent: Boolean, origin: Token?) {
        if (!divergent) return
        branchDepth++
        if (origin != null) origins.add(origin)
    }

    private fun leave(divergent: Boolean, origin: Token?) {
        if (!divergent) return
        branchDepth--
        if (origin != null) origins.removeAt(origins.size - 1)
    }

    private fun walkStmt(stmt: Stmt) {
        when (stmt) {
            is Block -> for (inner in stmt.stmts) walkStmt(inner)

            is VarDecl -> {
                val value = stmt.init?.let { walkExpr(it) } ?: false
                if (value || diverged()) markLocal(stmt)
            }

            is Assign -> {
                val value = walkExpr(stmt.value)
                val target = walkExpr(stmt.target)
                if (value || target || diverged()) markTarget(stmt.target)
            }

            is IfStmt -> walkIf(stmt)

            is WhileStmt -> {
                val cond = walkExpr(stmt.cond)
                walkLoop(cond, originOf(stmt.cond)) { walkStmt(stmt.body) }
            }

            is ForStmt -> {
                val range = walkExpr(stmt.start) or walkExpr(stmt.end)
                if (range) divergentInductions.add(stmt)
                walkLoop(range, originOf(stmt.end)) { walkStmt(stmt.body) }
            }

            is LoopStmt -> walkLoop(false, null) { walkStmt(stmt.body) }

            is SwitchStmt -> {
                val subject = walkExpr(stmt.subject)
                val origin = originOf(stmt.subject)
                enter(subject, origin)
                for (case in stmt.cases) walkStmt(case.body)
                stmt.elseBlock?.let { walkStmt(it) }
                leave(subject, origin)
            }

            is BreakStmt, is ContinueStmt -> if (diverged()) loopEscaped = true

            is ReturnStmt -> {
                val value = stmt.value?.let { walkExpr(it) } ?: false
                if (value) markReturn()
                if (diverged()) laneExited = true
            }

            is Expr -> walkExpr(stmt)
        }
    }

    private fun walkIf(stmt: IfStmt) {
        val cond = walkExpr(stmt.cond)
        val origin = originOf(stmt.cond)
        enter(cond, origin)
        walkStmt(stmt.thenBlock)
        stmt.elseBranch?.let { walkStmt(it) }
        leave(cond, origin)
    }

    private fun walkLoop(divergent: Boolean, origin: Token?, body: () -> Unit) {
        val saved = loopEscaped
        loopEscaped = false
        enter(divergent, origin)
        body()
        leave(divergent, origin)
        loopEscaped = saved
    }

    private fun markLocal(decl: VarDecl) {
        if (divergentLocals.add(decl)) changed = true
    }

    private fun markReturn() {
        val fn = currentFn ?: return
        if (divergentReturns.add(fn)) changed = true
    }

    private fun markTarget(target: Expr) {
        var node = target
        while (true) {
            when (node) {
                is VarRef -> {
                    val binding = node.binding
                    if (binding is Binding.Local) markLocal(binding.decl)
                    return
                }

                is Access -> node = node.target
                is Index -> node = node.target
                else -> return
            }
        }
    }

    private fun walkExpr(expr: Expr): Boolean {
        val divergent = computeExpr(expr)
        if (divergent) divergentExprs.add(expr)
        return divergent
    }

    private fun computeExpr(expr: Expr): Boolean = when (expr) {
        is LiteralInt, is LiteralFloat, is LiteralBool -> false

        is VarRef -> when (val binding = expr.binding) {
            is Binding.Local -> binding.decl in divergentLocals
            is Binding.Parameter -> currentFn?.let { divergentParams[it]?.getOrNull(binding.index) } ?: false
            is Binding.Induction -> binding.stmt in divergentInductions
            is Binding.Constant -> false
            null -> false
        }

        is BinOp -> walkExpr(expr.left) or walkExpr(expr.right)
        is LogicalOp -> walkExpr(expr.left) or walkExpr(expr.right)
        is UnaryOp -> walkExpr(expr.operand)
        is Ternary -> walkExpr(expr.cond) or walkExpr(expr.ifTrue) or walkExpr(expr.ifFalse)
        is CastExpr -> walkExpr(expr.expr)
        is StructLit -> expr.args.fold(false) { acc, arg -> walkExpr(arg) or acc }
        is Access -> walkExpr(expr.target)
        is Index -> walkExpr(expr.target) or walkExpr(expr.index)
        is AddrOf -> walkExpr(expr.operand)
        is Deref -> walkExpr(expr.operand)
        is Call -> computeCall(expr)
    }

    private fun computeCall(call: Call): Boolean {
        val args = BooleanArray(call.args.size)
        var any = false
        for ((index, arg) in call.args.withIndex()) {
            args[index] = walkExpr(arg)
            any = any or args[index]
        }

        return when (val target = call.target) {
            is CallTarget.Builtin -> {
                if (target.builtin == Builtin.Barrier && reporting && diverged()) reportBarrier(call.loc, null)
                if (target.builtin in LANE_VARYING) true else any
            }

            is CallTarget.User -> {
                val fn = target.fn
                val params = divergentParams[fn]
                if (params != null) {
                    for (index in args.indices) {
                        if (index < params.size && args[index] && !params[index]) {
                            params[index] = true
                            changed = true
                        }
                    }
                }
                if (reporting && fn in syncing && diverged()) reportBarrier(call.loc, fn.name)
                fn in divergentReturns
            }

            else -> any
        }
    }

    private fun reportBarrier(loc: Token, calleeName: String?) {
        val what = if (calleeName == null) "'barrier()'" else "the 'barrier()' inside '$calleeName'"
        val where = origins.lastOrNull()
        val note = if (where == null) {
            "a lane that leaves early never reaches the barrier, so the lanes still waiting never wake up"
        } else {
            "this is under a branch on line ${where.line} that depends on the work item index, " +
                "so only some lanes reach the barrier and the rest wait forever"
        }
        ctx.report(Level.ERROR, "$what is not reached by every lane in the workgroup", loc, note)
    }

    private fun originOf(expr: Expr): Token? {
        if (expr is Call) {
            val builtin = (expr.target as? CallTarget.Builtin)?.builtin
            if (builtin != null && builtin in LANE_VARYING) return expr.loc
        }
        for (child in children(expr)) {
            val found = originOf(child)
            if (found != null) return found
        }
        if (expr is VarRef && divergentExprs.contains(expr)) return expr.loc
        return null
    }

    private companion object {

        const val ROUND_LIMIT = 64

        val LANE_VARYING = setOf(
            Builtin.GlobalIdX, Builtin.GlobalIdY, Builtin.GlobalIdZ,
            Builtin.WorkitemIdX, Builtin.WorkitemIdY, Builtin.WorkitemIdZ,
            Builtin.LaneId,
        )
    }
}
