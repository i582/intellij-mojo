package com.github.petrmakhnev.intellijmojo.lang.editor

import com.github.petrmakhnev.intellijmojo.lang.lexer.MojoTokenSets
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoBraceExpression
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoCaseClause
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoElifClause
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoElseClause
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoExceptClause
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoExtensionDeclaration
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoFinallyClause
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoForStatement
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoFunctionDeclaration
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoFutureImportStatement
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoFile
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoIfStatement
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoImportStatement
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoMatchStatement
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoMlirRegionDeclaration
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoModuleImportStatement
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoListExpression
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoParenthesizedExpression
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoSelectiveImportStatement
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoSimpleStatementList
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoStringValue
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoStructDeclaration
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoTraitDeclaration
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoTryStatement
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoTypes
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoWhileStatement
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoWithStatement
import com.intellij.lang.ASTNode
import com.intellij.codeInsight.folding.CodeFoldingSettings
import com.intellij.lang.folding.CustomFoldingBuilder
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.tree.TokenSet

class MojoFoldingBuilder : CustomFoldingBuilder(), DumbAware {
    override fun buildLanguageFoldRegions(
        descriptors: MutableList<FoldingDescriptor>,
        root: PsiElement,
        document: Document,
        quick: Boolean,
    ) {
        collectRegions(root.node, document, descriptors)
        collectImportRegions(root, document, descriptors)
        collectCommentRegions(root, document, descriptors)
    }

    override fun getLanguagePlaceholderText(node: ASTNode, range: TextRange): String = when (node.psi) {
        is MojoImportStatement,
        is MojoModuleImportStatement,
        is MojoSelectiveImportStatement,
        is MojoFutureImportStatement,
        -> "import ..."
        else -> "..."
    }

    override fun isRegionCollapsedByDefault(node: ASTNode): Boolean = when (node.psi) {
        is MojoImportStatement,
        is MojoModuleImportStatement,
        is MojoSelectiveImportStatement,
        is MojoFutureImportStatement,
        ->
            CodeFoldingSettings.getInstance().COLLAPSE_IMPORTS
        is MojoFunctionDeclaration -> CodeFoldingSettings.getInstance().COLLAPSE_METHODS
        else -> false
    }

    override fun isCustomFoldingCandidate(node: ASTNode): Boolean =
        MojoTokenSets.COMMENTS.contains(node.elementType)

    override fun isCustomFoldingRoot(node: ASTNode): Boolean =
        node.psi is MojoFile || node.psi.isSuiteOwner()

    private fun collectRegions(
        node: ASTNode,
        document: Document,
        descriptors: MutableList<FoldingDescriptor>,
    ) {
        val range = when (val element = node.psi) {
            is MojoStringValue -> element.multilineStringContentRange()
            is MojoListExpression, is MojoBraceExpression, is MojoParenthesizedExpression ->
                node.delimitedContentRange()
            else -> if (element.isSuiteOwner()) node.suiteRange(document) else null
        }
        if (range != null && range.isMultiline(document)) {
            descriptors += FoldingDescriptor(node, range)
        }
        node.getChildren(null).forEach { child -> collectRegions(child, document, descriptors) }
    }

    private fun collectImportRegions(
        root: PsiElement,
        document: Document,
        descriptors: MutableList<FoldingDescriptor>,
    ) {
        var group = mutableListOf<PsiElement>()

        fun flush() {
            if (group.size >= 2) {
                val range = TextRange(group.first().textOffset, group.last().textRange.endOffset)
                if (range.isMultiline(document)) {
                    descriptors += FoldingDescriptor(group.first().node, range)
                }
            }
            group = mutableListOf()
        }

        val imports = PsiTreeUtil.findChildrenOfAnyType(
            root,
            MojoModuleImportStatement::class.java,
            MojoSelectiveImportStatement::class.java,
            MojoFutureImportStatement::class.java,
        ).filter { statement ->
            PsiTreeUtil.getParentOfType(statement, MojoSimpleStatementList::class.java, false)?.parent == root
        }.sortedBy(PsiElement::getTextOffset)
        imports.forEach { statement ->
            val previous = group.lastOrNull()
            if (previous != null) {
                val between = document.charsSequence.subSequence(previous.textRange.endOffset, statement.textOffset)
                if (between.any { !it.isWhitespace() }) {
                    flush()
                }
            }
            group += statement
        }
        flush()
    }

