package spezi.backend.llvm

import spezi.backend.target.TargetKind
import spezi.backend.target.TargetProfile
import spezi.common.Context
import spezi.common.Level
import spezi.domain.*
import spezi.frontend.semantic.Builtin
import spezi.frontend.semantic.BuiltinGroup
import spezi.frontend.semantic.SemanticModel

class LlvmBackend(
    private val ctx: Context,
    private val model: SemanticModel,
    private val profile: TargetProfile,
    private val boundsChecks: Boolean,
) {

    private class Val(val type: Type, val ref: String)

    private class Place(val type: Type, val ptr: String, val ptrType: String)

    private val types = LlvmTypes(profile)
    private val module = StringBuilder()
    private val declarations = LinkedHashSet<String>()
    private val dispatchUsers = HashSet<FnDef>()

    private val allocas = StringBuilder()
    private val body = StringBuilder()
    private var temps = 0
    private var labels = 0
    private var currentLabel = "entry"
    private var terminated = false
    private var dispatchArg: String? = null
    private val slots = HashMap<VarDecl, Place>()
    private val paramValues = HashMap<String, Val>()
    private val inductionValues = HashMap<ForStmt, Val>()
    private val breakTargets = ArrayList<String>()
    private val continueTargets = ArrayList<String>()

    fun emit(): String {
        analyzeDispatchUsage()

        module.append("target triple = \"").append(profile.triple).append("\"\n\n")

        for (struct in model.structOrder) {
            val fields = struct.fields.joinToString(", ") { types.storage(it.type) }
            module.append("%struct.").append(struct.name).append(" = type { ").append(fields).append(" }\n")
        }
        if (model.structOrder.isNotEmpty()) module.append('\n')

        if (!profile.hardwareDispatch) {
            module.append("%spz.dispatch = type { [3 x i32], [3 x i32], [3 x i32] }\n\n")
        }

        for (decl in model.sharedGlobals) emitSharedGlobal(decl)
        if (model.sharedGlobals.isNotEmpty()) module.append('\n')

        for (external in model.externals) {
            val params = ArrayList<String>()
            for (param in external.params) appendAbiTypes(param.type, params)
            module.append("declare ").append(types.value(external.retType)).append(" @")
                .append(external.name).append('(').append(params.joinToString(", ")).append(")\n")
        }
        if (model.externals.isNotEmpty()) module.append('\n')

        for (fn in model.functions) emitFunction(fn)

        for (declaration in declarations) module.append(declaration).append('\n')

        return module.toString()
    }

    private fun analyzeDispatchUsage() {
        if (profile.hardwareDispatch) return

        val callees = HashMap<FnDef, MutableSet<FnDef>>()
        for (fn in model.functions) {
            val direct = HashSet<FnDef>()
            callees[fn] = direct
            walk(fn.body) { expr ->
                if (expr is Call) {
                    when (val target = expr.target) {
                        is CallTarget.Builtin ->
                            if (target.builtin.group == BuiltinGroup.Dispatch) dispatchUsers.add(fn)

                        is CallTarget.User -> direct.add(target.fn)
                        else -> {}
                    }
                }
            }
        }

        var changed = true
        while (changed) {
            changed = false
            for (fn in model.functions) {
                if (fn in dispatchUsers) continue
                if (callees.getValue(fn).any { it in dispatchUsers }) {
                    dispatchUsers.add(fn)
                    changed = true
                }
            }
        }
    }

    private fun walk(node: AstNode, action: (Expr) -> Unit) {
        when (node) {
            is Block -> node.stmts.forEach { walk(it, action) }
            is VarDecl -> node.init?.let { walk(it, action) }
            is Assign -> {
                walk(node.target, action)
                walk(node.value, action)
            }

            is IfStmt -> {
                walk(node.cond, action)
                walk(node.thenBlock, action)
                node.elseBranch?.let { walk(it, action) }
            }

            is WhileStmt -> {
                walk(node.cond, action)
                walk(node.body, action)
            }

            is ForStmt -> {
                walk(node.start, action)
                walk(node.end, action)
                walk(node.body, action)
            }

            is LoopStmt -> walk(node.body, action)
            is SwitchStmt -> {
                walk(node.subject, action)
                node.cases.forEach { case ->
                    case.values.forEach { walk(it, action) }
                    walk(case.body, action)
                }
                node.elseBlock?.let { walk(it, action) }
            }

            is ReturnStmt -> node.value?.let { walk(it, action) }
            is Expr -> {
                action(node)
                when (node) {
                    is BinOp -> {
                        walk(node.left, action); walk(node.right, action)
                    }

                    is LogicalOp -> {
                        walk(node.left, action); walk(node.right, action)
                    }

                    is UnaryOp -> walk(node.operand, action)
                    is Ternary -> {
                        walk(node.cond, action)
                        walk(node.ifTrue, action)
                        walk(node.ifFalse, action)
                    }

                    is CastExpr -> walk(node.expr, action)
                    is Call -> node.args.forEach { walk(it, action) }
                    is StructLit -> node.args.forEach { walk(it, action) }
                    is Access -> walk(node.target, action)
                    is Index -> {
                        walk(node.target, action); walk(node.index, action)
                    }

                    is AddrOf -> walk(node.operand, action)
                    is Deref -> walk(node.operand, action)
                    else -> {}
                }
            }

            else -> {}
        }
    }

    private fun emitSharedGlobal(decl: VarDecl) {
        val name = decl.globalName ?: return
        val space = profile.spaceOf(AddressSpace.Shared)
        val qualifier = if (space == 0) "" else "addrspace($space) "
        module.append('@').append(name).append(" = internal ").append(qualifier)
            .append("global ").append(types.storage(decl.resolvedType))
            .append(" undef, align ").append(model.layout.alignOf(decl.resolvedType).coerceAtLeast(1))
            .append('\n')
    }

    private fun appendAbiTypes(type: Type, out: MutableList<String>) {
        if (type is SliceType) {
            out.add(types.pointer(type.space))
            out.add("i32")
        } else {
            out.add(types.value(type))
        }
    }

    private fun emitFunction(fn: FnDef) {
        allocas.setLength(0)
        body.setLength(0)
        temps = 0
        labels = 0
        currentLabel = "entry"
        terminated = false
        slots.clear()
        paramValues.clear()
        inductionValues.clear()
        breakTargets.clear()
        continueTargets.clear()
        dispatchArg = null

        val signature = ArrayList<String>()
        if (!profile.hardwareDispatch && (fn.isKernel || fn in dispatchUsers)) {
            dispatchArg = "%spz.dispatch.arg"
            signature.add("ptr nocapture readonly %spz.dispatch.arg")
        }

        for (param in fn.params) {
            val type = param.type
            if (type is SliceType) {
                val readonly = if (type.mutable) "" else " readonly"
                signature.add("${types.pointer(type.space)} noundef$readonly %arg.${param.name}")
                signature.add("i32 noundef %arg.${param.name}.len")
            } else {
                signature.add("${types.value(type)} %arg.${param.name}")
            }
        }

        for (param in fn.params) bindParameter(param)
        for (stmt in fn.body.stmts) emitStmt(stmt)

        if (!terminated) {
            if (fn.retType == VoidType) line("ret void") else line("unreachable")
        }

        val linkage = if (fn.isKernel) "" else "internal "
        val convention = if (fn.isKernel && profile.kernelCallingConv != null) profile.kernelCallingConv + " " else ""

        module.append("define ").append(linkage).append(convention)
            .append(types.value(fn.retType)).append(" @").append(fn.mangledName)
            .append('(').append(signature.joinToString(", ")).append(") ")
            .append(functionAttributes(fn)).append("{\n")
        module.append("entry:\n")
        module.append(allocas)
        module.append(body)
        module.append("}\n\n")
    }

    private fun functionAttributes(fn: FnDef): String {
        val attributes = ArrayList<String>()
        if (profile.isGpu) attributes.add("\"no-jump-tables\"=\"true\"")
        if (fn.isKernel && profile.kind == TargetKind.AmdGcn) {
            attributes.add("\"amdgpu-flat-work-group-size\"=\"1,1024\"")
        }
        if (!fn.isKernel) attributes.add(if (profile.isGpu) "alwaysinline" else "inlinehint")
        return if (attributes.isEmpty()) "" else attributes.joinToString(" ") + " "
    }

    private fun bindParameter(param: Param) {
        val type = param.type
        if (type is SliceType) {
            val withPtr = nextTemp()
            line("$withPtr = insertvalue ${types.value(type)} undef, ${types.pointer(type.space)} %arg.${param.name}, 0")
            val withLen = nextTemp()
            line("$withLen = insertvalue ${types.value(type)} $withPtr, i32 %arg.${param.name}.len, 1")
            paramValues[param.name] = Val(type, withLen)
            return
        }
        paramValues[param.name] = Val(type, "%arg.${param.name}")
    }

    private fun emitStmt(stmt: Stmt) {
        if (terminated) startBlock(nextLabel())
        when (stmt) {
            is Block -> stmt.stmts.forEach { emitStmt(it) }
            is VarDecl -> emitVarDecl(stmt)
            is Assign -> emitAssign(stmt)
            is IfStmt -> emitIf(stmt)
            is WhileStmt -> emitWhile(stmt)
            is ForStmt -> emitFor(stmt)
            is LoopStmt -> emitLoop(stmt)
            is SwitchStmt -> emitSwitch(stmt)
            is BreakStmt -> branch(breakTargets.last())
            is ContinueStmt -> branch(continueTargets.last())
            is ReturnStmt -> emitReturn(stmt)
            is Expr -> emitExpr(stmt)
        }
    }

    private fun emitVarDecl(decl: VarDecl) {
        val type = decl.resolvedType
        if (decl.space == AddressSpace.Shared) {
            val name = decl.globalName ?: return
            val space = profile.spaceOf(AddressSpace.Shared)
            val pointerType = if (space == 0) "ptr" else "ptr addrspace($space)"
            slots[decl] = Place(type, "@$name", pointerType)
            return
        }

        val slot = allocate(type, decl.name)
        slots[decl] = slot
        val init = decl.init ?: return
        if (type is SliceType && init.resolvedType is ArrayType) {
            storeTo(slot, sliceFromArray(init, type))
        } else {
            storeTo(slot, emitExpr(init))
        }
    }

    private fun allocate(type: Type, hint: String): Place {
        val name = "%slot.${sanitize(hint)}.${temps++}"
        val space = profile.spaceOf(AddressSpace.Private)
        allocas.append("  ").append(name).append(" = alloca ").append(types.storage(type))
            .append(", align ").append(model.layout.alignOf(type).coerceAtLeast(1))
        if (space != 0) allocas.append(", addrspace(").append(space).append(')')
        allocas.append('\n')
        return Place(type, name, types.pointer(AddressSpace.Private))
    }

    private fun sanitize(name: String): String = buildString {
        for (c in name) append(if (c.isLetterOrDigit() || c == '_') c else '_')
    }

    private fun emitAssign(assign: Assign) {
        val place = requirePlace(assign.target)
        if (assign.op == null) {
            if (place.type is SliceType && assign.value.resolvedType is ArrayType) {
                storeTo(place, sliceFromArray(assign.value, place.type as SliceType))
            } else {
                storeTo(place, emitExpr(assign.value))
            }
            return
        }
        val current = loadFrom(place)
        val operand = emitExpr(assign.value)
        storeTo(place, binary(assign.op, current, operand, place.type, assign.loc))
    }

    private fun sliceFromArray(expr: Expr, target: SliceType): Val {
        val array = expr.resolvedType as ArrayType
        val place = requirePlace(expr)
        val pointer = nextTemp()
        line(
            "$pointer = getelementptr inbounds ${types.storage(array)}, ${place.ptrType} ${place.ptr}, i64 0, i64 0",
        )
        val withPtr = nextTemp()
        line("$withPtr = insertvalue ${types.value(target)} undef, ${types.pointer(target.space)} $pointer, 0")
        val withLen = nextTemp()
        line("$withLen = insertvalue ${types.value(target)} $withPtr, i32 ${array.length}, 1")
        return Val(target, withLen)
    }

    private fun emitIf(stmt: IfStmt) {
        val condition = emitExpr(stmt.cond)
        val thenLabel = nextLabel()
        val elseLabel = if (stmt.elseBranch != null) nextLabel() else null
        val endLabel = nextLabel()

        line("br i1 ${condition.ref}, label %$thenLabel, label %${elseLabel ?: endLabel}")
        terminated = true

        startBlock(thenLabel)
        emitStmt(stmt.thenBlock)
        branch(endLabel)

        if (elseLabel != null) {
            startBlock(elseLabel)
            emitStmt(stmt.elseBranch!!)
            branch(endLabel)
        }

        startBlock(endLabel)
    }

    private fun emitWhile(stmt: WhileStmt) {
        val headLabel = nextLabel()
        val bodyLabel = nextLabel()
        val endLabel = nextLabel()

        branch(headLabel)
        startBlock(headLabel)
        val condition = emitExpr(stmt.cond)
        line("br i1 ${condition.ref}, label %$bodyLabel, label %$endLabel")
        terminated = true

        startBlock(bodyLabel)
        breakTargets.add(endLabel)
        continueTargets.add(headLabel)
        emitStmt(stmt.body)
        breakTargets.removeAt(breakTargets.size - 1)
        continueTargets.removeAt(continueTargets.size - 1)
        branch(headLabel)

        startBlock(endLabel)
    }

    private fun emitFor(stmt: ForStmt) {
        val type = stmt.inductionType
        val llvmType = types.value(type)
        val start = emitExpr(stmt.start)
        val end = emitExpr(stmt.end)

        val counter = allocate(type, stmt.varName)
        storeTo(counter, start)

        val headLabel = nextLabel()
        val bodyLabel = nextLabel()
        val stepLabel = nextLabel()
        val endLabel = nextLabel()

        branch(headLabel)
        startBlock(headLabel)
        val current = loadFrom(counter)
        val signed = (type as? IntType)?.signed ?: true
        val predicate = when {
            stmt.inclusive && signed -> "sle"
            stmt.inclusive -> "ule"
            signed -> "slt"
            else -> "ult"
        }
        val test = nextTemp()
        line("$test = icmp $predicate $llvmType ${current.ref}, ${end.ref}")
        line("br i1 $test, label %$bodyLabel, label %$endLabel")
        terminated = true

        startBlock(bodyLabel)
        inductionValues[stmt] = current
        breakTargets.add(endLabel)
        continueTargets.add(stepLabel)
        emitStmt(stmt.body)
        breakTargets.removeAt(breakTargets.size - 1)
        continueTargets.removeAt(continueTargets.size - 1)
        branch(stepLabel)

        startBlock(stepLabel)
        val reloaded = loadFrom(counter)
        val incremented = nextTemp()
        line("$incremented = add $llvmType ${reloaded.ref}, 1")
        storeTo(counter, Val(type, incremented))
        branch(headLabel)

        startBlock(endLabel)
    }

    private fun emitLoop(stmt: LoopStmt) {
        val headLabel = nextLabel()
        val endLabel = nextLabel()

        branch(headLabel)
        startBlock(headLabel)
        breakTargets.add(endLabel)
        continueTargets.add(headLabel)
        emitStmt(stmt.body)
        breakTargets.removeAt(breakTargets.size - 1)
        continueTargets.removeAt(continueTargets.size - 1)
        branch(headLabel)

        startBlock(endLabel)
    }

    private fun emitSwitch(stmt: SwitchStmt) {
        val subject = emitExpr(stmt.subject)
        val subjectType = types.value(stmt.subject.resolvedType)
        val endLabel = nextLabel()
        val caseLabels = stmt.cases.map { nextLabel() }
        val defaultLabel = if (stmt.elseBlock != null) nextLabel() else endLabel

        body.append("  switch ").append(subjectType).append(' ').append(subject.ref)
            .append(", label %").append(defaultLabel).append(" [\n")
        for ((index, case) in stmt.cases.withIndex()) {
            for (constant in case.constants) {
                body.append("    ").append(subjectType).append(' ').append(constant)
                    .append(", label %").append(caseLabels[index]).append('\n')
            }
        }
        body.append("  ]\n")
        terminated = true

        breakTargets.add(endLabel)
        for ((index, case) in stmt.cases.withIndex()) {
            startBlock(caseLabels[index])
            emitStmt(case.body)
            branch(endLabel)
        }
        if (stmt.elseBlock != null) {
            startBlock(defaultLabel)
            emitStmt(stmt.elseBlock)
            branch(endLabel)
        }
        breakTargets.removeAt(breakTargets.size - 1)

        startBlock(endLabel)
    }

    private fun emitReturn(stmt: ReturnStmt) {
        if (stmt.value == null) {
            line("ret void")
        } else {
            val value = emitExpr(stmt.value)
            line("ret ${types.value(value.type)} ${value.ref}")
        }
        terminated = true
    }

    private fun emitExpr(expr: Expr): Val = when (expr) {
        is LiteralInt -> Val(expr.resolvedType, literalInt(expr))
        is LiteralFloat -> Val(expr.resolvedType, literalFloat(expr.value, expr.resolvedType))
        is LiteralBool -> Val(BoolType, if (expr.value) "true" else "false")
        is VarRef -> emitVarRef(expr)
        is BinOp -> binary(expr.op, emitExpr(expr.left), emitExpr(expr.right), expr.resolvedType, expr.loc)
        is LogicalOp -> emitLogical(expr)
        is UnaryOp -> emitUnary(expr)
        is Ternary -> emitTernary(expr)
        is CastExpr -> convert(emitExpr(expr.expr), expr.targetType)
        is Call -> emitCall(expr)
        is StructLit -> emitStructLit(expr)
        is Access -> emitAccess(expr)
        is Index -> emitIndex(expr)
        is AddrOf -> Val(expr.resolvedType, requirePlace(expr.operand).ptr)
        is Deref -> loadFrom(requirePlace(expr))
    }

    private fun literalInt(expr: LiteralInt): String {
        val type = expr.resolvedType
        if (type.elementOrSelf.isFloat) return literalFloat(expr.value.toDouble(), type)
        if (type is VectorType) return splatConstant(type, expr.value.toString())
        return expr.value.toString()
    }

    private fun literalFloat(value: Double, type: Type): String {
        val element = type.elementOrSelf
        val normalized = if (element == Types.F32 || element == Types.F16) value.toFloat().toDouble() else value
        val text = "0x" + normalized.toRawBits().toULong().toString(16).uppercase().padStart(16, '0')
        if (type is VectorType) return splatConstant(type, text)
        return text
    }

    private fun splatConstant(type: VectorType, element: String): String =
        "<" + (0 until type.lanes).joinToString(", ") { "${types.value(type.elem)} $element" } + ">"

    private fun emitVarRef(expr: VarRef): Val = when (val binding = expr.binding) {
        is Binding.Local -> loadFrom(slots.getValue(binding.decl))
        is Binding.Parameter -> paramValues.getValue(binding.param.name)
        is Binding.Induction -> inductionValues.getValue(binding.stmt)
        is Binding.Constant -> {
            val type = binding.def.resolvedType
            val value = binding.def.evaluated
            Val(
                type,
                when {
                    value == null -> "undef"
                    type.isFloat -> literalFloat(value.asDouble, type)
                    type == BoolType -> if (value.asLong != 0L) "true" else "false"
                    else -> value.asLong.toString()
                },
            )
        }

        null -> Val(expr.resolvedType, "undef")
    }

    private fun emitLogical(expr: LogicalOp): Val {
        val left = emitExpr(expr.left)
        val rhsLabel = nextLabel()
        val mergeLabel = nextLabel()
        val entryLabel = currentLabel

        if (expr.op == TokenType.AND) {
            line("br i1 ${left.ref}, label %$rhsLabel, label %$mergeLabel")
        } else {
            line("br i1 ${left.ref}, label %$mergeLabel, label %$rhsLabel")
        }
        terminated = true

        startBlock(rhsLabel)
        val right = emitExpr(expr.right)
        val rhsExit = currentLabel
        branch(mergeLabel)

        startBlock(mergeLabel)
        val result = nextTemp()
        val shortCircuit = if (expr.op == TokenType.AND) "false" else "true"
        line("$result = phi i1 [ $shortCircuit, %$entryLabel ], [ ${right.ref}, %$rhsExit ]")
        return Val(BoolType, result)
    }

    private fun emitTernary(expr: Ternary): Val {
        val condition = emitExpr(expr.cond)
        val trueLabel = nextLabel()
        val falseLabel = nextLabel()
        val mergeLabel = nextLabel()

        line("br i1 ${condition.ref}, label %$trueLabel, label %$falseLabel")
        terminated = true

        startBlock(trueLabel)
        val trueValue = emitExpr(expr.ifTrue)
        val trueExit = currentLabel
        branch(mergeLabel)

        startBlock(falseLabel)
        val falseValue = emitExpr(expr.ifFalse)
        val falseExit = currentLabel
        branch(mergeLabel)

        startBlock(mergeLabel)
        val result = nextTemp()
        line(
            "$result = phi ${types.value(expr.resolvedType)} " +
                "[ ${trueValue.ref}, %$trueExit ], [ ${falseValue.ref}, %$falseExit ]",
        )
        return Val(expr.resolvedType, result)
    }

    private fun emitUnary(expr: UnaryOp): Val {
        val operand = emitExpr(expr.operand)
        val type = expr.resolvedType
        val llvmType = types.value(type)
        val result = nextTemp()
        when (expr.op) {
            TokenType.BANG -> line("$result = xor i1 ${operand.ref}, true")
            TokenType.TILDE -> line("$result = xor $llvmType ${operand.ref}, ${allOnes(type)}")
            TokenType.MINUS -> if (type.elementOrSelf.isFloat) {
                line("$result = fneg $llvmType ${operand.ref}")
            } else {
                line("$result = sub $llvmType ${integerZero(type)}, ${operand.ref}")
            }

            else -> line("$result = freeze $llvmType ${operand.ref}")
        }
        return Val(type, result)
    }

    private fun allOnes(type: Type): String =
        if (type is VectorType) splatConstant(type, "-1") else "-1"

    private fun integerZero(type: Type): String =
        if (type is VectorType) splatConstant(type, "0") else "0"

    private fun convert(source: Val, target: Type): Val {
        if (source.type == target) return source

        val from = source.type
        val fromElement = from.elementOrSelf
        val toElement = target.elementOrSelf
        val fromLlvm = types.value(from)
        val toLlvm = types.value(target)

        if (from is SliceType && target is PtrType) {
            val result = nextTemp()
            line("$result = extractvalue $fromLlvm ${source.ref}, 0")
            return Val(target, result)
        }
        if (from is SliceType && target is SliceType) {
            val pointer = nextTemp()
            line("$pointer = extractvalue $fromLlvm ${source.ref}, 0")
            val length = nextTemp()
            line("$length = extractvalue $fromLlvm ${source.ref}, 1")

            val fromWidth = model.layout.sizeOf(from.elem)
            val toWidth = model.layout.sizeOf(target.elem)
            val scaled = when {
                fromWidth == toWidth -> length
                fromWidth > toWidth -> {
                    val wider = nextTemp()
                    line("$wider = mul i32 $length, ${fromWidth / toWidth}")
                    wider
                }

                else -> {
                    val narrower = nextTemp()
                    line("$narrower = udiv i32 $length, ${toWidth / fromWidth}")
                    narrower
                }
            }

            val withPtr = nextTemp()
            line("$withPtr = insertvalue $toLlvm undef, ${types.pointer(target.space)} $pointer, 0")
            val withLen = nextTemp()
            line("$withLen = insertvalue $toLlvm $withPtr, i32 $scaled, 1")
            return Val(target, withLen)
        }
        if (from is PtrType && target is PtrType) return Val(target, source.ref)
        if (fromElement.isInt && toElement == BoolType) {
            val result = nextTemp()
            line("$result = icmp ne $fromLlvm ${source.ref}, ${integerZero(from)}")
            return Val(target, result)
        }

        val operation = when {
            fromElement == BoolType && toElement.isInt -> "zext"
            fromElement == BoolType && toElement.isFloat -> "uitofp"
            fromElement.isInt && toElement.isInt -> when {
                fromElement.scalarBits > toElement.scalarBits -> "trunc"
                fromElement.scalarBits < toElement.scalarBits ->
                    if ((fromElement as IntType).signed) "sext" else "zext"

                else -> return Val(target, source.ref)
            }

            fromElement.isInt && toElement.isFloat ->
                if ((fromElement as IntType).signed) "sitofp" else "uitofp"

            fromElement.isFloat && toElement.isInt ->
                if ((toElement as IntType).signed) "fptosi" else "fptoui"

            fromElement.isFloat && toElement.isFloat ->
                if (fromElement.scalarBits > toElement.scalarBits) "fptrunc" else "fpext"

            else -> {
                ctx.report(Level.ERROR, "cannot lower a cast from '${from.name}' to '${target.name}'", null)
                return Val(target, "undef")
            }
        }

        val result = nextTemp()
        line("$result = $operation $fromLlvm ${source.ref} to $toLlvm")
        return Val(target, result)
    }

    private fun binary(op: TokenType, leftInput: Val, rightInput: Val, resultType: Type, loc: Token): Val {
        val comparison = op == TokenType.EQEQ || op == TokenType.NEQ ||
            op == TokenType.LESS || op == TokenType.LESS_EQ ||
            op == TokenType.GREATER || op == TokenType.GREATER_EQ
        val shift = op == TokenType.LSHIFT || op == TokenType.RSHIFT

        val operandType = when {
            shift -> leftInput.type
            comparison -> if (leftInput.type is VectorType) leftInput.type else rightInput.type
            else -> resultType
        }

        val left = splatIfNeeded(leftInput, operandType)
        val right = splatIfNeeded(rightInput, operandType)

        val element = operandType.elementOrSelf
        val llvmType = types.value(operandType)
        val signed = (element as? IntType)?.signed ?: true
        val isFloat = element.isFloat
        val result = nextTemp()

        val instruction = when (op) {
            TokenType.PLUS -> if (isFloat) "fadd" else "add"
            TokenType.MINUS -> if (isFloat) "fsub" else "sub"
            TokenType.STAR -> if (isFloat) "fmul" else "mul"
            TokenType.SLASH -> if (isFloat) "fdiv" else if (signed) "sdiv" else "udiv"
            TokenType.PERCENT -> if (signed) "srem" else "urem"
            TokenType.AMP -> "and"
            TokenType.PIPE -> "or"
            TokenType.CARET -> "xor"
            TokenType.LSHIFT -> "shl"
            TokenType.RSHIFT -> if (signed) "ashr" else "lshr"
            else -> null
        }

        if (instruction != null) {
            line("$result = $instruction $llvmType ${left.ref}, ${right.ref}")
            return Val(operandType, result)
        }

        val predicate = when (op) {
            TokenType.EQEQ -> if (isFloat) "oeq" else "eq"
            TokenType.NEQ -> if (isFloat) "une" else "ne"
            TokenType.LESS -> if (isFloat) "olt" else if (signed) "slt" else "ult"
            TokenType.LESS_EQ -> if (isFloat) "ole" else if (signed) "sle" else "ule"
            TokenType.GREATER -> if (isFloat) "ogt" else if (signed) "sgt" else "ugt"
            TokenType.GREATER_EQ -> if (isFloat) "oge" else if (signed) "sge" else "uge"
            else -> {
                ctx.report(Level.ERROR, "cannot lower operator '${op.text}'", loc)
                return Val(resultType, "undef")
            }
        }

        line("$result = ${if (isFloat) "fcmp" else "icmp"} $predicate $llvmType ${left.ref}, ${right.ref}")
        return Val(BoolType, result)
    }

    private fun splatIfNeeded(value: Val, target: Type): Val {
        if (target !is VectorType || value.type is VectorType) return value
        val elementType = types.value(target.elem)
        val vectorType = types.value(target)
        val inserted = nextTemp()
        line("$inserted = insertelement $vectorType undef, $elementType ${value.ref}, i32 0")
        val splatted = nextTemp()
        line(
            "$splatted = shufflevector $vectorType $inserted, $vectorType undef, " +
                "<${target.lanes} x i32> <" + (0 until target.lanes).joinToString(", ") { "i32 0" } + ">",
        )
        return Val(target, splatted)
    }

    private fun emitStructLit(lit: StructLit): Val {
        val type = StructType(lit.typeName)
        val llvmType = types.value(type)
        var current = "undef"
        for ((index, arg) in lit.args.withIndex()) {
            val value = storageValue(emitExpr(arg))
            val next = nextTemp()
            line("$next = insertvalue $llvmType $current, ${types.storage(arg.resolvedType)} ${value.ref}, $index")
            current = next
        }
        return Val(type, current)
    }

    private fun emitAccess(access: Access): Val {
        if (access.kind == AccessKind.SliceLength) {
            val slice = emitExpr(access.target)
            val result = nextTemp()
            line("$result = extractvalue ${types.value(slice.type)} ${slice.ref}, 1")
            return Val(Types.U32, result)
        }

        if (access.kind == AccessKind.Swizzle) {
            val vector = emitExpr(access.target)
            val vectorType = vector.type as VectorType
            if (access.swizzle.size == 1) {
                val result = nextTemp()
                line("$result = extractelement ${types.value(vectorType)} ${vector.ref}, i32 ${access.swizzle[0]}")
                return Val(vectorType.elem, result)
            }
            val mask = "<${access.swizzle.size} x i32> <" +
                access.swizzle.joinToString(", ") { "i32 $it" } + ">"
            val result = nextTemp()
            line(
                "$result = shufflevector ${types.value(vectorType)} ${vector.ref}, " +
                    "${types.value(vectorType)} undef, $mask",
            )
            return Val(access.resolvedType, result)
        }

        if (isPlaceable(access)) return loadFrom(requirePlace(access))

        val target = emitExpr(access.target)
        val result = nextTemp()
        line("$result = extractvalue ${types.value(target.type)} ${target.ref}, ${access.fieldIndex}")
        return materialize(Val(access.resolvedType, result))
    }

    private fun emitIndex(index: Index): Val {
        if (isPlaceable(index)) return loadFrom(requirePlace(index))
        val target = emitExpr(index.target)
        val indexValue = emitExpr(index.index)
        if (target.type is VectorType) {
            val result = nextTemp()
            line(
                "$result = extractelement ${types.value(target.type)} ${target.ref}, " +
                    "${types.value(indexValue.type)} ${indexValue.ref}",
            )
            return Val(index.resolvedType, result)
        }
        ctx.report(Level.ERROR, "cannot index '${target.type.name}'", index.loc)
        return Val(index.resolvedType, "undef")
    }

    private fun isPlaceable(expr: Expr): Boolean = when (expr) {
        is VarRef -> expr.binding is Binding.Local
        is Deref -> true
        is Index -> when (expr.target.resolvedType) {
            is SliceType, is PtrType -> true
            is ArrayType, is VectorType -> isPlaceable(expr.target)
            else -> false
        }

        is Access -> when (expr.kind) {
            AccessKind.Field -> isPlaceable(expr.target)
            AccessKind.Swizzle -> expr.swizzle.size == 1 && isPlaceable(expr.target)
            else -> false
        }

        else -> false
    }

    private fun requirePlace(expr: Expr): Place {
        when (expr) {
            is VarRef -> (expr.binding as? Binding.Local)?.let { return slots.getValue(it.decl) }

            is Deref -> {
                val pointer = emitExpr(expr.operand)
                val pointerType = pointer.type as PtrType
                return Place(pointerType.pointee, pointer.ref, types.pointer(pointerType.space))
            }

            is Index -> placeOfIndex(expr)?.let { return it }

            is Access -> if (expr.kind == AccessKind.Field || expr.swizzle.size == 1) {
                val base = requirePlace(expr.target)
                val fieldIndex = if (expr.kind == AccessKind.Field) expr.fieldIndex else expr.swizzle[0]
                val sourceType = if (expr.kind == AccessKind.Field) {
                    types.storage(base.type)
                } else {
                    types.value(base.type)
                }
                val result = nextTemp()
                line("$result = getelementptr inbounds $sourceType, ${base.ptrType} ${base.ptr}, i32 0, i32 $fieldIndex")
                return Place(expr.resolvedType, result, base.ptrType)
            }

            else -> {}
        }

        ctx.report(Level.ERROR, "this expression does not name storage", expr.loc)
        return Place(expr.resolvedType, "undef", types.pointer(AddressSpace.Private))
    }

    private fun placeOfIndex(index: Index): Place? {
        val containerType = index.target.resolvedType

        return when (containerType) {
            is SliceType -> {
                val slice = emitExpr(index.target)
                val indexValue = emitExpr(index.index)
                val pointer = nextTemp()
                line("$pointer = extractvalue ${types.value(containerType)} ${slice.ref}, 0")
                if (boundsChecks) emitBoundsCheck(slice, indexValue)
                val element = nextTemp()
                line(
                    "$element = getelementptr inbounds ${types.storage(containerType.elem)}, " +
                        "${types.pointer(containerType.space)} $pointer, i64 ${extendIndex(indexValue)}",
                )
                Place(containerType.elem, element, types.pointer(containerType.space))
            }

            is PtrType -> {
                val pointer = emitExpr(index.target)
                val indexValue = emitExpr(index.index)
                val element = nextTemp()
                line(
                    "$element = getelementptr inbounds ${types.storage(containerType.pointee)}, " +
                        "${types.pointer(containerType.space)} ${pointer.ref}, i64 ${extendIndex(indexValue)}",
                )
                Place(containerType.pointee, element, types.pointer(containerType.space))
            }

            is ArrayType -> {
                val base = requirePlace(index.target)
                val indexValue = emitExpr(index.index)
                val element = nextTemp()
                line(
                    "$element = getelementptr inbounds ${types.storage(containerType)}, " +
                        "${base.ptrType} ${base.ptr}, i64 0, i64 ${extendIndex(indexValue)}",
                )
                Place(containerType.elem, element, base.ptrType)
            }

            is VectorType -> {
                val base = requirePlace(index.target)
                val indexValue = emitExpr(index.index)
                val element = nextTemp()
                line(
                    "$element = getelementptr inbounds ${types.value(containerType)}, " +
                        "${base.ptrType} ${base.ptr}, i64 0, i64 ${extendIndex(indexValue)}",
                )
                Place(containerType.elem, element, base.ptrType)
            }

            else -> null
        }
    }

    private fun extendIndex(value: Val): String {
        if (value.type.scalarBits == 64) return value.ref
        val extended = nextTemp()
        val signed = (value.type as? IntType)?.signed ?: false
        line("$extended = ${if (signed) "sext" else "zext"} ${types.value(value.type)} ${value.ref} to i64")
        return extended
    }

    private fun emitBoundsCheck(slice: Val, index: Val) {
        val length = nextTemp()
        line("$length = extractvalue ${types.value(slice.type)} ${slice.ref}, 1")

        val bits = index.type.scalarBits
        val narrowed = if (bits == 32) index.ref else {
            val converted = nextTemp()
            if (bits > 32) {
                line("$converted = trunc ${types.value(index.type)} ${index.ref} to i32")
            } else {
                val signed = (index.type as? IntType)?.signed ?: false
                line("$converted = ${if (signed) "sext" else "zext"} ${types.value(index.type)} ${index.ref} to i32")
            }
            converted
        }

        val inRange = nextTemp()
        line("$inRange = icmp ult i32 $narrowed, $length")
        val okLabel = nextLabel()
        val trapLabel = nextLabel()
        line("br i1 $inRange, label %$okLabel, label %$trapLabel")
        terminated = true

        startBlock(trapLabel)
        declarations.add("declare void @llvm.trap()")
        line("call void @llvm.trap()")
        line("unreachable")
        terminated = true

        startBlock(okLabel)
    }

    private fun loadFrom(place: Place): Val {
        val result = nextTemp()
        line(
            "$result = load ${types.storage(place.type)}, ${place.ptrType} ${place.ptr}, " +
                "align ${model.layout.alignOf(place.type).coerceAtLeast(1)}",
        )
        return materialize(Val(place.type, result))
    }

    private fun storeTo(place: Place, value: Val) {
        val stored = storageValue(value)
        line(
            "store ${types.storage(place.type)} ${stored.ref}, ${place.ptrType} ${place.ptr}, " +
                "align ${model.layout.alignOf(place.type).coerceAtLeast(1)}",
        )
    }

    private fun storageValue(value: Val): Val {
        if (value.type != BoolType) return value
        val extended = nextTemp()
        line("$extended = zext i1 ${value.ref} to i8")
        return Val(value.type, extended)
    }

    private fun materialize(value: Val): Val {
        if (value.type != BoolType) return value
        val truncated = nextTemp()
        line("$truncated = trunc i8 ${value.ref} to i1")
        return Val(value.type, truncated)
    }

    private fun emitCall(call: Call): Val = when (val target = call.target) {
        is CallTarget.Builtin -> emitBuiltin(call, target.builtin)
        is CallTarget.User -> emitUserCall(call, target.fn)
        is CallTarget.External -> emitExternalCall(call, target.fn)
        is CallTarget.VectorSplat -> splatIfNeeded(emitExpr(call.args[0]), target.type)
        is CallTarget.VectorBuild -> emitVectorBuild(call, target.type)
        is CallTarget.ScalarConvert -> convert(emitExpr(call.args[0]), target.type)
        CallTarget.Unresolved -> Val(call.resolvedType, "undef")
    }

    private fun emitVectorBuild(call: Call, type: VectorType): Val {
        val llvmType = types.value(type)
        val elementType = types.value(type.elem)
        var current = "undef"
        for ((index, arg) in call.args.withIndex()) {
            val value = emitExpr(arg)
            val next = nextTemp()
            line("$next = insertelement $llvmType $current, $elementType ${value.ref}, i32 $index")
            current = next
        }
        return Val(type, current)
    }

    private fun emitUserCall(call: Call, fn: FnDef): Val {
        val arguments = ArrayList<String>()
        if (!profile.hardwareDispatch && fn in dispatchUsers) {
            arguments.add("ptr ${dispatchArg ?: "null"}")
        }
        for ((index, arg) in call.args.withIndex()) {
            appendArgument(arg, fn.params[index].type, arguments)
        }
        return emitCallInstruction(fn.mangledName, arguments, fn.retType)
    }

    private fun emitExternalCall(call: Call, fn: ExternFnDef): Val {
        val arguments = ArrayList<String>()
        for ((index, arg) in call.args.withIndex()) {
            appendArgument(arg, fn.params[index].type, arguments)
        }
        return emitCallInstruction(fn.name, arguments, fn.retType)
    }

    private fun appendArgument(arg: Expr, expected: Type, out: MutableList<String>) {
        if (expected is SliceType) {
            val value = if (arg.resolvedType is ArrayType) sliceFromArray(arg, expected) else emitExpr(arg)
            val pointer = nextTemp()
            line("$pointer = extractvalue ${types.value(expected)} ${value.ref}, 0")
            val length = nextTemp()
            line("$length = extractvalue ${types.value(expected)} ${value.ref}, 1")
            out.add("${types.pointer(expected.space)} $pointer")
            out.add("i32 $length")
            return
        }
        val value = emitExpr(arg)
        out.add("${types.value(expected)} ${value.ref}")
    }

    private fun emitCallInstruction(name: String, arguments: List<String>, retType: Type): Val {
        if (retType == VoidType) {
            line("call void @$name(${arguments.joinToString(", ")})")
            return Val(VoidType, "")
        }
        val result = nextTemp()
        line("$result = call ${types.value(retType)} @$name(${arguments.joinToString(", ")})")
        return Val(retType, result)
    }

    private fun emitBuiltin(call: Call, builtin: Builtin): Val = when (builtin.group) {
        BuiltinGroup.Dispatch -> emitDispatch(builtin)
        BuiltinGroup.Sync -> emitSync(builtin)
        else -> emitBuiltinWithArgs(call, builtin)
    }

    private fun emitBuiltinWithArgs(call: Call, builtin: Builtin): Val {
        val args = call.args.map { emitExpr(it) }
        val type = call.resolvedType
        val first = args[0]
        val element = first.type.elementOrSelf
        val isFloat = element.isFloat
        val signed = (element as? IntType)?.signed ?: true

        return when (builtin) {
            Builtin.Sqrt -> intrinsic("llvm.sqrt", first.type, args)
            Builtin.Floor -> intrinsic("llvm.floor", first.type, args)
            Builtin.Ceil -> intrinsic("llvm.ceil", first.type, args)
            Builtin.Round -> intrinsic("llvm.round", first.type, args)
            Builtin.Trunc -> intrinsic("llvm.trunc", first.type, args)
            Builtin.Sin -> intrinsic("llvm.sin", first.type, args)
            Builtin.Cos -> intrinsic("llvm.cos", first.type, args)
            Builtin.Tan -> intrinsic("llvm.tan", first.type, args)
            Builtin.Exp -> intrinsic("llvm.exp", first.type, args)
            Builtin.Exp2 -> intrinsic("llvm.exp2", first.type, args)
            Builtin.Log -> intrinsic("llvm.log", first.type, args)
            Builtin.Log2 -> intrinsic("llvm.log2", first.type, args)
            Builtin.Pow -> intrinsic("llvm.pow", first.type, args)
            Builtin.Atan2 -> intrinsic("llvm.atan2", first.type, args)
            Builtin.Fma -> intrinsic("llvm.fma", first.type, args)
            Builtin.PopCount -> intrinsic("llvm.ctpop", first.type, args)
            Builtin.BitReverse -> intrinsic("llvm.bitreverse", first.type, args)
            Builtin.ByteSwap -> if (element.scalarBits <= 8) first else intrinsic("llvm.bswap", first.type, args)

            Builtin.Rsqrt -> {
                val root = intrinsic("llvm.sqrt", first.type, listOf(first))
                arithmetic("fdiv", first.type, constantOf(first.type, 1.0), root)
            }

            Builtin.Fract -> {
                val floor = intrinsic("llvm.floor", first.type, listOf(first))
                arithmetic("fsub", first.type, first, floor)
            }

            Builtin.FMod -> arithmetic("frem", first.type, first, args[1])

            Builtin.CountLeadingZeros -> intrinsicWithFlag("llvm.ctlz", first.type, first)
            Builtin.CountTrailingZeros -> intrinsicWithFlag("llvm.cttz", first.type, first)

            Builtin.Abs -> when {
                isFloat -> intrinsic("llvm.fabs", first.type, args)
                !signed -> first
                else -> intrinsicWithFlag("llvm.abs", first.type, first)
            }

            Builtin.Min -> when {
                isFloat -> intrinsic("llvm.minnum", first.type, args)
                signed -> intrinsic("llvm.smin", first.type, args)
                else -> intrinsic("llvm.umin", first.type, args)
            }

            Builtin.Max -> when {
                isFloat -> intrinsic("llvm.maxnum", first.type, args)
                signed -> intrinsic("llvm.smax", first.type, args)
                else -> intrinsic("llvm.umax", first.type, args)
            }

            Builtin.Clamp -> clamp(first, args[1], args[2], isFloat, signed)

            Builtin.Sign -> emitSign(first, isFloat, signed)

            Builtin.Mix -> {
                val delta = arithmetic("fsub", first.type, args[1], first)
                val scaled = arithmetic("fmul", first.type, delta, args[2])
                arithmetic("fadd", first.type, first, scaled)
            }

            Builtin.Step -> {
                val test = compare("fcmp", "olt", first.type, args[1], first)
                select(test, constantOf(first.type, 0.0), constantOf(first.type, 1.0), first.type)
            }

            Builtin.SmoothStep -> emitSmoothStep(first, args[1], args[2])

            Builtin.RotateLeft -> intrinsic("llvm.fshl", first.type, listOf(first, first, args[1]))
            Builtin.RotateRight -> intrinsic("llvm.fshr", first.type, listOf(first, first, args[1]))
            Builtin.MulHigh -> emitMulHigh(first, args[1], signed)

            Builtin.BitFieldExtract -> {
                val shifted = arithmetic("lshr", first.type, first, args[1])
                val one = Val(first.type, oneOf(first.type))
                val span = arithmetic("shl", first.type, one, args[2])
                val mask = arithmetic("sub", first.type, span, one)
                arithmetic("and", first.type, shifted, mask)
            }

            Builtin.Dot -> emitDot(first, args[1])
            Builtin.Length -> {
                val squared = emitDot(first, first)
                intrinsic("llvm.sqrt", squared.type, listOf(squared))
            }

            Builtin.Distance -> {
                val delta = arithmetic("fsub", first.type, first, args[1])
                val squared = emitDot(delta, delta)
                intrinsic("llvm.sqrt", squared.type, listOf(squared))
            }

            Builtin.Normalize -> {
                val squared = emitDot(first, first)
                val magnitude = intrinsic("llvm.sqrt", squared.type, listOf(squared))
                arithmetic("fdiv", first.type, first, splatIfNeeded(magnitude, first.type))
            }

            Builtin.Cross -> emitCross(first, args[1])

            Builtin.FloatBits, Builtin.BitsFloat, Builtin.DoubleBits, Builtin.BitsDouble -> {
                val result = nextTemp()
                line("$result = bitcast ${types.value(first.type)} ${first.ref} to ${types.value(type)}")
                Val(type, result)
            }

            Builtin.Select -> select(first, args[1], args[2], type)

            else -> {
                ctx.report(Level.ERROR, "builtin '${builtin.fnName}' has no lowering", call.loc)
                Val(type, "undef")
            }
        }
    }

    private fun clamp(value: Val, low: Val, high: Val, isFloat: Boolean, signed: Boolean): Val {
        val minName = if (isFloat) "llvm.minnum" else if (signed) "llvm.smin" else "llvm.umin"
        val maxName = if (isFloat) "llvm.maxnum" else if (signed) "llvm.smax" else "llvm.umax"
        val lifted = intrinsic(maxName, value.type, listOf(value, low))
        return intrinsic(minName, value.type, listOf(lifted, high))
    }

    private fun emitSign(value: Val, isFloat: Boolean, signed: Boolean): Val {
        val type = value.type
        val zero = if (isFloat) constantOf(type, 0.0) else Val(type, integerZero(type))
        val one = if (isFloat) constantOf(type, 1.0) else Val(type, oneOf(type))
        if (!isFloat && !signed) {
            val nonZero = compare("icmp", "ne", type, value, zero)
            return select(nonZero, one, zero, type)
        }
        val minusOne = if (isFloat) constantOf(type, -1.0) else Val(type, allOnes(type))
        val positive = compare(if (isFloat) "fcmp" else "icmp", if (isFloat) "ogt" else "sgt", type, value, zero)
        val negative = compare(if (isFloat) "fcmp" else "icmp", if (isFloat) "olt" else "slt", type, value, zero)
        val lower = select(negative, minusOne, zero, type)
        return select(positive, one, lower, type)
    }

    private fun emitSmoothStep(edge0: Val, edge1: Val, value: Val): Val {
        val type = edge0.type
        val numerator = arithmetic("fsub", type, value, edge0)
        val denominator = arithmetic("fsub", type, edge1, edge0)
        val ratio = arithmetic("fdiv", type, numerator, denominator)
        val clamped = clamp(ratio, constantOf(type, 0.0), constantOf(type, 1.0), true, true)
        val doubled = arithmetic("fmul", type, constantOf(type, 2.0), clamped)
        val tail = arithmetic("fsub", type, constantOf(type, 3.0), doubled)
        val squared = arithmetic("fmul", type, clamped, clamped)
        return arithmetic("fmul", type, squared, tail)
    }

    private fun emitMulHigh(left: Val, right: Val, signed: Boolean): Val {
        val type = left.type
        val element = type.elementOrSelf as IntType
        val wideElement = IntType(element.bits * 2, element.signed)
        val wideType = if (type is VectorType) VectorType(wideElement, type.lanes) else wideElement
        val extend = if (signed) "sext" else "zext"

        val wideLeft = nextTemp()
        line("$wideLeft = $extend ${types.value(type)} ${left.ref} to ${types.value(wideType)}")
        val wideRight = nextTemp()
        line("$wideRight = $extend ${types.value(type)} ${right.ref} to ${types.value(wideType)}")
        val product = arithmetic("mul", wideType, Val(wideType, wideLeft), Val(wideType, wideRight))
        val shifted = nextTemp()
        val shiftAmount = if (wideType is VectorType) {
            splatConstant(wideType, element.bits.toString())
        } else {
            element.bits.toString()
        }
        line("$shifted = lshr ${types.value(wideType)} ${product.ref}, $shiftAmount")
        val narrowed = nextTemp()
        line("$narrowed = trunc ${types.value(wideType)} $shifted to ${types.value(type)}")
        return Val(type, narrowed)
    }

    private fun emitDot(left: Val, right: Val): Val {
        val type = left.type as VectorType
        val products = arithmetic("fmul", type, left, right)
        var accumulator: Val? = null
        for (lane in 0 until type.lanes) {
            val extracted = nextTemp()
            line("$extracted = extractelement ${types.value(type)} ${products.ref}, i32 $lane")
            val current = Val(type.elem, extracted)
            accumulator = if (accumulator == null) current else arithmetic("fadd", type.elem, accumulator, current)
        }
        return accumulator ?: constantOf(type.elem, 0.0)
    }

    private fun emitCross(left: Val, right: Val): Val {
        val type = left.type as VectorType
        val leftYzx = shuffle(left, intArrayOf(1, 2, 0))
        val leftZxy = shuffle(left, intArrayOf(2, 0, 1))
        val rightYzx = shuffle(right, intArrayOf(1, 2, 0))
        val rightZxy = shuffle(right, intArrayOf(2, 0, 1))
        val head = arithmetic("fmul", type, leftYzx, rightZxy)
        val tail = arithmetic("fmul", type, leftZxy, rightYzx)
        return arithmetic("fsub", type, head, tail)
    }

    private fun shuffle(value: Val, mask: IntArray): Val {
        val llvmType = types.value(value.type)
        val result = nextTemp()
        line(
            "$result = shufflevector $llvmType ${value.ref}, $llvmType undef, " +
                "<${mask.size} x i32> <" + mask.joinToString(", ") { "i32 $it" } + ">",
        )
        return Val(value.type, result)
    }

    private fun arithmetic(instruction: String, type: Type, left: Val, right: Val): Val {
        val result = nextTemp()
        line("$result = $instruction ${types.value(type)} ${left.ref}, ${right.ref}")
        return Val(type, result)
    }

    private fun compare(instruction: String, predicate: String, type: Type, left: Val, right: Val): Val {
        val result = nextTemp()
        line("$result = $instruction $predicate ${types.value(type)} ${left.ref}, ${right.ref}")
        return Val(if (type is VectorType) VectorType(BoolType, type.lanes) else BoolType, result)
    }

    private fun select(condition: Val, ifTrue: Val, ifFalse: Val, type: Type): Val {
        val result = nextTemp()
        line(
            "$result = select ${types.value(condition.type)} ${condition.ref}, " +
                "${types.value(type)} ${ifTrue.ref}, ${types.value(type)} ${ifFalse.ref}",
        )
        return Val(type, result)
    }

    private fun constantOf(type: Type, value: Double): Val = Val(type, literalFloat(value, type))

    private fun oneOf(type: Type): String =
        if (type is VectorType) splatConstant(type, "1") else "1"

    private fun intrinsic(name: String, type: Type, args: List<Val>): Val {
        val suffix = intrinsicSuffix(type)
        val llvmType = types.value(type)
        val fullName = "$name.$suffix"
        declarations.add("declare $llvmType @$fullName(${args.joinToString(", ") { llvmType }})")
        val result = nextTemp()
        line("$result = call $llvmType @$fullName(${args.joinToString(", ") { "$llvmType ${it.ref}" }})")
        return Val(type, result)
    }

    private fun intrinsicWithFlag(name: String, type: Type, value: Val): Val {
        val suffix = intrinsicSuffix(type)
        val llvmType = types.value(type)
        val fullName = "$name.$suffix"
        declarations.add("declare $llvmType @$fullName($llvmType, i1)")
        val result = nextTemp()
        line("$result = call $llvmType @$fullName($llvmType ${value.ref}, i1 false)")
        return Val(type, result)
    }

    private fun intrinsicSuffix(type: Type): String = when (type) {
        is VectorType -> "v${type.lanes}${scalarSuffix(type.elem)}"
        else -> scalarSuffix(type)
    }

    private fun scalarSuffix(type: Type): String = when (type) {
        is FloatType -> "f${type.bits}"
        is IntType -> "i${type.bits}"
        else -> "i32"
    }

    private fun emitDispatch(builtin: Builtin): Val {
        val result = when (builtin) {
            Builtin.GlobalIdX -> return globalId(0)
            Builtin.GlobalIdY -> return globalId(1)
            Builtin.GlobalIdZ -> return globalId(2)
            Builtin.SubgroupSize -> return Val(Types.U32, waveSize().toString())
            else -> readDispatch(builtin)
        }
        return result
    }

    private fun waveSize(): Int = when (profile.kind) {
        TargetKind.AmdGcn -> 64
        TargetKind.Nvptx64 -> 32
        TargetKind.SpirV64 -> 32
        TargetKind.X86_64 -> 1
    }

    private fun globalId(axis: Int): Val {
        if (profile.kind == TargetKind.SpirV64) return openClDispatch("_Z13get_global_idj", axis)
        val group = readDispatch(WORKGROUP_ID[axis])
        val size = readDispatch(WORKGROUP_SIZE[axis])
        val item = readDispatch(WORKITEM_ID[axis])
        val scaled = arithmetic("mul", Types.U32, group, size)
        return arithmetic("add", Types.U32, scaled, item)
    }

    private fun readDispatch(builtin: Builtin): Val {
        if (!profile.hardwareDispatch) return readDispatchStruct(builtin)
        return when (profile.kind) {
            TargetKind.AmdGcn -> readAmdDispatch(builtin)
            TargetKind.SpirV64 -> readSpirvDispatch(builtin)
            else -> readNvptxDispatch(builtin)
        }
    }

    private fun readSpirvDispatch(builtin: Builtin): Val {
        val axis = when (builtin) {
            Builtin.WorkitemIdX, Builtin.WorkgroupIdX, Builtin.WorkgroupSizeX -> 0
            Builtin.WorkitemIdY, Builtin.WorkgroupIdY, Builtin.WorkgroupSizeY -> 1
            else -> 2
        }
        val name = when (builtin) {
            Builtin.WorkitemIdX, Builtin.WorkitemIdY, Builtin.WorkitemIdZ -> "_Z12get_local_idj"
            Builtin.WorkgroupIdX, Builtin.WorkgroupIdY, Builtin.WorkgroupIdZ -> "_Z12get_group_idj"
            Builtin.WorkgroupSizeX, Builtin.WorkgroupSizeY, Builtin.WorkgroupSizeZ -> "_Z14get_local_sizej"
            else -> {
                declarations.add("declare i32 @_Z22get_sub_group_local_idv()")
                val lane = nextTemp()
                line("$lane = call i32 @_Z22get_sub_group_local_idv()")
                return Val(Types.U32, lane)
            }
        }
        return openClDispatch(name, axis)
    }

    private fun openClDispatch(name: String, axis: Int): Val {
        declarations.add("declare i64 @$name(i32)")
        val wide = nextTemp()
        line("$wide = call i64 @$name(i32 $axis)")
        val narrow = nextTemp()
        line("$narrow = trunc i64 $wide to i32")
        return Val(Types.U32, narrow)
    }

    private fun readDispatchStruct(builtin: Builtin): Val {
        val pointer = dispatchArg ?: return Val(Types.U32, "0")
        val group = when (builtin) {
            Builtin.WorkitemIdX, Builtin.WorkitemIdY, Builtin.WorkitemIdZ -> 0
            Builtin.WorkgroupIdX, Builtin.WorkgroupIdY, Builtin.WorkgroupIdZ -> 1
            else -> 2
        }
        val axis = when (builtin) {
            Builtin.WorkitemIdX, Builtin.WorkgroupIdX, Builtin.WorkgroupSizeX -> 0
            Builtin.WorkitemIdY, Builtin.WorkgroupIdY, Builtin.WorkgroupSizeY -> 1
            Builtin.LaneId -> 0
            else -> 2
        }
        if (builtin == Builtin.LaneId) return Val(Types.U32, "0")

        val slot = nextTemp()
        line("$slot = getelementptr inbounds %spz.dispatch, ptr $pointer, i32 0, i32 $group, i32 $axis")
        val value = nextTemp()
        line("$value = load i32, ptr $slot, align 4")
        return Val(Types.U32, value)
    }

    private fun readAmdDispatch(builtin: Builtin): Val {
        val name = when (builtin) {
            Builtin.WorkitemIdX -> "llvm.amdgcn.workitem.id.x"
            Builtin.WorkitemIdY -> "llvm.amdgcn.workitem.id.y"
            Builtin.WorkitemIdZ -> "llvm.amdgcn.workitem.id.z"
            Builtin.WorkgroupIdX -> "llvm.amdgcn.workgroup.id.x"
            Builtin.WorkgroupIdY -> "llvm.amdgcn.workgroup.id.y"
            Builtin.WorkgroupIdZ -> "llvm.amdgcn.workgroup.id.z"
            Builtin.LaneId -> return amdLaneId()
            else -> return amdWorkgroupSize(builtin)
        }
        declarations.add("declare i32 @$name()")
        val result = nextTemp()
        line("$result = call i32 @$name()")
        return Val(Types.U32, result)
    }

    private fun amdLaneId(): Val {
        declarations.add("declare i32 @llvm.amdgcn.mbcnt.lo(i32, i32)")
        declarations.add("declare i32 @llvm.amdgcn.mbcnt.hi(i32, i32)")
        val low = nextTemp()
        line("$low = call i32 @llvm.amdgcn.mbcnt.lo(i32 -1, i32 0)")
        val high = nextTemp()
        line("$high = call i32 @llvm.amdgcn.mbcnt.hi(i32 -1, i32 $low)")
        return Val(Types.U32, high)
    }

    private fun amdWorkgroupSize(builtin: Builtin): Val {
        val offset = when (builtin) {
            Builtin.WorkgroupSizeX -> 4
            Builtin.WorkgroupSizeY -> 6
            else -> 8
        }
        declarations.add("declare ptr addrspace(4) @llvm.amdgcn.dispatch.ptr()")
        val packet = nextTemp()
        line("$packet = call align 4 dereferenceable(64) ptr addrspace(4) @llvm.amdgcn.dispatch.ptr()")
        val slot = nextTemp()
        line("$slot = getelementptr inbounds i8, ptr addrspace(4) $packet, i64 $offset")
        val raw = nextTemp()
        line("$raw = load i16, ptr addrspace(4) $slot, align 2")
        val widened = nextTemp()
        line("$widened = zext i16 $raw to i32")
        return Val(Types.U32, widened)
    }

    private fun readNvptxDispatch(builtin: Builtin): Val {
        val name = when (builtin) {
            Builtin.WorkitemIdX -> "llvm.nvvm.read.ptx.sreg.tid.x"
            Builtin.WorkitemIdY -> "llvm.nvvm.read.ptx.sreg.tid.y"
            Builtin.WorkitemIdZ -> "llvm.nvvm.read.ptx.sreg.tid.z"
            Builtin.WorkgroupIdX -> "llvm.nvvm.read.ptx.sreg.ctaid.x"
            Builtin.WorkgroupIdY -> "llvm.nvvm.read.ptx.sreg.ctaid.y"
            Builtin.WorkgroupIdZ -> "llvm.nvvm.read.ptx.sreg.ctaid.z"
            Builtin.WorkgroupSizeX -> "llvm.nvvm.read.ptx.sreg.ntid.x"
            Builtin.WorkgroupSizeY -> "llvm.nvvm.read.ptx.sreg.ntid.y"
            Builtin.WorkgroupSizeZ -> "llvm.nvvm.read.ptx.sreg.ntid.z"
            else -> "llvm.nvvm.read.ptx.sreg.laneid"
        }
        declarations.add("declare i32 @$name()")
        val result = nextTemp()
        line("$result = call i32 @$name()")
        return Val(Types.U32, result)
    }

    private fun emitSync(builtin: Builtin): Val {
        if (builtin == Builtin.MemFence || builtin == Builtin.MemFenceWorkgroup) {
            if (profile.isGpu) line("fence syncscope(\"workgroup\") seq_cst") else line("fence seq_cst")
            return Val(VoidType, "")
        }
        if (builtin == Builtin.MemFenceDevice) {
            if (profile.isGpu) line("fence syncscope(\"agent\") seq_cst") else line("fence seq_cst")
            return Val(VoidType, "")
        }
        when (profile.kind) {
            TargetKind.AmdGcn -> {
                declarations.add("declare void @llvm.amdgcn.s.barrier()")
                line("call void @llvm.amdgcn.s.barrier()")
            }

            TargetKind.Nvptx64 -> {
                declarations.add("declare void @llvm.nvvm.barrier0()")
                line("call void @llvm.nvvm.barrier0()")
            }

            TargetKind.SpirV64 -> {
                declarations.add("declare void @_Z7barrierj(i32)")
                line("call void @_Z7barrierj(i32 1)")
            }

            TargetKind.X86_64 -> line("fence seq_cst")
        }
        return Val(VoidType, "")
    }

    private fun nextTemp(): String = "%t${temps++}"

    private fun nextLabel(): String = "L${labels++}"

    private fun startBlock(label: String) {
        body.append(label).append(":\n")
        currentLabel = label
        terminated = false
    }

    private fun branch(label: String) {
        if (terminated) return
        line("br label %$label")
        terminated = true
    }

    private fun line(text: String) {
        if (terminated) return
        body.append("  ").append(text).append('\n')
    }

    private companion object {

        val WORKITEM_ID = arrayOf(Builtin.WorkitemIdX, Builtin.WorkitemIdY, Builtin.WorkitemIdZ)
        val WORKGROUP_ID = arrayOf(Builtin.WorkgroupIdX, Builtin.WorkgroupIdY, Builtin.WorkgroupIdZ)
        val WORKGROUP_SIZE = arrayOf(Builtin.WorkgroupSizeX, Builtin.WorkgroupSizeY, Builtin.WorkgroupSizeZ)
    }
}
