package spezi.frontend.semantic

import spezi.common.Context
import spezi.common.Level
import spezi.domain.*

class SemanticAnalyzer(private val ctx: Context, private val program: Program) {

    private val structs = LinkedHashMap<String, StructDef>()
    private val structOrder = ArrayList<StructDef>()
    private val functions = ArrayList<FnDef>()
    private val functionsByName = HashMap<String, MutableList<FnDef>>()
    private val externals = ArrayList<ExternFnDef>()
    private val externalsByName = HashMap<String, ExternFnDef>()
    private val constants = LinkedHashMap<String, ConstDef>()
    private val sharedGlobals = ArrayList<VarDecl>()

    private val scopes = ArrayList<HashMap<String, Binding>>()
    private var currentFn: FnDef? = null
    private var loopDepth = 0
    private var sharedCounter = 0

    private lateinit var layout: Layout

    fun analyze(): SemanticModel {
        collect()
        layout = Layout(structs)

        for (struct in structOrder) checkStruct(struct)
        for (constant in constants.values) checkConst(constant)
        for (fn in functions) checkSignature(fn)
        for (fn in functions) checkBody(fn)

        val uniformity = if (ctx.reporter.hasErrors) null else Uniformity(ctx, functions).analyze()

        return SemanticModel(
            program,
            structs,
            structOrder,
            functions,
            externals,
            constants.values.toList(),
            sharedGlobals,
            layout,
            uniformity,
        )
    }

    private fun collect() {
        for (decl in program.decls) {
            when (decl) {
                is StructDef -> {
                    if (structs.containsKey(decl.name)) {
                        error("struct '${decl.name}' is already defined", decl.loc)
                    } else {
                        structs[decl.name] = decl
                        structOrder.add(decl)
                    }
                }

                is ConstDef -> {
                    if (constants.containsKey(decl.name)) {
                        error("constant '${decl.name}' is already defined", decl.loc)
                    } else {
                        constants[decl.name] = decl
                    }
                }

                is FnDef -> {
                    functions.add(decl)
                    functionsByName.getOrPut(decl.name) { ArrayList() }.add(decl)
                }

                is ExternFnDef -> {
                    externals.add(decl)
                    externalsByName[decl.name] = decl
                }

                else -> {}
            }
        }

        for (fn in functions) {
            fn.mangledName = mangle(fn)
            if (Builtin.isBuiltinName(fn.name)) {
                error("'${fn.name}' is a builtin and cannot be redefined", fn.loc)
            }
        }

        val seen = HashSet<String>()
        for (fn in functions) {
            if (!seen.add(fn.mangledName)) {
                error("function '${fn.name}' is defined twice with the same parameter types", fn.loc)
            }
        }
    }

    private fun mangle(fn: FnDef): String {
        if (fn.isKernel) return fn.name
        val prefix = if (fn.module.isEmpty()) "" else fn.module.replace('.', '_') + "_"
        val params = if (fn.params.isEmpty()) "" else "_" + fn.params.joinToString("_") { sanitize(it.type.name) }
        return "spz_$prefix${fn.name}$params"
    }

    private fun sanitize(name: String): String = buildString {
        for (c in name) append(if (c.isLetterOrDigit()) c else '.')
    }.replace(".", "")

    private fun checkStruct(struct: StructDef) {
        val names = HashSet<String>()
        for (field in struct.fields) {
            if (!names.add(field.name)) error("duplicate field '${field.name}'", field.loc)
            validateType(field.type, field.loc)
            if (field.type is SliceType) {
                error("struct fields cannot be slices", field.loc)
            }
        }
        if (layout.isRecursive(struct.name)) {
            error("struct '${struct.name}' contains itself", struct.loc)
        }
    }

    private fun checkConst(def: ConstDef) {
        pushScope()
        val declared = def.declaredType
        if (declared != null) validateType(declared, def.loc)
        var type = infer(def.value, declared)
        type = settle(def.value, type)
        popScope()

        if (type == ErrorType) return
        if (type !is IntType && type !is FloatType && type != BoolType) {
            error("constants must be a scalar integer, float or bool, got '${type.name}'", def.loc)
            return
        }
        if (declared != null && declared != type) {
            error("constant '${def.name}' is declared '${declared.name}' but the value is '${type.name}'", def.loc)
            return
        }
        def.resolvedType = type
        val value = evaluate(def.value)
        if (value == null) {
            error("constant '${def.name}' must have a compile time value", def.loc)
            return
        }
        def.evaluated = value
    }

    private fun checkSignature(fn: FnDef) {
        val names = HashSet<String>()
        for (param in fn.params) {
            if (!names.add(param.name)) error("duplicate parameter '${param.name}'", param.loc)
            validateType(param.type, param.loc)
            if (param.type is ArrayType) {
                error("array parameters are not allowed, pass a slice instead", param.loc)
            }
        }
        validateType(fn.retType, fn.loc)

        if (escapesPrivate(fn.retType)) {
            error(
                "'${fn.name}' returns '${fn.retType.name}', which points into private storage that dies with the call",
                fn.loc,
                "return a value, or take a 'global' pointer parameter to write through",
            )
        }

        if (!fn.isKernel) return

        if (fn.retType != VoidType) {
            error("kernel '${fn.name}' must return void", fn.loc)
        }
        for (param in fn.params) {
            val space = param.type.pointerSpace ?: continue
            if (space == AddressSpace.Private) {
                error(
                    "kernel parameter '${param.name}' must name an address space",
                    param.loc,
                    "write 'global ${param.type.name}' or 'uniform ${param.type.name}'",
                )
            } else if (space == AddressSpace.Shared) {
                error("kernel parameter '${param.name}' cannot live in 'shared' storage", param.loc)
            }
        }
    }

