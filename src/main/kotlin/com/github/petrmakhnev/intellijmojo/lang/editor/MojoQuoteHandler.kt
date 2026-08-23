package com.github.petrmakhnev.intellijmojo.lang.editor

import com.github.petrmakhnev.intellijmojo.lang.lexer.MojoTokenSets
import com.intellij.codeInsight.editorActions.MultiCharQuoteHandler
import com.intellij.codeInsight.editorActions.SimpleTokenSetQuoteHandler
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.highlighter.HighlighterIterator

class MojoQuoteHandler :
    SimpleTokenSetQuoteHandler(MojoTokenSets.STRINGS),
    MultiCharQuoteHandler {
    override fun isClosingQuote(iterator: HighlighterIterator, offset: Int): Boolean {
        if (!MojoTokenSets.STRINGS.contains(iterator.tokenType)) return false
        val text = iterator.document.charsSequence
        val end = iterator.end
        val closingTripleStart = end - TRIPLE_QUOTE_LENGTH
        if (closingTripleStart >= iterator.start && tripleQuoteAt(text, closingTripleStart) != null) {
            return offset in closingTripleStart until end
        }
        return offset == end - 1
    }

    // An unfinished triple-quoted string ends in a quote, which the base
    // handler would incorrectly treat as a closing delimiter.
    override fun hasNonClosedLiteral(
        editor: Editor,
        iterator: HighlighterIterator,
        offset: Int,
    ): Boolean = true

    override fun getClosingQuote(iterator: HighlighterIterator, offset: Int): CharSequence? {
        val text = iterator.document.charsSequence
        val start = iterator.start
        val end = iterator.end

        if (MojoTokenSets.STRINGS.contains(iterator.tokenType) && end - start == TRIPLE_QUOTE_LENGTH) {
            tripleQuoteAt(text, start)?.let { return it }
        }

        return tripleQuoteBefore(text, offset)
    }

    private fun tripleQuoteBefore(text: CharSequence, offset: Int): String? =
        if (offset >= TRIPLE_QUOTE_LENGTH) tripleQuoteAt(text, offset - TRIPLE_QUOTE_LENGTH) else null

    private fun tripleQuoteAt(text: CharSequence, offset: Int): String? {
        if (offset < 0 || offset + TRIPLE_QUOTE_LENGTH > text.length) return null
        val quote = text[offset]
        if (quote != '\'' && quote != '"') return null
        if (text[offset + 1] != quote || text[offset + 2] != quote) return null
        return quote.toString().repeat(TRIPLE_QUOTE_LENGTH)
    }

    private companion object {
        const val TRIPLE_QUOTE_LENGTH = 3
    }
}