    private fun collectCommentRegions(
        root: PsiElement,
        document: Document,
        descriptors: MutableList<FoldingDescriptor>,
    ) {
        var group = mutableListOf<PsiElement>()

        fun flush() {
            if (group.size >= 2) {
                val first = group.first()
                val prefixLength = if (first.text.startsWith("# ")) 2 else 1
                val range = TextRange(first.textOffset + prefixLength, group.last().textRange.endOffset)
                if (range.isMultiline(document)) descriptors += FoldingDescriptor(first.node, range)
            }
            group = mutableListOf()
        }

        val comments = PsiTreeUtil.collectElements(root) { element ->
            MojoTokenSets.COMMENTS.contains(element.node.elementType)
        }.sortedBy(PsiElement::getTextOffset)
        comments.forEach { comment ->
            val previous = group.lastOrNull()
            val consecutive = previous != null &&
                !previous.isRegionMarker() && !comment.isRegionMarker() &&
                document.getLineNumber(comment.textOffset) ==
                document.getLineNumber(previous.textRange.endOffset.coerceAtMost(document.textLength)) + 1 &&
                previous.indent(document) == comment.indent(document) &&
                document.charsSequence
                    .subSequence(previous.textRange.endOffset, comment.textOffset)
                    .all(Char::isWhitespace)
            if (!consecutive) flush()
            if (!comment.isRegionMarker()) group += comment
        }
        flush()
    }

    private fun ASTNode.suiteEndOffset(): Int {
        val boundary = getChildren(null).firstOrNull { child ->
            when (psi) {
                is MojoIfStatement -> child.psi is MojoElifClause || child.psi is MojoElseClause
                is MojoForStatement, is MojoWhileStatement -> child.psi is MojoElseClause
                is MojoTryStatement -> child.psi is MojoExceptClause ||
                    child.psi is MojoElseClause || child.psi is MojoFinallyClause
                else -> false
            }
        }
        return boundary?.startOffset ?: textRange.endOffset
    }

    private fun PsiElement.isSuiteOwner(): Boolean = when (this) {
        is MojoFunctionDeclaration,
        is MojoStructDeclaration,
        is MojoTraitDeclaration,
        is MojoExtensionDeclaration,
        is MojoMlirRegionDeclaration,
        is MojoIfStatement,
        is MojoElifClause,
        is MojoElseClause,
        is MojoForStatement,
        is MojoWhileStatement,
        is MojoTryStatement,
        is MojoExceptClause,
        is MojoFinallyClause,
        is MojoWithStatement,
        is MojoMatchStatement,
        is MojoCaseClause,
        -> true

        else -> false
    }

    private fun ASTNode.suiteRange(document: Document): TextRange? {
        val colon = getChildren(COLON).lastOrNull() ?: return null
        return TextRange(colon.textRange.endOffset, suiteEndOffset())
            .trimTrailingWhitespace(document)
            .takeIf { it.startOffset < it.endOffset }
    }

    private fun TextRange.trimTrailingWhitespace(document: Document): TextRange {
        val chars = document.charsSequence
        var trimmedEnd = endOffset.coerceAtMost(chars.length)
        while (trimmedEnd > startOffset && chars[trimmedEnd - 1].isWhitespace()) {
            trimmedEnd--
        }
        return TextRange(startOffset, trimmedEnd)
    }

    private fun MojoStringValue.multilineStringContentRange(): TextRange? {
        val delimiter = TRIPLE_QUOTES
            .mapNotNull { quote -> text.indexOf(quote).takeIf { it >= 0 }?.let { it to quote } }
            .minByOrNull { it.first }
            ?: return null
        val contentStart = delimiter.first + delimiter.second.length
        val contentEnd = text.lastIndexOf(delimiter.second)
        if (contentEnd <= contentStart) return null
        return TextRange(textOffset + contentStart, textOffset + contentEnd)
    }

    private fun ASTNode.delimitedContentRange(): TextRange? {
        val text = chars
        if (text.length < 2 || !matchingDelimiters(text.first(), text.last())) return null
        return TextRange(startOffset + 1, textRange.endOffset - 1)
    }

    private fun PsiElement.isRegionMarker(): Boolean {
        val content = text.removePrefix("#").trimStart()
        return content.startsWith("region") || content.startsWith("endregion") ||
            content.startsWith("<editor-fold") || content.startsWith("</editor-fold")
    }

    private fun PsiElement.indent(document: Document): Int {
        val line = document.getLineNumber(textOffset)
        return textOffset - document.getLineStartOffset(line)
    }

    private fun TextRange.isMultiline(document: Document): Boolean {
        if (isEmpty || startOffset >= document.textLength) return false
        val lastContentOffset = (endOffset - 1).coerceAtMost(document.textLength - 1)
        return lastContentOffset >= startOffset &&
            document.getLineNumber(startOffset) < document.getLineNumber(lastContentOffset)
    }

    private companion object {
        val COLON = TokenSet.create(MojoTypes.COLON)
        val TRIPLE_QUOTES = listOf("\"\"\"", "'''")

        fun matchingDelimiters(first: Char, last: Char): Boolean =
            first == '(' && last == ')' || first == '[' && last == ']' || first == '{' && last == '}'
    }
}