    private fun escapesPrivate(type: Type): Boolean = when (type) {
        is PtrType -> type.space == AddressSpace.Private
        is SliceType -> type.space == AddressSpace.Private
        else -> false
    }

    private fun validateType(type: Type, loc: Token) {
        when (type) {
            is StructType -> if (!structs.containsKey(type.name)) error("unknown struct '${type.name}'", loc)
            is ArrayType -> validateType(type.elem, loc)
            is PtrType -> validateType(type.pointee, loc)
            is SliceType -> validateType(type.elem, loc)
            is VectorType -> {}
            else -> {}
        }
    }

    private fun checkBody(fn: FnDef) {
        currentFn = fn
        loopDepth = 0
        pushScope()
        for ((index, param) in fn.params.withIndex()) {
            define(param.name, Binding.Parameter(param, index), param.loc)
        }
        checkBlock(fn.body, ownScope = false)
        popScope()

        if (fn.retType != VoidType && !alwaysReturns(fn.body)) {
            error(
                "'${fn.name}' must return '${fn.retType.name}' on every path",
                fn.loc,
                "add a 'return' at the end, or an 'else' branch that returns",
            )
        }
        currentFn = null
    }

    private fun alwaysReturns(stmt: Stmt): Boolean = when (stmt) {
        is ReturnStmt -> true
        is Block -> stmt.stmts.any { alwaysReturns(it) }
        is IfStmt -> stmt.elseBranch != null &&
            alwaysReturns(stmt.thenBlock) &&
            alwaysReturns(stmt.elseBranch)

        is SwitchStmt -> stmt.elseBlock != null &&
            alwaysReturns(stmt.elseBlock) &&
            stmt.cases.all { alwaysReturns(it.body) }

        is LoopStmt -> !escapesLoop(stmt.body)
        else -> false
    }

    private fun escapesLoop(stmt: Stmt): Boolean = when (stmt) {
        is BreakStmt -> true
        is Block -> stmt.stmts.any { escapesLoop(it) }
        is IfStmt -> escapesLoop(stmt.thenBlock) || (stmt.elseBranch?.let { escapesLoop(it) } ?: false)
        is SwitchStmt -> stmt.cases.any { escapesLoop(it.body) } || (stmt.elseBlock?.let { escapesLoop(it) } ?: false)
        else -> false
    }

    private fun checkBlock(block: Block, ownScope: Boolean = true) {
        if (ownScope) pushScope()
        for (stmt in block.stmts) checkStmt(stmt)
        if (ownScope) popScope()
    }

    private fun checkStmt(stmt: Stmt) {
        when (stmt) {
            is Block -> checkBlock(stmt)
            is VarDecl -> checkVarDecl(stmt)
            is Assign -> checkAssign(stmt)
            is IfStmt -> checkIf(stmt)
            is WhileStmt -> {
                expectBool(stmt.cond)
                loopDepth++
                checkBlock(stmt.body)
                loopDepth--
            }

            is ForStmt -> checkFor(stmt)
            is LoopStmt -> {
                loopDepth++
                checkBlock(stmt.body)
                loopDepth--
            }

            is SwitchStmt -> checkSwitch(stmt)
            is BreakStmt -> if (loopDepth == 0) error("'break' is only valid inside a loop", stmt.loc)
            is ContinueStmt -> if (loopDepth == 0) error("'continue' is only valid inside a loop", stmt.loc)
            is ReturnStmt -> checkReturn(stmt)
            is Expr -> settle(stmt, infer(stmt, null))
        }
    }

    private fun checkVarDecl(decl: VarDecl) {
        val declared = decl.declaredType
        if (declared != null) validateType(declared, decl.loc)

        var type = if (decl.init != null) settle(decl.init, infer(decl.init, declared)) else declared ?: ErrorType

        if (declared != null && decl.init != null && type != ErrorType && !assignable(declared, type)) {
            error("'${decl.name}' is declared '${declared.name}' but the value is '${type.name}'", decl.loc)
            type = declared
        }
        if (declared != null) type = declared

        if (type == VoidType || type == UnknownType) {
            error("cannot infer a type for '${decl.name}'", decl.loc)
            type = ErrorType
        }

        if (decl.space == AddressSpace.Shared) {
            checkSharedDecl(decl, type)
        } else if (decl.space != AddressSpace.Private) {
            error("local storage cannot live in '${decl.space.keyword}' memory", decl.loc)
        }

        decl.resolvedType = type
        define(decl.name, Binding.Local(decl), decl.loc)
    }

    private fun checkSharedDecl(decl: VarDecl, type: Type) {
        val fn = currentFn
        if (fn == null || !fn.isKernel) {
            error("'shared' storage can only be declared inside a kernel", decl.loc)
            return
        }
        if (decl.init != null) {
            error("'shared' storage cannot have an initializer", decl.loc)
        }
        if (type is SliceType || type is PtrType) {
            error("'shared' storage must be a value or an array, not a reference", decl.loc)
        }
        fn.usesShared = true
        decl.globalName = "spz.shared.${fn.name}.${decl.name}.${sharedCounter++}"
        sharedGlobals.add(decl)
    }

