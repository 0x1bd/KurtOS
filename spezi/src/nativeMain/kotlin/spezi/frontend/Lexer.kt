package spezi.frontend

import spezi.common.Context
import spezi.common.Level
import spezi.common.SourceFile
import spezi.domain.Token
import spezi.domain.TokenType
import spezi.domain.Types

class Lexer(private val ctx: Context, private val source: SourceFile) {

    private val src = source.content
    private var start = 0
    private var current = 0
    private var line = 1
    private var lineStart = 0

    fun tokenize(): List<Token> {
        val tokens = ArrayList<Token>(src.length / 4 + 8)
        while (true) {
            val token = next()
            tokens.add(token)
            if (token.type == TokenType.EOF) break
        }
        return tokens
    }

    private fun next(): Token {
        skipTrivia()
        start = current
        if (isAtEnd()) return make(TokenType.EOF)

        val c = advance()
        if (c.isLetter() || c == '_') return scanIdentifier()
        if (c.isDigit()) return scanNumber()

        return when (c) {
            '(' -> make(TokenType.LPAREN)
            ')' -> make(TokenType.RPAREN)
            '{' -> make(TokenType.LBRACE)
            '}' -> make(TokenType.RBRACE)
            '[' -> make(TokenType.LBRACKET)
            ']' -> make(TokenType.RBRACKET)
            ',' -> make(TokenType.COMMA)
            ':' -> make(TokenType.COLON)
            '?' -> make(TokenType.QUESTION)
            '~' -> make(TokenType.TILDE)
            '.' -> if (match('.')) make(TokenType.DOTDOT) else make(TokenType.DOT)
            '+' -> if (match('=')) make(TokenType.PLUS_EQ) else make(TokenType.PLUS)
            '*' -> if (match('=')) make(TokenType.STAR_EQ) else make(TokenType.STAR)
            '/' -> if (match('=')) make(TokenType.SLASH_EQ) else make(TokenType.SLASH)
            '%' -> if (match('=')) make(TokenType.PERCENT_EQ) else make(TokenType.PERCENT)
            '^' -> if (match('=')) make(TokenType.CARET_EQ) else make(TokenType.CARET)
            '!' -> if (match('=')) make(TokenType.NEQ) else make(TokenType.BANG)

            '-' -> when {
                match('>') -> make(TokenType.ARROW)
                match('=') -> make(TokenType.MINUS_EQ)
                else -> make(TokenType.MINUS)
            }

            '=' -> when {
                match('=') -> make(TokenType.EQEQ)
                match('>') -> make(TokenType.FATARROW)
                else -> make(TokenType.EQ)
            }

            '&' -> when {
                match('&') -> error("'&&' is not an operator, use 'and'")
                match('=') -> make(TokenType.AMP_EQ)
                else -> make(TokenType.AMP)
            }

            '|' -> when {
                match('|') -> error("'||' is not an operator, use 'or'")
                match('=') -> make(TokenType.PIPE_EQ)
                else -> make(TokenType.PIPE)
            }

            '<' -> when {
                match('<') -> if (match('=')) make(TokenType.LSHIFT_EQ) else make(TokenType.LSHIFT)
                match('=') -> make(TokenType.LESS_EQ)
                else -> make(TokenType.LESS)
            }

            '>' -> when {
                match('>') -> if (match('=')) make(TokenType.RSHIFT_EQ) else make(TokenType.RSHIFT)
                match('=') -> make(TokenType.GREATER_EQ)
                else -> make(TokenType.GREATER)
            }

            '"' -> error("string literals are not supported")

            else -> error("unexpected character '$c'")
        }
    }

    private fun scanIdentifier(): Token {
        while (peek().isLetterOrDigit() || peek() == '_') advance()
        val text = src.substring(start, current)
        return make(KEYWORDS[text] ?: TokenType.ID, text)
    }

    private fun scanNumber(): Token {
        var isFloat = false
        if (src[start] == '0' && (peek() == 'x' || peek() == 'X')) {
            advance()
            while (isHexDigit(peek()) || peek() == '_') advance()
        } else if (src[start] == '0' && (peek() == 'b' || peek() == 'B')) {
            advance()
            while (peek() == '0' || peek() == '1' || peek() == '_') advance()
        } else {
            while (peek().isDigit() || peek() == '_') advance()
            if (peek() == '.' && peek(1).isDigit()) {
                isFloat = true
                advance()
                while (peek().isDigit() || peek() == '_') advance()
            }
            if (peek() == 'e' || peek() == 'E') {
                val sign = if (peek(1) == '+' || peek(1) == '-') 1 else 0
                if (peek(1 + sign).isDigit()) {
                    isFloat = true
                    advance()
                    if (sign == 1) advance()
                    while (peek().isDigit()) advance()
                }
            }
        }

        val bodyEnd = current
        while (peek().isLetterOrDigit() || peek() == '_') advance()
        val suffix = src.substring(bodyEnd, current)
        val body = src.substring(start, bodyEnd).replace("_", "")

        if (suffix.isNotEmpty()) {
            val resolved = if (suffix == "f") Types.F32 else Types.scalarsByName[suffix]
            if (resolved == null) return error("unknown numeric suffix '$suffix'")
            if (resolved is spezi.domain.FloatType) isFloat = true
            else if (isFloat) return error("'$suffix' is not a floating point suffix")
        }

        return make(if (isFloat) TokenType.FLOAT_LIT else TokenType.INT_LIT, body, suffix)
    }

    private fun skipTrivia() {
        while (true) {
            when (peek()) {
                ' ', '\r', '\t' -> advance()
                '\n' -> {
                    advance()
                    line++
                    lineStart = current
                }

                '/' -> when (peek(1)) {
                    '/' -> while (peek() != '\n' && !isAtEnd()) advance()
                    '*' -> skipBlockComment()
                    else -> return
                }

                else -> return
            }
        }
    }

    private fun skipBlockComment() {
        advance()
        advance()
        var depth = 1
        while (depth > 0 && !isAtEnd()) {
            when {
                peek() == '/' && peek(1) == '*' -> {
                    advance(); advance(); depth++
                }

                peek() == '*' && peek(1) == '/' -> {
                    advance(); advance(); depth--
                }

                else -> {
                    if (peek() == '\n') {
                        line++
                        lineStart = current + 1
                    }
                    advance()
                }
            }
        }
    }

    private fun isHexDigit(c: Char) = c.isDigit() || c in 'a'..'f' || c in 'A'..'F'

    private fun advance(): Char = src[current++]

    private fun peek(offset: Int = 0): Char =
        if (current + offset >= src.length) '\u0000' else src[current + offset]

    private fun match(expected: Char): Boolean {
        if (isAtEnd() || src[current] != expected) return false
        current++
        return true
    }

    private fun isAtEnd() = current >= src.length

    private fun make(type: TokenType, value: String = "", suffix: String = ""): Token =
        Token(type, value, source, line, start - lineStart + 1, (current - start).coerceAtLeast(1), suffix)

    private fun error(message: String): Token {
        val token = make(TokenType.EOF)
        ctx.report(Level.ERROR, message, token)
        return make(TokenType.EOF)
    }

    private companion object {

        val KEYWORDS: Map<String, TokenType> = buildMap {
            for (type in TokenType.entries) {
                if (type.ordinal >= TokenType.TRUE.ordinal && type.ordinal <= TokenType.PRIVATE.ordinal) {
                    put(type.text, type)
                }
            }
        }
    }
}
