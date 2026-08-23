package com.github.petrmakhnev.intellijmojo.lang.indexing

import com.github.petrmakhnev.intellijmojo.lang.lexer.MojoLexer
import com.github.petrmakhnev.intellijmojo.lang.lexer.MojoTokenSets
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoFile
import com.intellij.lexer.Lexer
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.search.IndexPatternBuilder
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet

class MojoIndexPatternBuilder : IndexPatternBuilder {
    override fun getIndexingLexer(file: PsiFile): Lexer? =
        if (file is MojoFile) MojoLexer() else null

    override fun getCommentTokenSet(file: PsiFile): TokenSet? =
        if (file is MojoFile) MojoTokenSets.COMMENTS else null

    override fun getCommentStartDelta(tokenType: IElementType): Int = 1

    override fun getCommentEndDelta(tokenType: IElementType): Int = 0

    override fun getCharsAllowedInContinuationPrefix(tokenType: IElementType): String = "#"
}
