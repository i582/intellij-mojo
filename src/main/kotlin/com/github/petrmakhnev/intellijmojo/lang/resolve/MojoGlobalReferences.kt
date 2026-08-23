package com.github.petrmakhnev.intellijmojo.lang.resolve

import com.github.petrmakhnev.intellijmojo.lang.psi.MojoMemberExpression
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoFunctionDeclaration
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoNameIdentifier
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoReferenceExpression
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoStructDeclaration
import com.intellij.codeInsight.highlighting.HighlightedReference
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.PsiReference
import com.intellij.psi.ResolveResult

object MojoGlobalReferences {
    fun forExpression(expression: MojoReferenceExpression): Array<PsiReference> = arrayOf(
        MojoSymbolReference(
            expression,
            expression.nameIdentifier.rangeIn(expression),
        ) { element ->
            val targets = MojoGlobalSymbolResolver.resolve(
                element,
                element.nameIdentifier.text.removeSurrounding("`"),
            )
            MojoGlobalSymbolResolver.resolveCallAware(element, targets)
        },
    )

    fun forMember(expression: MojoMemberExpression): Array<PsiReference> = arrayOf(
        MojoSymbolReference(
            expression,
            expression.nameIdentifier.rangeIn(expression),
        ) { element ->
            MojoGlobalSymbolResolver.resolveCallAware(element, MojoGlobalSymbolResolver.resolveMember(element))
        },
    )

    fun forTypeName(name: MojoNameIdentifier): Array<PsiReference> = arrayOf(
        MojoSymbolReference(name, TextRange(0, name.textLength)) { element ->
            MojoGlobalSymbolResolver.resolve(element, element.text.removeSurrounding("`"))
        },
    )

    private fun PsiElement.rangeIn(parent: PsiElement): TextRange = textRange.shiftLeft(parent.textOffset)

    private class MojoSymbolReference<T : PsiElement>(
        element: T,
        range: TextRange,
        private val resolver: (T) -> List<PsiElement>,
    ) : PsiPolyVariantReferenceBase<T>(element, range, false), HighlightedReference {
        override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
            if (!element.isValid) return ResolveResult.EMPTY_ARRAY
            return resolver(element).map(::PsiElementResolveResult).toTypedArray()
        }

        override fun isReferenceTo(target: PsiElement): Boolean {
            if (!element.isValid) return false
            return resolver(element).any { resolved ->
                element.manager.areElementsEquivalent(resolved, target) ||
                    resolved.constructorOwner()?.let { element.manager.areElementsEquivalent(it, target) } == true
            }
        }

        override fun getVariants(): Array<Any> = emptyArray()

        private fun PsiElement.constructorOwner(): MojoStructDeclaration? {
            val constructor = this as? MojoFunctionDeclaration ?: return null
            if (constructor.name != "__init__") return null
            var ancestor = constructor.parent
            while (ancestor != null) {
                when (ancestor) {
                    is MojoFunctionDeclaration -> return null
                    is MojoStructDeclaration -> return ancestor
                }
                ancestor = ancestor.parent
            }
            return null
        }
    }
}
