package spezi

import spezi.domain.TokenType
import spezi.domain.Types
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LexerTest {

    @Test
    fun everyPunctuationTokenLexes() {
        for ((source, expected) in PUNCTUATION) {
            assertEquals(expected, lexOne(source).type, "lexing '$source'")
        }
    }

    @Test
    fun everyKeywordLexes() {
        for (type in TokenType.entries) {
            if (type.ordinal < TokenType.TRUE.ordinal || type.ordinal > TokenType.PRIVATE.ordinal) continue
            assertEquals(type, lexOne(type.text).type, "lexing keyword '${type.text}'")
        }
    }

    @Test
    fun everyTokenTypeIsCovered() {
        val seen = HashSet<TokenType>()
        seen.add(TokenType.EOF)
        seen.add(TokenType.ID)
        seen.add(TokenType.INT_LIT)
        seen.add(TokenType.FLOAT_LIT)
        for ((source, _) in PUNCTUATION) seen.add(lexOne(source).type)
        for (type in TokenType.entries) {
            if (type.ordinal in TokenType.TRUE.ordinal..TokenType.PRIVATE.ordinal) seen.add(type)
        }

        val missing = TokenType.entries.filter { it !in seen }
        assertTrue(missing.isEmpty(), "these token types have no lexer coverage: $missing")
    }

    @Test
    fun identifiersAreNotKeywords() {
        assertEquals(TokenType.ID, lexOne("gid").type)
        assertEquals(TokenType.ID, lexOne("_private_thing").type)
        assertEquals(TokenType.ID, lexOne("iffy").type)
        assertEquals(TokenType.ID, lexOne("forward").type)
        assertEquals("iffy", lexOne("iffy").value)
    }

    @Test
    fun decimalIntegerLiterals() {
        assertEquals(TokenType.INT_LIT, lexOne("0").type)
        assertEquals("0", lexOne("0").numericBody)
        assertEquals("12345", lexOne("12345").numericBody)
        assertEquals("", lexOne("12345").numericSuffix)
    }

    @Test
    fun underscoreSeparatorsAreStripped() {
        assertEquals("1000000", lexOne("1_000_000").numericBody)
    }

    @Test
    fun hexAndBinaryLiterals() {
        assertEquals("0x7FFFFF", lexOne("0x7FFFFF").numericBody)
        assertEquals(TokenType.INT_LIT, lexOne("0x7FFFFF").type)
        assertEquals("0b1011", lexOne("0b1011").numericBody)
        assertEquals("u32", lexOne("0xFFu32").numericSuffix)
    }

    @Test
    fun everyScalarSuffixLexes() {
        for (name in Types.scalarsByName.keys) {
            if (name == "bool") continue
            val token = lexOne("1$name")
            assertEquals(name, token.numericSuffix, "suffix on '1$name'")
            val expected = if (name.startsWith("f")) TokenType.FLOAT_LIT else TokenType.INT_LIT
            assertEquals(expected, token.type, "token kind for '1$name'")
        }
    }

    @Test
    fun floatLiterals() {
        assertEquals(TokenType.FLOAT_LIT, lexOne("1.5").type)
        assertEquals("1.5", lexOne("1.5").numericBody)
        assertEquals(TokenType.FLOAT_LIT, lexOne("2f").type)
        assertEquals("f", lexOne("2f").numericSuffix)
        assertEquals(TokenType.FLOAT_LIT, lexOne("1e9").type)
        assertEquals(TokenType.FLOAT_LIT, lexOne("1.5e-3").type)
        assertEquals("1.5e-3", lexOne("1.5e-3").numericBody)
    }

    @Test
    fun rangeIsNotAFloat() {
        assertEquals(listOf(TokenType.INT_LIT, TokenType.DOTDOT, TokenType.INT_LIT), tokensOf("0..8"))
    }

    @Test
    fun lineCommentsAreSkipped() {
        assertEquals(listOf(TokenType.LET, TokenType.ID), tokensOf("let // comment here\n x"))
    }

    @Test
    fun blockCommentsNest() {
        assertEquals(listOf(TokenType.LET, TokenType.ID), tokensOf("let /* outer /* inner */ still */ x"))
    }

    @Test
    fun compoundAssignmentOperators() {
        assertEquals(
            listOf(
                TokenType.PLUS_EQ, TokenType.MINUS_EQ, TokenType.STAR_EQ, TokenType.SLASH_EQ,
                TokenType.PERCENT_EQ, TokenType.AMP_EQ, TokenType.PIPE_EQ, TokenType.CARET_EQ,
                TokenType.LSHIFT_EQ, TokenType.RSHIFT_EQ,
            ),
            tokensOf("+= -= *= /= %= &= |= ^= <<= >>="),
        )
    }

    @Test
    fun shiftsAreNotComparisons() {
        assertEquals(listOf(TokenType.LSHIFT, TokenType.RSHIFT), tokensOf("<< >>"))
        assertEquals(listOf(TokenType.LESS_EQ, TokenType.GREATER_EQ), tokensOf("<= >="))
        assertEquals(listOf(TokenType.LESS, TokenType.GREATER), tokensOf("< >"))
    }

    @Test
    fun linesAndColumnsAreTracked() {
        val tokens = Harness("let\n  x").tokens()
        assertEquals(1, tokens[0].line)
        assertEquals(1, tokens[0].col)
        assertEquals(2, tokens[1].line)
        assertEquals(3, tokens[1].col)
    }

    @Test
    fun logicalOperatorsSuggestKeywords() {
        val harness = Harness("a && b")
        harness.tokens()
        assertTrue(harness.errors.any { it.contains("use 'and'") }, harness.errors.toString())

        val other = Harness("a || b")
        other.tokens()
        assertTrue(other.errors.any { it.contains("use 'or'") }, other.errors.toString())
    }

    @Test
    fun unknownSuffixIsRejected() {
        val harness = Harness("1q7")
        harness.tokens()
        assertTrue(harness.errors.any { it.contains("unknown numeric suffix") }, harness.errors.toString())
    }

    @Test
    fun floatSuffixOnIntegerBodyIsAllowedButIntSuffixOnFloatIsNot() {
        assertEquals(TokenType.FLOAT_LIT, lexOne("3f32").type)
        val harness = Harness("1.5u32")
        harness.tokens()
        assertContains(harness.errors.joinToString(), "not a floating point suffix")
    }

    @Test
    fun stringLiteralsAreRejected() {
        val harness = Harness("\"hello\"")
        harness.tokens()
        assertContains(harness.errors.joinToString(), "string literals are not supported")
    }

    @Test
    fun unexpectedCharacterIsReported() {
        val harness = Harness("@")
        harness.tokens()
        assertContains(harness.errors.joinToString(), "unexpected character")
    }

    private companion object {

        val PUNCTUATION = listOf(
            ":" to TokenType.COLON,
            "," to TokenType.COMMA,
            "." to TokenType.DOT,
            ".." to TokenType.DOTDOT,
            "->" to TokenType.ARROW,
            "=>" to TokenType.FATARROW,
            "?" to TokenType.QUESTION,
            "(" to TokenType.LPAREN,
            ")" to TokenType.RPAREN,
            "{" to TokenType.LBRACE,
            "}" to TokenType.RBRACE,
            "[" to TokenType.LBRACKET,
            "]" to TokenType.RBRACKET,
            "=" to TokenType.EQ,
            "+=" to TokenType.PLUS_EQ,
            "-=" to TokenType.MINUS_EQ,
            "*=" to TokenType.STAR_EQ,
            "/=" to TokenType.SLASH_EQ,
            "%=" to TokenType.PERCENT_EQ,
            "&=" to TokenType.AMP_EQ,
            "|=" to TokenType.PIPE_EQ,
            "^=" to TokenType.CARET_EQ,
            "<<=" to TokenType.LSHIFT_EQ,
            ">>=" to TokenType.RSHIFT_EQ,
            "==" to TokenType.EQEQ,
            "!=" to TokenType.NEQ,
            "<" to TokenType.LESS,
            "<=" to TokenType.LESS_EQ,
            ">" to TokenType.GREATER,
            ">=" to TokenType.GREATER_EQ,
            "+" to TokenType.PLUS,
            "-" to TokenType.MINUS,
            "*" to TokenType.STAR,
            "/" to TokenType.SLASH,
            "%" to TokenType.PERCENT,
            "&" to TokenType.AMP,
            "|" to TokenType.PIPE,
            "^" to TokenType.CARET,
            "~" to TokenType.TILDE,
            "<<" to TokenType.LSHIFT,
            ">>" to TokenType.RSHIFT,
            "!" to TokenType.BANG,
        )
    }
}
