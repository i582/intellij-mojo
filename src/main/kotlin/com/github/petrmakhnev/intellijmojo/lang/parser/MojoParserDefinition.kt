package com.github.petrmakhnev.intellijmojo.lang.parser

import com.github.petrmakhnev.intellijmojo.lang.MojoFileElementType
import com.github.petrmakhnev.intellijmojo.lang.MojoLanguage
import com.github.petrmakhnev.intellijmojo.lang.lexer.MojoIndentingLexer
import com.github.petrmakhnev.intellijmojo.lang.lexer.MojoTokenSets
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoFile
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoTypes
import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.TokenType
import com.intellij.psi.tree.TokenSet

class MojoParserDefinition : ParserDefinition {
    override fun createLexer(project: Project?): Lexer = MojoIndentingLexer()

    override fun createParser(project: Project?): PsiParser = MojoParser()

    override fun getFileNodeType() = MojoFileElementType

    override fun getWhitespaceTokens(): TokenSet = WHITESPACE

    override fun getCommentTokens(): TokenSet = MojoTokenSets.COMMENTS

    override fun getStringLiteralElements(): TokenSet = MojoTokenSets.STRINGS

    override fun createElement(node: ASTNode): PsiElement = MojoTypes.Factory.createElement(node)

    override fun createFile(viewProvider: FileViewProvider): PsiFile = MojoFile(viewProvider)

    override fun spaceExistenceTypeBetweenTokens(
        left: ASTNode,
        right: ASTNode,
    ): ParserDefinition.SpaceRequirements = ParserDefinition.SpaceRequirements.MAY

    companion object {
        private val WHITESPACE = TokenSet.create(TokenType.WHITE_SPACE, MojoTypes.LINE_BREAK)
    }
}