    private fun checkAssign(assign: Assign) {
        val targetType = infer(assign.target, null)
        checkAssignable(assign.target)

        if (assign.op == null) {
            val valueType = settle(assign.value, infer(assign.value, targetType))
            if (targetType != ErrorType && valueType != ErrorType && !assignable(targetType, valueType)) {
                error("cannot assign '${valueType.name}' to '${targetType.name}'", assign.loc)
            }
            return
        }

        val valueType = settle(assign.value, infer(assign.value, targetType))
        if (targetType == ErrorType || valueType == ErrorType) return
        val result = binaryResult(assign.op, targetType, valueType, assign.loc)
        if (result != ErrorType && result != targetType) {
            error("'${assign.op.text}=' on '${targetType.name}' produces '${result.name}'", assign.loc)
        }
    }

    private fun checkAssignable(target: Expr) {
        when (target) {
            is VarRef -> when (val binding = target.binding) {
                is Binding.Local -> if (!binding.decl.isMut) {
                    error("'${target.name}' is not mutable", target.loc, "declare it with 'let mut'")
                }

                is Binding.Parameter -> error("parameter '${target.name}' is not mutable", target.loc)
                is Binding.Induction -> error("loop variable '${target.name}' is not mutable", target.loc)
                is Binding.Constant -> error("'${target.name}' is a constant", target.loc)
                null -> {}
            }

            is Index -> {
                val container = target.target.resolvedType
                when (container) {
                    is SliceType -> if (!container.mutable) {
                        error("cannot write through a read only slice", target.loc, "declare it 'mut'")
                    }

                    is PtrType -> if (!container.mutable) {
                        error("cannot write through a read only pointer", target.loc, "declare it 'mut'")
                    }

                    else -> checkAssignable(target.target)
                }
            }

            is Access -> {
                if (target.kind == AccessKind.SliceLength) {
                    error("a slice length is read only", target.loc)
                } else {
                    checkAssignable(target.target)
                }
            }

            is Deref -> {
                val pointer = target.operand.resolvedType
                if (pointer is PtrType && !pointer.mutable) {
                    error("cannot write through a read only pointer", target.loc, "declare it 'mut'")
                }
            }

            else -> error("this expression cannot be assigned to", target.loc)
        }
    }

    private fun checkIf(stmt: IfStmt) {
        expectBool(stmt.cond)
        checkBlock(stmt.thenBlock)
        when (val branch = stmt.elseBranch) {
            null -> {}
            is IfStmt -> checkIf(branch)
            else -> checkStmt(branch)
        }
    }

    private fun checkFor(stmt: ForStmt) {
        var startType = infer(stmt.start, null)
        var endType = infer(stmt.end, if (startType == UnknownType) null else startType)
        if (startType == UnknownType && endType != UnknownType) {
            startType = infer(stmt.start, endType)
        }
        startType = settle(stmt.start, startType)
        if (endType == UnknownType) endType = infer(stmt.end, startType)
        endType = settle(stmt.end, endType)

        if (startType == ErrorType || endType == ErrorType) {
            stmt.inductionType = ErrorType
        } else if (!startType.isInt || startType != endType) {
            error("a range needs two integers of the same type, got '${startType.name}' and '${endType.name}'", stmt.loc)
            stmt.inductionType = ErrorType
        } else {
            stmt.inductionType = startType
        }

        pushScope()
        define(stmt.varName, Binding.Induction(stmt), stmt.loc)
        loopDepth++
        checkBlock(stmt.body)
        loopDepth--
        popScope()
    }

    private fun checkSwitch(stmt: SwitchStmt) {
        val subjectType = settle(stmt.subject, infer(stmt.subject, null))
        if (subjectType != ErrorType && !subjectType.isInt && subjectType != BoolType) {
            error("'switch' needs an integer or bool, got '${subjectType.name}'", stmt.subject.loc)
        }

        val seen = HashSet<Long>()
        for (case in stmt.cases) {
            val constantValues = LongArray(case.values.size)
            for ((index, value) in case.values.withIndex()) {
                settle(value, infer(value, subjectType))
                val evaluated = evaluate(value)
                if (evaluated == null) {
                    error("'case' values must be compile time constants", value.loc)
                } else {
                    if (!seen.add(evaluated.asLong)) {
                        error("duplicate case value ${evaluated.asLong}", value.loc)
                    }
                    constantValues[index] = evaluated.asLong
                }
            }
            case.constants = constantValues
            checkBlock(case.body)
        }
        stmt.elseBlock?.let { checkBlock(it) }
    }

    private fun checkReturn(stmt: ReturnStmt) {
        val expected = currentFn?.retType ?: VoidType
        if (stmt.value == null) {
            if (expected != VoidType) error("this function must return '${expected.name}'", stmt.loc)
            return
        }
        val actual = settle(stmt.value, infer(stmt.value, expected))
        if (actual == ErrorType) return
        if (expected == VoidType) {
            error("this function returns void but a value was given", stmt.loc)
            return
        }
        if (!assignable(expected, actual)) {
            error("this function returns '${expected.name}' but the value is '${actual.name}'", stmt.loc)
        }
        if (pointsAtLocal(stmt.value)) {
            error(
                "cannot return a pointer into storage that dies with this call",
                stmt.loc,
                "copy the value out, or write through a 'global' pointer parameter",
            )
        }
    }

