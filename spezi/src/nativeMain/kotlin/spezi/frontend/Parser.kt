package spezi.frontend

import spezi.common.Context
import spezi.common.Level
import spezi.common.SourceFile
import spezi.domain.*

class Parser(private val ctx: Context, source: SourceFile, private val module: String) {

    private val tokens = Lexer(ctx, source).tokenize()
    private var pos = 0

    private class ParseError : RuntimeException()

    fun parseInto(out: MutableList<AstNode>) {
        while (!check(TokenType.EOF)) {
            try {
                when (curr().type) {
                    TokenType.IMPORT -> parseImport(out)
                    TokenType.STRUCT -> out.add(parseStruct())
                    TokenType.CONST -> out.add(parseConst())
                    TokenType.FN -> out.add(parseFn(false))
                    TokenType.KERNEL -> {
                        advance()
                        out.add(parseFn(true))
                    }

                    TokenType.EXTERN -> out.add(parseExtern())
                    else -> errorAtCurrent("expected a top-level declaration")
                }
            } catch (e: ParseError) {
                synchronizeTopLevel()
            }
        }
    }

    private fun parseImport(out: MutableList<AstNode>) {
        advance()
        val name = StringBuilder()
        name.append(consumeIdentifier("expected a module name"))
        while (match(TokenType.DOT)) {
            name.append('.').append(consumeIdentifier("expected a module path segment"))
        }

        val moduleName = name.toString()
        val path = ctx.resolveImport(moduleName)
        if (path == null) {
            ctx.report(Level.ERROR, "cannot resolve module '$moduleName'", prev())
            return
        }
        if (ctx.markLoaded(path)) return

        val source = SourceFile.load(path)
        if (source == null) {
            ctx.report(Level.ERROR, "cannot read module '$moduleName' at $path", prev())
            return
        }
        Parser(ctx, source, moduleName).parseInto(out)
    }

    private fun parseStruct(): StructDef {
        val loc = curr()
        consume(TokenType.STRUCT, "expected 'struct'")
        val name = consumeIdentifier("expected a struct name")
        consume(TokenType.LBRACE, "expected '{'")
        val fields = ArrayList<Field>()
        while (!check(TokenType.RBRACE) && !check(TokenType.EOF)) {
            val fieldLoc = curr()
            val fieldName = consumeIdentifier("expected a field name")
            consume(TokenType.COLON, "expected ':'")
            fields.add(Field(fieldName, parseType(), fieldLoc))
            if (!check(TokenType.RBRACE)) consume(TokenType.COMMA, "expected ',' between fields")
        }
        consume(TokenType.RBRACE, "expected '}'")
        return StructDef(module, name, fields, loc)
    }

    private fun parseConst(): ConstDef {
        val loc = curr()
        consume(TokenType.CONST, "expected 'const'")
        val name = consumeIdentifier("expected a constant name")
        val type = if (match(TokenType.COLON)) parseType() else null
        consume(TokenType.EQ, "a constant must have an initializer")
        return ConstDef(module, name, type, parseExpr(), loc)
    }

    private fun parseFn(isKernel: Boolean): FnDef {
        val loc = curr()
        consume(TokenType.FN, "expected 'fn'")
        val name = consumeIdentifier("expected a function name")
        val params = parseParams()
        val ret = if (match(TokenType.ARROW)) parseType() else VoidType
        val body = parseBlock()
        return FnDef(module, name, isKernel, params, ret, body, loc)
    }

    private fun parseExtern(): ExternFnDef {
        val loc = curr()
        consume(TokenType.EXTERN, "expected 'extern'")
        consume(TokenType.FN, "expected 'fn'")
        val name = consumeIdentifier("expected a function name")
        val params = parseParams()
        val ret = if (match(TokenType.ARROW)) parseType() else VoidType
        return ExternFnDef(name, params, ret, loc)
    }

    private fun parseParams(): List<Param> {
        consume(TokenType.LPAREN, "expected '('")
        val params = ArrayList<Param>()
        if (!check(TokenType.RPAREN)) {
            do {
                if (check(TokenType.RPAREN)) break
                val loc = curr()
                val name = consumeIdentifier("expected a parameter name")
                consume(TokenType.COLON, "expected ':'")
                params.add(Param(name, parseType(), loc))
            } while (match(TokenType.COMMA))
        }
        consume(TokenType.RPAREN, "expected ')'")
        return params
    }

