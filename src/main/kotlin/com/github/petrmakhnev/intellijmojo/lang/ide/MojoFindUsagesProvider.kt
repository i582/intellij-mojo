package com.github.petrmakhnev.intellijmojo.lang.ide

import com.github.petrmakhnev.intellijmojo.lang.lexer.MojoLexer
import com.github.petrmakhnev.intellijmojo.lang.lexer.MojoTokenSets
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoComptimeStatement
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoFunctionDeclaration
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoMlirRegionDeclaration
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoNamedElement
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoNameIdentifier
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoStructDeclaration
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoTraitDeclaration
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoTypes
import com.github.petrmakhnev.intellijmojo.lang.resolve.MojoBindingKind
import com.github.petrmakhnev.intellijmojo.lang.resolve.staticBindingKind
import com.github.petrmakhnev.intellijmojo.lang.type.mojoInference
import com.github.petrmakhnev.intellijmojo.lang.type.isTypeValuedComptime
import com.intellij.find.findUsages.FindUsagesHandler
import com.intellij.find.findUsages.FindUsagesHandlerFactory
import com.intellij.lang.HelpID
import com.intellij.lang.cacheBuilder.DefaultWordsScanner
import com.intellij.lang.cacheBuilder.WordsScanner
import com.intellij.lang.findUsages.FindUsagesProvider
import com.intellij.psi.ElementDescriptionUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.usageView.UsageViewLongNameLocation
import com.intellij.usageView.UsageViewShortNameLocation

class MojoFindUsagesProvider : FindUsagesProvider {
    override fun getWordsScanner(): WordsScanner = DefaultWordsScanner(
        MojoLexer(),
        TokenSet.create(MojoTypes.IDENTIFIER, MojoTypes.ESCAPED_IDENTIFIER),
        MojoTokenSets.COMMENTS,
        MojoTokenSets.STRINGS,
    )

    override fun canFindUsagesFor(element: PsiElement): Boolean = when (val target = element.mojoFindUsagesTarget()) {
        is MojoNamedElement -> true
        is MojoNameIdentifier -> target.isLocalDeclaration()
        else -> false
    }

    override fun getHelpId(element: PsiElement): String = HelpID.FIND_OTHER_USAGES

    override fun getType(element: PsiElement): String = when (val target = element.mojoFindUsagesTarget()) {
        is MojoFunctionDeclaration -> "Function"
        is MojoStructDeclaration -> "Struct"
        is MojoTraitDeclaration -> "Trait"
        is MojoComptimeStatement -> if (target.isTypeValuedComptime()) "Type alias" else "Constant"
        is MojoMlirRegionDeclaration -> "MLIR symbol"
        is MojoNameIdentifier -> when (target.staticBindingKind()) {
            MojoBindingKind.TYPE_PARAMETER -> "Type parameter"
            MojoBindingKind.PARAMETER -> "Parameter"
            MojoBindingKind.CAPTURE_PARAMETER -> "Capture"
            else -> "Variable"
        }
        else -> "Symbol"
    }

    override fun getDescriptiveName(element: PsiElement): String =
        element.mojoFindUsagesTarget().let { target ->
            ElementDescriptionUtil.getElementDescription(target, UsageViewLongNameLocation.INSTANCE)
                .ifEmpty { elementName(target) }
        }

    override fun getNodeText(element: PsiElement, useFullName: Boolean): String =
        element.mojoFindUsagesTarget().let { target ->
            ElementDescriptionUtil.getElementDescription(target, UsageViewShortNameLocation.INSTANCE)
                .ifEmpty { elementName(target) }
        }

    private fun elementName(element: PsiElement): String = when (element) {
        is MojoNamedElement -> element.name.orEmpty()
        is MojoNameIdentifier -> element.text.removeSurrounding("`")
        else -> element.text
    }

    private fun MojoNameIdentifier.isLocalDeclaration(): Boolean =
        staticBindingKind() != null || mojoInference?.isLocalDeclaration(this) == true
}

class MojoFindUsagesHandlerFactory : FindUsagesHandlerFactory() {
    override fun canFindUsages(element: PsiElement): Boolean =
        element.mojoFindUsagesTarget() !== element

    override fun createFindUsagesHandler(element: PsiElement, forHighlightUsages: Boolean): FindUsagesHandler =
        object : FindUsagesHandler(element.mojoFindUsagesTarget()) {}
}

private fun PsiElement.mojoFindUsagesTarget(): PsiElement {
    val identifier = this as? MojoNameIdentifier ?: return this
    val enclosingOwner = PsiTreeUtil.getParentOfType(identifier, MojoNamedElement::class.java, false)
    return enclosingOwner?.takeIf { it.nameIdentifier === identifier } ?: this
}