    private fun pointsAtLocal(expr: Expr): Boolean = when (expr) {
        is AddrOf -> rootSpace(expr.operand) == AddressSpace.Private
        is Ternary -> pointsAtLocal(expr.ifTrue) || pointsAtLocal(expr.ifFalse)
        else -> false
    }

    private fun rootSpace(expr: Expr): AddressSpace? = when (expr) {
        is VarRef -> when (val binding = expr.binding) {
            is Binding.Local -> binding.decl.space
            is Binding.Parameter -> binding.param.type.pointerSpace ?: AddressSpace.Private
            is Binding.Induction -> AddressSpace.Private
            else -> AddressSpace.Private
        }

        is Index -> expr.target.resolvedType.pointerSpace ?: rootSpace(expr.target)
        is Access -> rootSpace(expr.target)
        is Deref -> expr.operand.resolvedType.pointerSpace
        else -> AddressSpace.Private
    }

    private fun expectBool(expr: Expr) {
        val type = settle(expr, infer(expr, BoolType))
        if (type != BoolType && type != ErrorType) {
            error("expected a bool condition, got '${type.name}'", expr.loc)
        }
    }

    private fun assignable(target: Type, value: Type): Boolean {
        if (target == value) return true
        if (target is SliceType && value is ArrayType && target.elem == value.elem) return true
        return false
    }

    private fun settle(expr: Expr, type: Type): Type {
        if (type != UnknownType) return type
        val fallback = defaultFor(expr) ?: return type
        return infer(expr, fallback)
    }

    private fun defaultFor(expr: Expr): Type? = when (expr) {
        is LiteralInt -> Types.I32
        is LiteralFloat -> Types.F32
        is BinOp -> defaultFor(expr.left) ?: defaultFor(expr.right)
        is UnaryOp -> defaultFor(expr.operand)
        is Ternary -> defaultFor(expr.ifTrue) ?: defaultFor(expr.ifFalse)
        else -> null
    }

    private fun infer(expr: Expr, expected: Type?): Type {
        val type = when (expr) {
            is LiteralInt -> inferIntLiteral(expr, expected)
            is LiteralFloat -> inferFloatLiteral(expr, expected)
            is LiteralBool -> BoolType
            is VarRef -> inferVarRef(expr)
            is BinOp -> inferBinOp(expr, expected)
            is LogicalOp -> inferLogical(expr)
            is UnaryOp -> inferUnary(expr, expected)
            is Ternary -> inferTernary(expr, expected)
            is CastExpr -> inferCast(expr)
            is Call -> inferCall(expr)
            is StructLit -> inferStructLit(expr)
            is Access -> inferAccess(expr)
            is Index -> inferIndex(expr)
            is AddrOf -> inferAddrOf(expr)
            is Deref -> inferDeref(expr)
        }
        expr.resolvedType = type
        return type
    }

    private fun inferIntLiteral(expr: LiteralInt, expected: Type?): Type {
        expr.suffix?.let { return it }
        val target = expected ?: return UnknownType
        val element = target.elementOrSelf
        return when {
            element.isInt -> target
            element.isFloat -> target
            else -> UnknownType
        }
    }

    private fun inferFloatLiteral(expr: LiteralFloat, expected: Type?): Type {
        expr.suffix?.let { return it }
        val target = expected ?: return UnknownType
        return if (target.elementOrSelf.isFloat) target else UnknownType
    }

    private fun inferVarRef(expr: VarRef): Type {
        val binding = lookup(expr.name)
        if (binding == null) {
            val constant = constants[expr.name]
            if (constant != null) {
                val constBinding = Binding.Constant(constant)
                expr.binding = constBinding
                return constant.resolvedType
            }
            error("'${expr.name}' is not defined", expr.loc)
            return ErrorType
        }
        expr.binding = binding
        return binding.type
    }

    private fun inferBinOp(expr: BinOp, expected: Type?): Type {
        val shift = expr.op == TokenType.LSHIFT || expr.op == TokenType.RSHIFT
        val comparison = expr.op == TokenType.EQEQ || expr.op == TokenType.NEQ ||
            expr.op == TokenType.LESS || expr.op == TokenType.LESS_EQ ||
            expr.op == TokenType.GREATER || expr.op == TokenType.GREATER_EQ

        val hint = if (comparison) null else expected
        var leftType = infer(expr.left, hint)
        var rightType = infer(expr.right, if (shift) null else if (leftType == UnknownType) hint else leftType)
        if (leftType == UnknownType && rightType != UnknownType && !shift) {
            leftType = infer(expr.left, rightType)
        }
        if (rightType == UnknownType && leftType != UnknownType) {
            rightType = infer(expr.right, if (shift) leftType.elementOrSelf else leftType)
        }
        if (leftType == UnknownType || rightType == UnknownType) return UnknownType
        return binaryResult(expr.op, leftType, rightType, expr.loc)
    }