    private fun parseAddressSpace(): AddressSpace? = when (curr().type) {
        TokenType.GLOBAL -> {
            advance(); AddressSpace.Global
        }

        TokenType.SHARED -> {
            advance(); AddressSpace.Shared
        }

        TokenType.UNIFORM -> {
            advance(); AddressSpace.Uniform
        }

        TokenType.PRIVATE -> {
            advance(); AddressSpace.Private
        }

        else -> null
    }

    private fun parseType(): Type {
        val space = parseAddressSpace() ?: AddressSpace.Private
        val mutable = match(TokenType.MUT)

        if (match(TokenType.STAR)) return PtrType(parseType(), space, mutable)

        if (match(TokenType.LBRACKET)) {
            if (match(TokenType.RBRACKET)) return SliceType(parseType(), space, mutable)
            val lengthToken = curr()
            consume(TokenType.INT_LIT, "expected an array length")
            val length = lengthToken.numericBody.toIntOrNull()
            if (length == null || length <= 0) {
                ctx.report(Level.ERROR, "array length must be a positive integer", lengthToken)
            }
            consume(TokenType.RBRACKET, "expected ']'")
            return ArrayType(parseType(), length ?: 1)
        }

        val nameToken = curr()
        val name = consumeIdentifier("expected a type")
        if (name == "void") return VoidType
        Types.scalarsByName[name]?.let { return it }
        Types.vectorByName(name)?.let { return it }
        if (name.isNotEmpty() && name[0].isUpperCase()) return StructType(name)
        ctx.report(Level.ERROR, "unknown type '$name'", nameToken)
        return ErrorType
    }

    private fun parseBlock(): Block {
        val loc = curr()
        consume(TokenType.LBRACE, "expected '{'")
        val stmts = ArrayList<Stmt>()
        while (!check(TokenType.RBRACE) && !check(TokenType.EOF)) {
            try {
                stmts.add(parseStmt())
            } catch (e: ParseError) {
                synchronizeStatement()
            }
        }
        consume(TokenType.RBRACE, "expected '}'")
        return Block(stmts, loc)
    }

    private fun parseStmt(): Stmt = when (curr().type) {
        TokenType.LET -> parseVarDecl()
        TokenType.IF -> parseIf()
        TokenType.WHILE -> parseWhile()
        TokenType.FOR -> parseFor()
        TokenType.LOOP -> parseLoop()
        TokenType.SWITCH -> parseSwitch()
        TokenType.RETURN -> parseReturn()
        TokenType.BREAK -> BreakStmt(advance())
        TokenType.CONTINUE -> ContinueStmt(advance())
        TokenType.LBRACE -> parseBlock()
        else -> parseExprStmt()
    }

    private fun parseExprStmt(): Stmt {
        val expr = parseExpr()
        val op = curr().type
        if (op.isAssign) {
            val loc = advance()
            return Assign(expr, op.compoundOp, parseExpr(), loc)
        }
        return expr
    }

    private fun parseVarDecl(): VarDecl {
        val loc = advance()
        val space = parseAddressSpace() ?: AddressSpace.Private
        val mutable = match(TokenType.MUT)
        val name = consumeIdentifier("expected a variable name")
        val type = if (match(TokenType.COLON)) parseType() else null
        val init = if (match(TokenType.EQ)) parseExpr() else null
        if (type == null && init == null) {
            ctx.report(Level.ERROR, "'$name' needs either a type annotation or an initializer", loc)
        }
        return VarDecl(name, type, mutable, space, init, loc)
    }

    private fun parseIf(): IfStmt {
        val loc = advance()
        val cond = parseExpr()
        val thenBlock = parseBlock()
        var elseBranch: Stmt? = null
        if (match(TokenType.ELSE)) {
            elseBranch = if (check(TokenType.IF)) parseIf() else parseBlock()
        }
        return IfStmt(cond, thenBlock, elseBranch, loc)
    }

    private fun parseWhile(): WhileStmt {
        val loc = advance()
        val cond = parseExpr()
        return WhileStmt(cond, parseBlock(), loc)
    }

    private fun parseFor(): ForStmt {
        val loc = advance()
        val name = consumeIdentifier("expected a loop variable name")
        consume(TokenType.IN, "expected 'in'")
        val start = parseExpr()
        consume(TokenType.DOTDOT, "expected '..' in a range")
        val inclusive = match(TokenType.EQ)
        val end = parseExpr()
        return ForStmt(name, start, end, inclusive, parseBlock(), loc)
    }

