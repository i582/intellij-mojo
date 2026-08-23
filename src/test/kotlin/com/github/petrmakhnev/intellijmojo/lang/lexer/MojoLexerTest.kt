package com.github.petrmakhnev.intellijmojo.lang.lexer

import com.github.petrmakhnev.intellijmojo.lang.psi.MojoTypes
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import junit.framework.TestCase

class MojoLexerTest : TestCase() {
    fun testIndentAndDedentTokens() {
        val types = significantTokens(
            """
            def main():
                if True:
                    print("Hello")
                return 0
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                MojoTypes.DEF, MojoTypes.IDENTIFIER, MojoTypes.LPAR, MojoTypes.RPAR, MojoTypes.COLON,
                MojoTypes.STATEMENT_BREAK, MojoTypes.INDENT,
                MojoTypes.IF, MojoTypes.TRUE, MojoTypes.COLON,
                MojoTypes.STATEMENT_BREAK, MojoTypes.INDENT,
                MojoTypes.IDENTIFIER, MojoTypes.LPAR, MojoTypes.STRING_LITERAL, MojoTypes.RPAR,
                MojoTypes.STATEMENT_BREAK, MojoTypes.DEDENT,
                MojoTypes.RETURN, MojoTypes.INTEGER_LITERAL,
                MojoTypes.STATEMENT_BREAK, MojoTypes.DEDENT,
            ),
            types,
        )
    }

    fun testIndentationIsSuppressedInsideBrackets() {
        val types = significantTokens(
            """
            var value = (
                1 +
                2
            )
            return value
            """.trimIndent(),
        )

        assertFalse(types.contains(MojoTypes.INDENT))
        assertFalse(types.contains(MojoTypes.DEDENT))
        assertEquals(2, types.count { it == MojoTypes.STATEMENT_BREAK })
    }

    fun testBlankAndCommentLinesDoNotChangeIndentation() {
        val types = significantTokens(
            """
            def main():
                # comment

                return 0
            """.trimIndent(),
        )

        assertEquals(1, types.count { it == MojoTypes.INDENT })
        assertEquals(1, types.count { it == MojoTypes.DEDENT })
        assertFalse(types.contains(MojoTypes.INCONSISTENT_DEDENT))
    }

    fun testConsecutiveEofDedentsHaveDistinctRestartStates() {
        val source = """
            def outer():
                if True:
                    while True:
                        pass
        """.trimIndent()
        val lexer = MojoIndentingLexer()
        lexer.start(source)

        val eofSyntheticStates = buildList {
            while (lexer.tokenType != null) {
                if (lexer.tokenStart == source.length && lexer.tokenEnd == source.length) {
                    add(lexer.state)
                }
                lexer.advance()
            }
        }

        assertEquals(eofSyntheticStates.size, eofSyntheticStates.distinct().size)
        assertTrue(eofSyntheticStates.all { it > 0 })
    }

    private fun significantTokens(source: String): List<IElementType> {
        val lexer = MojoIndentingLexer()
        lexer.start(source)
        return buildList {
            while (lexer.tokenType != null) {
                val type = lexer.tokenType!!
                if (type != TokenType.WHITE_SPACE && type != MojoTypes.LINE_BREAK && type != MojoTypes.COMMENT) {
                    add(type)
                }
                lexer.advance()
            }
        }
    }
}