    private fun binaryResult(op: TokenType, leftType: Type, rightType: Type, loc: Token): Type {
        if (leftType == ErrorType || rightType == ErrorType) return ErrorType

        val shift = op == TokenType.LSHIFT || op == TokenType.RSHIFT
        if (shift) {
            if (!leftType.elementOrSelf.isInt || !rightType.elementOrSelf.isInt) {
                error("shift needs integers, got '${leftType.name}' and '${rightType.name}'", loc)
                return ErrorType
            }
            if (rightType is VectorType && rightType != leftType) {
                error("shift amount '${rightType.name}' does not match '${leftType.name}'", loc)
                return ErrorType
            }
            return leftType
        }

        val unified = unify(leftType, rightType)
        if (unified == null) {
            error("cannot apply '${op.text}' to '${leftType.name}' and '${rightType.name}'", loc)
            return ErrorType
        }

        return when (op) {
            TokenType.EQEQ, TokenType.NEQ,
            TokenType.LESS, TokenType.LESS_EQ,
            TokenType.GREATER, TokenType.GREATER_EQ,
            -> {
                if (unified is VectorType) {
                    error("comparing vectors is not supported", loc)
                    ErrorType
                } else if (!unified.isNumeric && unified != BoolType) {
                    error("cannot compare '${unified.name}'", loc)
                    ErrorType
                } else {
                    BoolType
                }
            }

            TokenType.PLUS, TokenType.MINUS, TokenType.STAR, TokenType.SLASH -> {
                if (!unified.elementOrSelf.isNumeric) {
                    error("'${op.text}' needs numbers, got '${unified.name}'", loc)
                    ErrorType
                } else {
                    unified
                }
            }

            TokenType.PERCENT -> {
                if (!unified.elementOrSelf.isInt) {
                    error("'%' needs integers, use 'fmod' for floats", loc)
                    ErrorType
                } else {
                    unified
                }
            }

            TokenType.AMP, TokenType.PIPE, TokenType.CARET -> {
                if (unified == BoolType) unified
                else if (!unified.elementOrSelf.isInt) {
                    error("'${op.text}' needs integers, got '${unified.name}'", loc)
                    ErrorType
                } else {
                    unified
                }
            }

            else -> {
                error("'${op.text}' is not a binary operator", loc)
                ErrorType
            }
        }
    }

    private fun unify(left: Type, right: Type): Type? {
        if (left == right) return left
        if (left is VectorType && right == left.elem) return left
        if (right is VectorType && left == right.elem) return right
        return null
    }

    private fun inferLogical(expr: LogicalOp): Type {
        expectBool(expr.left)
        expectBool(expr.right)
        return BoolType
    }

    private fun inferUnary(expr: UnaryOp, expected: Type?): Type {
        val operand = infer(expr.operand, if (expr.op == TokenType.BANG) BoolType else expected)
        if (operand == ErrorType) return ErrorType
        if (operand == UnknownType) return UnknownType
        return when (expr.op) {
            TokenType.BANG -> {
                if (operand != BoolType) {
                    error("'!' needs a bool, got '${operand.name}'", expr.loc)
                    ErrorType
                } else {
                    BoolType
                }
            }

            TokenType.MINUS -> {
                val element = operand.elementOrSelf
                if (!element.isNumeric) {
                    error("'-' needs a number, got '${operand.name}'", expr.loc)
                    ErrorType
                } else if (element is IntType && !element.signed) {
                    error("'-' cannot be applied to the unsigned type '${operand.name}'", expr.loc)
                    ErrorType
                } else {
                    operand
                }
            }

            TokenType.TILDE -> {
                if (!operand.elementOrSelf.isInt) {
                    error("'~' needs an integer, got '${operand.name}'", expr.loc)
                    ErrorType
                } else {
                    operand
                }
            }

            else -> ErrorType
        }
    }

    private fun inferTernary(expr: Ternary, expected: Type?): Type {
        expectBool(expr.cond)
        var trueType = infer(expr.ifTrue, expected)
        var falseType = infer(expr.ifFalse, if (trueType == UnknownType) expected else trueType)
        if (trueType == UnknownType && falseType != UnknownType) trueType = infer(expr.ifTrue, falseType)
        if (trueType == UnknownType || falseType == UnknownType) return UnknownType
        if (trueType == ErrorType || falseType == ErrorType) return ErrorType
        if (trueType != falseType) {
            error("the two ternary branches produce '${trueType.name}' and '${falseType.name}'", expr.loc)
            return ErrorType
        }
        return trueType
    }

    private fun inferCast(expr: CastExpr): Type {
        validateType(expr.targetType, expr.loc)
        val from = settle(expr.expr, infer(expr.expr, null))
        val to = expr.targetType
        if (from == ErrorType || to == ErrorType) return ErrorType

        if (from == to) {
            ctx.report(Level.WARN, "this cast does nothing, '${from.name}' is already the target type", expr.loc)
            return to
        }
        if (from.isNumeric && to.isNumeric) return to
        if (from == BoolType && to.isInt) return to
        if (from.isInt && to == BoolType) return to
        if (from is VectorType && to is VectorType && from.lanes == to.lanes &&
            from.elem.isNumeric && to.elem.isNumeric
        ) {
            return to
        }
        if (from is PtrType && to is PtrType && from.space == to.space) {
            if (to.mutable && !from.mutable) {
                error("a read only pointer cannot become a mutable one", expr.loc)
                return ErrorType
            }
            return to
        }
        if (from is SliceType && to is PtrType && from.space == to.space) {
            if (to.mutable && !from.mutable) {
                error("a read only buffer cannot become a mutable pointer", expr.loc)
                return ErrorType
            }
            return to
        }
        if (from is SliceType && to is SliceType && from.space == to.space) {
            if (to.mutable && !from.mutable) {
                error("a read only buffer cannot become a mutable view", expr.loc)
                return ErrorType
            }
            val fromWidth = layout.sizeOf(from.elem)
            val toWidth = layout.sizeOf(to.elem)
            if (fromWidth == 0 || toWidth == 0) {
                error("cannot view '${from.name}' as '${to.name}'", expr.loc)
                return ErrorType
            }
            if (fromWidth % toWidth != 0 && toWidth % fromWidth != 0) {
                error(
                    "a '${from.elem.name}' is $fromWidth bytes and a '${to.elem.name}' is $toWidth bytes, " +
                        "so one does not divide the other",
                    expr.loc,
                    "views are only allowed between element sizes that divide evenly",
                )
                return ErrorType
            }
            return to
        }
        if (from is ArrayType && to is SliceType && from.elem == to.elem) return to

        error("cannot cast '${from.name}' to '${to.name}'", expr.loc)
        return ErrorType
    }