    private fun parseLoop(): LoopStmt {
        val loc = advance()
        return LoopStmt(parseBlock(), loc)
    }

    private fun parseSwitch(): SwitchStmt {
        val loc = advance()
        val subject = parseExpr()
        consume(TokenType.LBRACE, "expected '{'")

        val cases = ArrayList<SwitchCase>()
        var elseBlock: Block? = null
        while (!check(TokenType.RBRACE) && !check(TokenType.EOF)) {
            if (match(TokenType.ELSE)) {
                consume(TokenType.ARROW, "expected '->'")
                if (elseBlock != null) ctx.report(Level.ERROR, "duplicate 'else' arm", prev())
                elseBlock = parseBlock()
                continue
            }
            val caseLoc = curr()
            consume(TokenType.CASE, "expected 'case' or 'else'")
            val values = ArrayList<Expr>()
            do {
                values.add(parseExpr())
            } while (match(TokenType.COMMA))
            consume(TokenType.ARROW, "expected '->'")
            cases.add(SwitchCase(values, parseBlock(), caseLoc))
        }
        consume(TokenType.RBRACE, "expected '}'")
        return SwitchStmt(subject, cases, elseBlock, loc)
    }

    private fun parseReturn(): ReturnStmt {
        val loc = advance()
        if (check(TokenType.RBRACE) || check(TokenType.EOF)) return ReturnStmt(null, loc)
        return ReturnStmt(parseExpr(), loc)
    }

    private fun parseExpr(): Expr = parseTernary()

    private fun parseTernary(): Expr {
        val cond = parseBinary(1)
        if (!check(TokenType.QUESTION)) return cond
        val loc = advance()
        val ifTrue = parseTernary()
        consume(TokenType.COLON, "expected ':' in a ternary expression")
        return Ternary(cond, ifTrue, parseTernary(), loc)
    }

    private fun precedenceOf(type: TokenType): Int = when (type) {
        TokenType.OR -> 1
        TokenType.AND -> 2
        TokenType.EQEQ, TokenType.NEQ,
        TokenType.LESS, TokenType.LESS_EQ, TokenType.GREATER, TokenType.GREATER_EQ,
        -> 3

        TokenType.PIPE -> 4
        TokenType.CARET -> 5
        TokenType.AMP -> 6
        TokenType.LSHIFT, TokenType.RSHIFT -> 7
        TokenType.PLUS, TokenType.MINUS -> 8
        TokenType.STAR, TokenType.SLASH, TokenType.PERCENT -> 9
        else -> 0
    }

    private fun parseBinary(minPrecedence: Int): Expr {
        var lhs = parseCast()
        while (true) {
            val op = curr().type
            val precedence = precedenceOf(op)
            if (precedence < minPrecedence || precedence == 0) break
            if (op == TokenType.STAR && startsLine()) break
            val loc = advance()
            val rhs = parseBinary(precedence + 1)
            lhs = if (op == TokenType.AND || op == TokenType.OR) {
                LogicalOp(lhs, op, rhs, loc)
            } else {
                BinOp(lhs, op, rhs, loc)
            }
        }
        return lhs
    }

    private fun parseCast(): Expr {
        var node = parseUnary()
        while (check(TokenType.AS)) {
            val loc = advance()
            node = CastExpr(node, parseType(), loc)
        }
        return node
    }

    private fun parseUnary(): Expr {
        val type = curr().type
        if (type == TokenType.BANG || type == TokenType.MINUS || type == TokenType.TILDE) {
            val loc = advance()
            return UnaryOp(type, parseUnary(), loc)
        }
        if (type == TokenType.AMP) {
            val loc = advance()
            return AddrOf(parseUnary(), loc)
        }
        if (type == TokenType.STAR) {
            val loc = advance()
            return Deref(parseUnary(), loc)
        }
        return parsePostfix(parsePrimary())
    }

    private fun parsePostfix(start: Expr): Expr {
        var node = start
        while (true) {
            when {
                check(TokenType.DOT) -> {
                    val loc = advance()
                    val member = consumeIdentifier("expected a member name")
                    node = if (check(TokenType.LPAREN)) {
                        val args = ArrayList<Expr>()
                        args.add(node)
                        args.addAll(parseCallArgs())
                        Call(member, args, loc)
                    } else {
                        Access(node, member, loc)
                    }
                }

                check(TokenType.LBRACKET) -> {
                    val loc = advance()
                    val index = parseExpr()
                    consume(TokenType.RBRACKET, "expected ']'")
                    node = Index(node, index, loc)
                }

                else -> return node
            }
        }
    }

