package com.github.petrmakhnev.intellijmojo.lang.highlighting

import com.github.petrmakhnev.intellijmojo.ide.colors.MojoColor
import com.github.petrmakhnev.intellijmojo.lang.lexer.MojoLexer
import com.github.petrmakhnev.intellijmojo.lang.lexer.MojoTokenSets
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoTypes
import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType

class MojoSyntaxHighlighter : SyntaxHighlighterBase() {
    override fun getHighlightingLexer(): Lexer = MojoLexer()

    override fun getTokenHighlights(tokenType: IElementType): Array<TextAttributesKey> = when {
        MojoTokenSets.KEYWORDS.contains(tokenType) -> pack(MojoColor.KEYWORD.textAttributesKey)
        MojoTokenSets.STRINGS.contains(tokenType) -> pack(MojoColor.STRING.textAttributesKey)
        MojoTokenSets.NUMBERS.contains(tokenType) -> pack(MojoColor.NUMBER.textAttributesKey)
        MojoTokenSets.COMMENTS.contains(tokenType) -> pack(MojoColor.LINE_COMMENT.textAttributesKey)
        tokenType == MojoTypes.AT -> pack(MojoColor.DECORATOR.textAttributesKey)
        MojoTokenSets.OPERATORS.contains(tokenType) -> pack(MojoColor.OPERATION_SIGN.textAttributesKey)
        tokenType == MojoTypes.LPAR || tokenType == MojoTypes.RPAR -> pack(MojoColor.PARENTHESES.textAttributesKey)
        tokenType == MojoTypes.LBRACKET || tokenType == MojoTypes.RBRACKET -> pack(MojoColor.BRACKETS.textAttributesKey)
        tokenType == MojoTypes.LBRACE || tokenType == MojoTypes.RBRACE -> pack(MojoColor.BRACES.textAttributesKey)
        tokenType == MojoTypes.COMMA -> pack(MojoColor.COMMA.textAttributesKey)
        tokenType == MojoTypes.DOT -> pack(MojoColor.DOT.textAttributesKey)
        tokenType == MojoTypes.COLON || tokenType == MojoTypes.SEMICOLON -> pack(MojoColor.SEMICOLON.textAttributesKey)
        tokenType == TokenType.BAD_CHARACTER || tokenType == MojoTypes.INCONSISTENT_DEDENT -> pack(MojoColor.BAD_CHARACTER.textAttributesKey)
        else -> EMPTY
    }

    companion object {
        private val EMPTY = emptyArray<TextAttributesKey>()
    }
}