    private fun inferCall(call: Call): Type {
        Types.vectorByName(call.name)?.let { return inferVectorCall(call, it) }
        Types.scalarsByName[call.name]?.let { return inferScalarCall(call, it) }
        if (Builtin.isBuiltinName(call.name)) return inferBuiltinCall(call)

        val candidates = functionsByName[call.name]
        if (candidates != null && candidates.isNotEmpty()) {
            return inferUserCall(call, candidates)
        }

        val external = externalsByName[call.name]
        if (external != null) return inferExternalCall(call, external)

        for (arg in call.args) settle(arg, infer(arg, null))
        error("'${call.name}' is not defined", call.loc)
        return ErrorType
    }

    private fun inferVectorCall(call: Call, type: VectorType): Type {
        if (call.args.size == 1) {
            val argType = settle(call.args[0], infer(call.args[0], type.elem))
            if (argType != ErrorType && argType != type.elem) {
                error("'${type.name}' splat needs a '${type.elem.name}', got '${argType.name}'", call.loc)
            }
            call.target = CallTarget.VectorSplat(type)
            return type
        }
        if (call.args.size != type.lanes) {
            error("'${type.name}' needs ${type.lanes} components or 1 to splat, got ${call.args.size}", call.loc)
            for (arg in call.args) settle(arg, infer(arg, type.elem))
            return ErrorType
        }
        for (arg in call.args) {
            val argType = settle(arg, infer(arg, type.elem))
            if (argType != ErrorType && argType != type.elem) {
                error("'${type.name}' component must be '${type.elem.name}', got '${argType.name}'", arg.loc)
            }
        }
        call.target = CallTarget.VectorBuild(type)
        return type
    }

    private fun inferScalarCall(call: Call, type: Scalar): Type {
        if (call.args.size != 1) {
            error("'${type.name}(...)' takes exactly one argument", call.loc)
            for (arg in call.args) settle(arg, infer(arg, null))
            return ErrorType
        }
        val argType = settle(call.args[0], infer(call.args[0], type))
        if (argType != ErrorType && !argType.isNumeric && argType != BoolType) {
            error("cannot convert '${argType.name}' to '${type.name}'", call.loc)
            return ErrorType
        }
        call.target = CallTarget.ScalarConvert(type)
        return type
    }

    private fun inferBuiltinCall(call: Call): Type {
        val candidates = Builtin.candidates(call.name)
        val arity = candidates.firstOrNull { it.arity == call.args.size }
        if (arity == null) {
            for (arg in call.args) settle(arg, infer(arg, null))
            error("'${call.name}' does not take ${call.args.size} arguments", call.loc)
            return ErrorType
        }

        val hint = if (arity.group == BuiltinGroup.Select) null else null
        val argTypes = ArrayList<Type>(call.args.size)
        var lead: Type? = null
        for ((index, arg) in call.args.withIndex()) {
            val expectation = when {
                arity.group == BuiltinGroup.Select && index == 0 -> BoolType
                arity.group == BuiltinGroup.Select -> lead
                else -> lead
            } ?: hint
            var type = infer(arg, expectation)
            if (type == UnknownType && lead != null) type = infer(arg, lead)
            type = settle(arg, type)
            argTypes.add(type)
            if (lead == null && type != ErrorType && type != BoolType) lead = type
        }
        if (argTypes.any { it == ErrorType }) return ErrorType

        for (candidate in candidates) {
            if (candidate.arity != call.args.size) continue
            val result = candidate.resolve(argTypes)
            if (result != null) {
                call.target = CallTarget.Builtin(candidate, 0)
                return result
            }
        }

        error("'${call.name}' has no overload for (${argTypes.joinToString { it.name }})", call.loc)
        return ErrorType
    }

    private fun inferUserCall(call: Call, candidates: List<FnDef>): Type {
        val matching = candidates.filter { it.params.size == call.args.size }
        if (matching.isEmpty()) {
            for (arg in call.args) settle(arg, infer(arg, null))
            error("'${call.name}' does not take ${call.args.size} arguments", call.loc)
            return ErrorType
        }

        if (matching.size == 1) {
            val fn = matching[0]
            if (fn.isKernel) {
                error("kernel '${fn.name}' cannot be called from Spezi code", call.loc)
                return ErrorType
            }
            var ok = true
            for ((index, arg) in call.args.withIndex()) {
                val expected = fn.params[index].type
                val actual = settle(arg, infer(arg, expected))
                if (actual != ErrorType && !assignable(expected, actual)) {
                    error(
                        "argument ${index + 1} of '${call.name}' expects '${expected.name}', got '${actual.name}'",
                        arg.loc,
                    )
                    ok = false
                }
            }
            call.target = CallTarget.User(fn)
            return if (ok) fn.retType else ErrorType
        }

        val argTypes = call.args.map { settle(it, infer(it, null)) }
        if (argTypes.any { it == ErrorType }) return ErrorType
        val exact = matching.filter { fn -> fn.params.indices.all { assignable(fn.params[it].type, argTypes[it]) } }
        if (exact.size == 1) {
            call.target = CallTarget.User(exact[0])
            return exact[0].retType
        }
        if (exact.isEmpty()) {
            error("no overload of '${call.name}' accepts (${argTypes.joinToString { it.name }})", call.loc)
        } else {
            error("call to '${call.name}' is ambiguous", call.loc)
        }
        return ErrorType
    }

