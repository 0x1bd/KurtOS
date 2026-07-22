package spezi.domain

import spezi.common.SourceFile

enum class TokenType(val text: String = "") {
    EOF("end of file"),
    ID("identifier"),
    INT_LIT("integer"),
    FLOAT_LIT("float"),

    TRUE("true"),
    FALSE("false"),
    LET("let"),
    MUT("mut"),
    CONST("const"),
    FN("fn"),
    KERNEL("kernel"),
    STRUCT("struct"),
    IMPORT("import"),
    IF("if"),
    ELSE("else"),
    RETURN("return"),
    EXTERN("extern"),
    AS("as"),
    WHILE("while"),
    FOR("for"),
    IN("in"),
    LOOP("loop"),
    BREAK("break"),
    CONTINUE("continue"),
    SWITCH("switch"),
    CASE("case"),
    AND("and"),
    OR("or"),
    GLOBAL("global"),
    SHARED("shared"),
    UNIFORM("uniform"),
    PRIVATE("private"),

    COLON(":"),
    COMMA(","),
    DOT("."),
    DOTDOT(".."),
    ARROW("->"),
    FATARROW("=>"),
    QUESTION("?"),
    LPAREN("("),
    RPAREN(")"),
    LBRACE("{"),
    RBRACE("}"),
    LBRACKET("["),
    RBRACKET("]"),

    EQ("="),
    PLUS_EQ("+="),
    MINUS_EQ("-="),
    STAR_EQ("*="),
    SLASH_EQ("/="),
    PERCENT_EQ("%="),
    AMP_EQ("&="),
    PIPE_EQ("|="),
    CARET_EQ("^="),
    LSHIFT_EQ("<<="),
    RSHIFT_EQ(">>="),

    EQEQ("=="),
    NEQ("!="),
    LESS("<"),
    LESS_EQ("<="),
    GREATER(">"),
    GREATER_EQ(">="),
    PLUS("+"),
    MINUS("-"),
    STAR("*"),
    SLASH("/"),
    PERCENT("%"),
    AMP("&"),
    PIPE("|"),
    CARET("^"),
    TILDE("~"),
    LSHIFT("<<"),
    RSHIFT(">>"),
    BANG("!");

    val isAssign: Boolean
        get() = this == EQ || compoundOp != null

    val compoundOp: TokenType?
        get() = when (this) {
            PLUS_EQ -> PLUS
            MINUS_EQ -> MINUS
            STAR_EQ -> STAR
            SLASH_EQ -> SLASH
            PERCENT_EQ -> PERCENT
            AMP_EQ -> AMP
            PIPE_EQ -> PIPE
            CARET_EQ -> CARET
            LSHIFT_EQ -> LSHIFT
            RSHIFT_EQ -> RSHIFT
            else -> null
        }
}

class Token(
    val type: TokenType,
    val value: String,
    val source: SourceFile,
    val line: Int,
    val col: Int,
    val length: Int,
    val numericSuffix: String = "",
) {

    override fun toString(): String = if (value.isEmpty()) type.text else value

    val numericBody: String get() = value
}
