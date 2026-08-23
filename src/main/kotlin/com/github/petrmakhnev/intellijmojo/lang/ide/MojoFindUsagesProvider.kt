package com.github.petrmakhnev.intellijmojo.lang.ide

import com.github.petrmakhnev.intellijmojo.lang.lexer.MojoLexer
import com.github.petrmakhnev.intellijmojo.lang.lexer.MojoTokenSets
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoComptimeStatement
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoFunctionDeclaration
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoMlirRegionDeclaration
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoNamedElement
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoStructDeclaration
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoTraitDeclaration
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoTypes
import com.intellij.lang.HelpID
import com.intellij.lang.cacheBuilder.DefaultWordsScanner
import com.intellij.lang.cacheBuilder.WordsScanner
import com.intellij.lang.findUsages.FindUsagesProvider
import com.intellij.psi.ElementDescriptionUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.TokenSet
import com.intellij.usageView.UsageViewLongNameLocation
import com.intellij.usageView.UsageViewShortNameLocation

class MojoFindUsagesProvider : FindUsagesProvider {
    override fun getWordsScanner(): WordsScanner = DefaultWordsScanner(
        MojoLexer(),
        TokenSet.create(MojoTypes.IDENTIFIER, MojoTypes.ESCAPED_IDENTIFIER),
        MojoTokenSets.COMMENTS,
        MojoTokenSets.STRINGS,
    )

    override fun canFindUsagesFor(element: PsiElement): Boolean =
        element is MojoNamedElement && element.isFileLevel()

    override fun getHelpId(element: PsiElement): String = HelpID.FIND_OTHER_USAGES

    override fun getType(element: PsiElement): String = when (element) {
        is MojoFunctionDeclaration -> "Function"
        is MojoStructDeclaration -> "Struct"
        is MojoTraitDeclaration -> "Trait"
        is MojoComptimeStatement -> if (element.node.findChildByType(MojoTypes.TYPE) != null) "Type alias" else "Constant"
        is MojoMlirRegionDeclaration -> "MLIR symbol"
        else -> "Symbol"
    }

    override fun getDescriptiveName(element: PsiElement): String =
        ElementDescriptionUtil.getElementDescription(element, UsageViewLongNameLocation.INSTANCE)
            .ifEmpty { elementName(element) }

    override fun getNodeText(element: PsiElement, useFullName: Boolean): String =
        ElementDescriptionUtil.getElementDescription(element, UsageViewShortNameLocation.INSTANCE)
            .ifEmpty { elementName(element) }

    private fun MojoNamedElement.isFileLevel(): Boolean {
        var ancestor = parent
        while (ancestor != null && ancestor !is PsiFile) {
            if (ancestor is MojoFunctionDeclaration || ancestor is MojoStructDeclaration || ancestor is MojoTraitDeclaration) {
                return false
            }
            ancestor = ancestor.parent
        }
        return ancestor is PsiFile
    }

    private fun elementName(element: PsiElement): String = when (element) {
        is MojoNamedElement -> element.name.orEmpty()
        else -> element.text
    }
}