    private fun inferExternalCall(call: Call, fn: ExternFnDef): Type {
        if (call.args.size != fn.params.size) {
            for (arg in call.args) settle(arg, infer(arg, null))
            error("'${fn.name}' takes ${fn.params.size} arguments, got ${call.args.size}", call.loc)
            return ErrorType
        }
        for ((index, arg) in call.args.withIndex()) {
            val expected = fn.params[index].type
            val actual = settle(arg, infer(arg, expected))
            if (actual != ErrorType && !assignable(expected, actual)) {
                error("argument ${index + 1} of '${fn.name}' expects '${expected.name}', got '${actual.name}'", arg.loc)
            }
        }
        call.target = CallTarget.External(fn)
        return fn.retType
    }

    private fun inferStructLit(lit: StructLit): Type {
        val def = structs[lit.typeName]
        if (def == null) {
            for (arg in lit.args) settle(arg, infer(arg, null))
            error("unknown struct '${lit.typeName}'", lit.loc)
            return ErrorType
        }
        if (lit.args.size != def.fields.size) {
            for (arg in lit.args) settle(arg, infer(arg, null))
            error("'${def.name}' has ${def.fields.size} fields, got ${lit.args.size}", lit.loc)
            return ErrorType
        }
        for ((index, arg) in lit.args.withIndex()) {
            val expected = def.fields[index].type
            val actual = settle(arg, infer(arg, expected))
            if (actual != ErrorType && !assignable(expected, actual)) {
                error(
                    "field '${def.fields[index].name}' expects '${expected.name}', got '${actual.name}'",
                    arg.loc,
                )
            }
        }
        return StructType(def.name)
    }

    private fun inferAccess(access: Access): Type {
        val target = settle(access.target, infer(access.target, null))
        if (target == ErrorType) return ErrorType

        if (target is SliceType && access.member == "len") {
            access.kind = AccessKind.SliceLength
            return Types.U32
        }

        if (target is VectorType) {
            val components = parseSwizzle(access.member)
            if (components == null) {
                error("'${access.member}' is not a vector component", access.loc)
                return ErrorType
            }
            for (component in components) {
                if (component >= target.lanes) {
                    error("'${access.member}' reads past the end of '${target.name}'", access.loc)
                    return ErrorType
                }
            }
            access.kind = AccessKind.Swizzle
            access.swizzle = components
            return if (components.size == 1) target.elem else VectorType(target.elem, components.size)
        }

        val structType = when (target) {
            is StructType -> target
            is PtrType -> target.pointee as? StructType
            else -> null
        }
        if (structType == null) {
            error("cannot read '.${access.member}' from '${target.name}'", access.loc)
            return ErrorType
        }

        val def = structs[structType.name]
        if (def == null) {
            error("unknown struct '${structType.name}'", access.loc)
            return ErrorType
        }
        val index = def.fields.indexOfFirst { it.name == access.member }
        if (index < 0) {
            error("'${structType.name}' has no field '${access.member}'", access.loc)
            return ErrorType
        }
        access.kind = AccessKind.Field
        access.fieldIndex = index
        return def.fields[index].type
    }

    private fun parseSwizzle(member: String): IntArray? {
        if (member.isEmpty() || member.length > 4) return null
        val components = IntArray(member.length)
        var set = -1
        for ((index, c) in member.withIndex()) {
            val position = POSITION.indexOf(c)
            if (position < 0) return null
            val group = position / 4
            if (set >= 0 && group != set) return null
            set = group
            components[index] = position % 4
        }
        if (components.size !in Types.vectorLanes && components.size != 1) return null
        return components
    }

    private fun inferIndex(index: Index): Type {
        val target = settle(index.target, infer(index.target, null))
        val indexType = settle(index.index, infer(index.index, Types.U32))
        if (indexType != ErrorType && !indexType.isInt) {
            error("an index must be an integer, got '${indexType.name}'", index.index.loc)
        }
        if (target == ErrorType) return ErrorType

        val element = target.indexedElement
        if (element == null) {
            error("'${target.name}' cannot be indexed", index.loc)
            return ErrorType
        }
        return element
    }

    private fun inferAddrOf(expr: AddrOf): Type {
        val operand = settle(expr.operand, infer(expr.operand, null))
        if (operand == ErrorType) return ErrorType
        if (!isPlace(expr.operand)) {
            error("'&' needs a variable, field or element", expr.loc)
            return ErrorType
        }
        markAddressTaken(expr.operand)
        val space = rootSpace(expr.operand) ?: AddressSpace.Private
        return PtrType(operand, space, isMutablePlace(expr.operand))
    }

