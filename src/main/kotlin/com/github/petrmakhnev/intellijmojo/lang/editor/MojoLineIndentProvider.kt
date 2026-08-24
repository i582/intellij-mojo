package com.github.petrmakhnev.intellijmojo.lang.editor

import com.github.petrmakhnev.intellijmojo.lang.MojoLanguage
import com.github.petrmakhnev.intellijmojo.lang.lexer.MojoTokenSets
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoTypes
import com.intellij.application.options.CodeStyle
import com.intellij.lang.Language
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.TokenType
import com.intellij.psi.codeStyle.lineIndent.LineIndentProvider
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet

class MojoLineIndentProvider : LineIndentProvider {
    override fun isSuitableFor(language: Language?): Boolean = language == MojoLanguage

    override fun getLineIndent(
        project: Project,
        editor: Editor,
        language: Language?,
        offset: Int,
    ): String? {
        if (!isSuitableFor(language)) return null

        val document = editor.document
        val currentLine = document.getLineNumber(offset.coerceIn(0, document.textLength))
        if (currentLine == 0) return ""

        val previousLine = currentLine - 1
        val lineStart = document.getLineStartOffset(previousLine)
        val lineEnd = document.getLineEndOffset(previousLine)
        val chars = document.charsSequence
        val indentEnd = (lineStart until lineEnd)
            .firstOrNull { chars[it] != ' ' && chars[it] != '\t' }
            ?: lineEnd
        val baseIndent = chars.subSequence(lineStart, indentEnd).toString()

        return when {
            opensSuite(editor, lineStart, lineEnd) ->
                baseIndent + indentUnit(project, editor, baseIndent)

            dedentsAfterStatement(editor, lineStart, lineEnd) ->
                parentIndent(project, editor, previousLine, baseIndent)

            else -> baseIndent
        }
    }

    private fun opensSuite(editor: Editor, lineStart: Int, lineEnd: Int): Boolean {
        if (lineStart == lineEnd) return false

        val iterator = editor.highlighter.createIterator(lineEnd - 1)
        while (!iterator.atEnd() && iterator.end > lineStart) {
            val tokenType = iterator.tokenType
            if (tokenType != TokenType.WHITE_SPACE && !MojoTokenSets.COMMENTS.contains(tokenType)) {
                return tokenType == MojoTypes.COLON
            }
            iterator.retreat()
        }
        return false
    }

    private fun dedentsAfterStatement(editor: Editor, lineStart: Int, lineEnd: Int): Boolean =
        DEDENT_AFTER_STATEMENT.contains(firstSignificantToken(editor, lineStart, lineEnd))

    private fun firstSignificantToken(editor: Editor, lineStart: Int, lineEnd: Int): IElementType? {
        if (lineStart == lineEnd) return null

        val iterator = editor.highlighter.createIterator(lineStart)
        while (!iterator.atEnd() && iterator.start < lineEnd) {
            val tokenType = iterator.tokenType
            if (tokenType != TokenType.WHITE_SPACE && !MojoTokenSets.COMMENTS.contains(tokenType)) {
                return tokenType
            }
            iterator.advance()
        }
        return null
    }

    private fun parentIndent(
        project: Project,
        editor: Editor,
        previousLine: Int,
        baseIndent: String,
    ): String {
        val document = editor.document
        val tabSize = CodeStyle.getIndentOptions(project, document).TAB_SIZE.coerceAtLeast(1)
        val baseWidth = visualWidth(baseIndent, tabSize)

        for (line in previousLine - 1 downTo 0) {
            val indent = lineIndent(document, line) ?: continue
            if (visualWidth(indent, tabSize) < baseWidth) return indent
        }

        return removeIndentUnit(project, editor, baseIndent)
    }

    private fun lineIndent(document: Document, line: Int): String? {
        val lineStart = document.getLineStartOffset(line)
        val lineEnd = document.getLineEndOffset(line)
        val chars = document.charsSequence
        val indentEnd = (lineStart until lineEnd)
            .firstOrNull { chars[it] != ' ' && chars[it] != '\t' }
            ?: return null
        return chars.subSequence(lineStart, indentEnd).toString()
    }

    private fun removeIndentUnit(project: Project, editor: Editor, indent: String): String {
        if (indent.endsWith('\t')) return indent.dropLast(1)

        val indentSize = CodeStyle.getIndentOptions(project, editor.document).INDENT_SIZE.coerceAtLeast(1)
        val spacesToRemove = indent.takeLastWhile { it == ' ' }.length.coerceAtMost(indentSize)
        return indent.dropLast(spacesToRemove)
    }

    private fun visualWidth(indent: String, tabSize: Int): Int {
        var width = 0
        for (char in indent) {
            width = if (char == '\t') ((width / tabSize) + 1) * tabSize else width + 1
        }
        return width
    }

    private fun indentUnit(project: Project, editor: Editor, baseIndent: String): String {
        if ('\t' in baseIndent) return "\t"

        val options = CodeStyle.getIndentOptions(project, editor.document)
        return if (options.USE_TAB_CHARACTER) {
            "\t"
        } else {
            " ".repeat(options.INDENT_SIZE.coerceAtLeast(1))
        }
    }

    companion object {
        private val DEDENT_AFTER_STATEMENT = TokenSet.create(
            MojoTypes.RETURN,
            MojoTypes.RAISE,
            MojoTypes.PASS,
            MojoTypes.ELLIPSIS,
        )
    }
}