    private fun parsePrimary(): Expr {
        val token = curr()
        when (token.type) {
            TokenType.INT_LIT -> {
                advance()
                return LiteralInt(parseIntegerBody(token), suffixType(token), token)
            }

            TokenType.FLOAT_LIT -> {
                advance()
                return LiteralFloat(token.numericBody.toDoubleOrNull() ?: 0.0, suffixType(token), token)
            }

            TokenType.TRUE -> {
                advance(); return LiteralBool(true, token)
            }

            TokenType.FALSE -> {
                advance(); return LiteralBool(false, token)
            }

            TokenType.LPAREN -> {
                advance()
                val inner = parseExpr()
                consume(TokenType.RPAREN, "expected ')'")
                return inner
            }

            TokenType.ID -> {
                advance()
                if (check(TokenType.LPAREN)) {
                    val args = ArrayList<Expr>()
                    args.addAll(parseCallArgs())
                    if (token.value.isNotEmpty() && token.value[0].isUpperCase() &&
                        Types.scalarsByName[token.value] == null && Types.vectorByName(token.value) == null
                    ) {
                        return StructLit(token.value, args, token)
                    }
                    return Call(token.value, args, token)
                }
                return VarRef(token.value, token)
            }

            else -> errorAtCurrent("unexpected ${token.type.text}")
        }
    }

    private fun parseIntegerBody(token: Token): Long {
        val body = token.numericBody
        return when {
            body.startsWith("0x") || body.startsWith("0X") -> body.substring(2).toULongOrNull(16)?.toLong()
            body.startsWith("0b") || body.startsWith("0B") -> body.substring(2).toULongOrNull(2)?.toLong()
            else -> body.toULongOrNull()?.toLong()
        } ?: run {
            ctx.report(Level.ERROR, "integer literal '$body' does not fit in 64 bits", token)
            0L
        }
    }

    private fun suffixType(token: Token): Type? {
        val suffix = token.numericSuffix
        if (suffix.isEmpty()) return null
        if (suffix == "f") return Types.F32
        return Types.scalarsByName[suffix]
    }

    private fun parseCallArgs(): List<Expr> {
        consume(TokenType.LPAREN, "expected '('")
        val args = ArrayList<Expr>()
        if (!check(TokenType.RPAREN)) {
            do {
                if (check(TokenType.RPAREN)) break
                args.add(parseExpr())
            } while (match(TokenType.COMMA))
        }
        consume(TokenType.RPAREN, "expected ')'")
        return args
    }

    private fun synchronizeTopLevel() {
        while (!check(TokenType.EOF)) {
            when (curr().type) {
                TokenType.FN, TokenType.KERNEL, TokenType.STRUCT,
                TokenType.IMPORT, TokenType.EXTERN, TokenType.CONST,
                -> return

                else -> advance()
            }
        }
    }

    private fun synchronizeStatement() {
        var depth = 0
        while (!check(TokenType.EOF)) {
            when (curr().type) {
                TokenType.LBRACE -> depth++
                TokenType.RBRACE -> {
                    if (depth == 0) return
                    depth--
                }

                TokenType.LET, TokenType.IF, TokenType.WHILE, TokenType.FOR,
                TokenType.LOOP, TokenType.SWITCH, TokenType.RETURN,
                -> if (depth == 0) return

                else -> {}
            }
            advance()
        }
    }

    private fun startsLine(): Boolean = pos > 0 && tokens[pos].line > tokens[pos - 1].line

    private fun curr(): Token = tokens[pos]

    private fun prev(): Token = tokens[if (pos == 0) 0 else pos - 1]

    private fun advance(): Token {
        val token = tokens[pos]
        if (pos < tokens.size - 1) pos++
        return token
    }

    private fun check(type: TokenType) = tokens[pos].type == type

    private fun match(type: TokenType): Boolean {
        if (!check(type)) return false
        advance()
        return true
    }

    private fun consume(type: TokenType, message: String) {
        if (check(type)) advance() else errorAtCurrent(message)
    }

    private fun consumeIdentifier(message: String): String {
        if (!check(TokenType.ID)) errorAtCurrent(message)
        return advance().value
    }

    private fun errorAtCurrent(message: String): Nothing {
        ctx.report(Level.ERROR, message, curr())
        throw ParseError()
    }
}