    private fun inferDeref(expr: Deref): Type {
        val operand = settle(expr.operand, infer(expr.operand, null))
        if (operand == ErrorType) return ErrorType
        if (operand !is PtrType) {
            error("cannot dereference '${operand.name}'", expr.loc)
            return ErrorType
        }
        return operand.pointee
    }

    private fun isPlace(expr: Expr): Boolean = when (expr) {
        is VarRef -> expr.binding is Binding.Local
        is Deref -> true
        is Index -> when (expr.target.resolvedType) {
            is SliceType, is PtrType -> true
            is ArrayType, is VectorType -> isPlace(expr.target)
            else -> false
        }

        is Access -> when (expr.kind) {
            AccessKind.Field -> isPlace(expr.target)
            AccessKind.Swizzle -> expr.swizzle.size == 1 && isPlace(expr.target)
            else -> false
        }

        else -> false
    }

    private fun isMutablePlace(expr: Expr): Boolean = when (expr) {
        is VarRef -> (expr.binding as? Binding.Local)?.decl?.isMut ?: false
        is Index -> when (val container = expr.target.resolvedType) {
            is SliceType -> container.mutable
            is PtrType -> container.mutable
            else -> isMutablePlace(expr.target)
        }

        is Access -> isMutablePlace(expr.target)
        is Deref -> (expr.operand.resolvedType as? PtrType)?.mutable ?: false
        else -> false
    }

    private fun markAddressTaken(expr: Expr) {
        when (expr) {
            is VarRef -> (expr.binding as? Binding.Local)?.decl?.addressTaken = true
            is Index -> markAddressTaken(expr.target)
            is Access -> markAddressTaken(expr.target)
            else -> {}
        }
    }

    private fun evaluate(expr: Expr): ConstValue? = when (expr) {
        is LiteralInt -> ConstValue(expr.value, expr.value.toDouble())
        is LiteralFloat -> ConstValue(expr.value.toLong(), expr.value)
        is LiteralBool -> ConstValue(if (expr.value) 1L else 0L, if (expr.value) 1.0 else 0.0)
        is VarRef -> (expr.binding as? Binding.Constant)?.def?.evaluated
        is UnaryOp -> evaluate(expr.operand)?.let { operand ->
            when (expr.op) {
                TokenType.MINUS -> ConstValue(-operand.asLong, -operand.asDouble)
                TokenType.TILDE -> ConstValue(operand.asLong.inv(), operand.asLong.inv().toDouble())
                TokenType.BANG -> ConstValue(if (operand.asLong == 0L) 1L else 0L, 0.0)
                else -> null
            }
        }

        is BinOp -> {
            val left = evaluate(expr.left)
            val right = evaluate(expr.right)
            if (left == null || right == null) null else foldBinary(expr, left, right)
        }

        is CastExpr -> evaluate(expr.expr)
        else -> null
    }

    private fun foldBinary(expr: BinOp, left: ConstValue, right: ConstValue): ConstValue? {
        val isFloat = expr.resolvedType.elementOrSelf.isFloat
        val signed = (expr.resolvedType.elementOrSelf as? IntType)?.signed ?: true
        return when (expr.op) {
            TokenType.PLUS -> ConstValue(left.asLong + right.asLong, left.asDouble + right.asDouble)
            TokenType.MINUS -> ConstValue(left.asLong - right.asLong, left.asDouble - right.asDouble)
            TokenType.STAR -> ConstValue(left.asLong * right.asLong, left.asDouble * right.asDouble)
            TokenType.SLASH -> when {
                isFloat -> ConstValue((left.asDouble / right.asDouble).toLong(), left.asDouble / right.asDouble)
                right.asLong == 0L -> null
                signed -> ConstValue(left.asLong / right.asLong, 0.0)
                else -> ConstValue((left.asLong.toULong() / right.asLong.toULong()).toLong(), 0.0)
            }

            TokenType.PERCENT -> when {
                right.asLong == 0L -> null
                signed -> ConstValue(left.asLong % right.asLong, 0.0)
                else -> ConstValue((left.asLong.toULong() % right.asLong.toULong()).toLong(), 0.0)
            }

            TokenType.AMP -> ConstValue(left.asLong and right.asLong, 0.0)
            TokenType.PIPE -> ConstValue(left.asLong or right.asLong, 0.0)
            TokenType.CARET -> ConstValue(left.asLong xor right.asLong, 0.0)
            TokenType.LSHIFT -> ConstValue(left.asLong shl right.asLong.toInt(), 0.0)
            TokenType.RSHIFT -> if (signed) {
                ConstValue(left.asLong shr right.asLong.toInt(), 0.0)
            } else {
                ConstValue(left.asLong ushr right.asLong.toInt(), 0.0)
            }

            else -> null
        }
    }

    private fun pushScope() {
        scopes.add(HashMap())
    }

    private fun popScope() {
        scopes.removeAt(scopes.size - 1)
    }

    private fun define(name: String, binding: Binding, loc: Token) {
        val frame = scopes[scopes.size - 1]
        if (frame.containsKey(name)) {
            error("'$name' is already defined in this scope", loc)
        }
        frame[name] = binding
    }

    private fun lookup(name: String): Binding? {
        for (index in scopes.indices.reversed()) {
            scopes[index][name]?.let { return it }
        }
        return null
    }

    private fun error(message: String, loc: Token, note: String? = null) {
        ctx.report(Level.ERROR, message, loc, note)
    }

    private companion object {

        const val POSITION = "xyzwrgba"
    }
}
