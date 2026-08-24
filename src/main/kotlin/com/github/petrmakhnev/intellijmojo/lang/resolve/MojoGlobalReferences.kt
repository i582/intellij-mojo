package com.github.petrmakhnev.intellijmojo.lang.resolve

import com.github.petrmakhnev.intellijmojo.lang.psi.MojoMemberExpression
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoFunctionDeclaration
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoLiteralExpression
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoNameIdentifier
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoReferenceExpression
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoStructDeclaration
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoTraitDeclaration
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoTypeComposition
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoTypes
import com.github.petrmakhnev.intellijmojo.lang.type.mojoInference
import com.intellij.codeInsight.highlighting.HighlightedReference
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.PsiReference
import com.intellij.psi.ResolveResult
import com.intellij.psi.impl.source.resolve.ResolveCache
import com.intellij.psi.util.PsiTreeUtil

object MojoGlobalReferences {
    fun forExpression(expression: MojoReferenceExpression): Array<PsiReference> = arrayOf(
        MojoSymbolReference(
            expression,
            expression.nameIdentifier.rangeIn(expression),
        ) { element ->
            element.mojoInference?.getResolvedRefs(element)?.takeIf { it.isNotEmpty() }?.let { return@MojoSymbolReference it }
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
            element.mojoInference?.getResolvedRefs(element)?.takeIf { it.isNotEmpty() }?.let { return@MojoSymbolReference it }
            MojoGlobalSymbolResolver.resolveCallAware(element, MojoGlobalSymbolResolver.resolveMember(element))
        },
    )

    fun forSelf(expression: MojoLiteralExpression): Array<PsiReference> {
        if (expression.node.findChildByType(MojoTypes.SELF) == null) return PsiReference.EMPTY_ARRAY
        return arrayOf(
            MojoSymbolReference(expression, TextRange(0, expression.textLength)) { element ->
                listOfNotNull(element.enclosingSelfType())
            },
        )
    }

    fun forTypeName(name: MojoNameIdentifier): Array<PsiReference> = arrayOf(
        MojoSymbolReference(name, TextRange(0, name.textLength)) { element ->
            element.mojoInference?.getResolvedRefs(element)?.takeIf { it.isNotEmpty() }?.let { return@MojoSymbolReference it }
            val spelling = element.text.removeSurrounding("`")
            element.selfTypeComposition()?.let { owner ->
                MojoGlobalSymbolResolver.resolveTypeMember(listOf(owner), spelling)
                    .takeIf { it.isNotEmpty() }
                    ?.let { return@MojoSymbolReference it }
            }
            element.lexicalTypeParameter(spelling)?.let { return@MojoSymbolReference listOf(it) }
            MojoGlobalSymbolResolver.resolve(element, spelling)
        },
    )

    fun forIdentifier(name: MojoNameIdentifier): Array<PsiReference> = arrayOf(
        MojoSymbolReference(name, TextRange(0, name.textLength)) { element ->
            element.mojoInference?.getResolvedRefs(element).orEmpty()
        },
    )

    private fun PsiElement.rangeIn(parent: PsiElement): TextRange = textRange.shiftLeft(parent.textOffset)

    private fun PsiElement.enclosingSelfType(): PsiElement? {
        var ancestor = parent
        while (ancestor != null) {
            when (ancestor) {
                is MojoStructDeclaration, is MojoTraitDeclaration -> return ancestor
            }
            ancestor = ancestor.parent
        }
        return null
    }

    private fun MojoNameIdentifier.selfTypeComposition(): PsiElement? {
        val composition = PsiTreeUtil.getParentOfType(this, MojoTypeComposition::class.java, false)
            ?: return null
        if (composition.nameIdentifierList.lastOrNull() != this) return null
        val hasSelf = PsiTreeUtil.getChildOfType(composition, MojoLiteralExpression::class.java)
            ?.node
            ?.findChildByType(MojoTypes.SELF) != null
        return if (hasSelf) enclosingSelfType() else null
    }

    private class MojoSymbolReference<T : PsiElement>(
        element: T,
        range: TextRange,
        private val resolver: (T) -> List<PsiElement>,
    ) : PsiPolyVariantReferenceBase<T>(element, range, false), HighlightedReference {
        private val cacheResolver = ResolveCache.PolyVariantResolver<MojoSymbolReference<T>> { reference, _ ->
            reference.resolveUncached()
        }

        override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
            if (!element.isValid) return ResolveResult.EMPTY_ARRAY
            return ResolveCache.getInstance(element.project)
                .resolveWithCaching(this, cacheResolver, false, incompleteCode)
        }

        override fun isReferenceTo(target: PsiElement): Boolean {
            if (!element.isValid) return false
            return resolver(element).any { resolved ->
                element.manager.areElementsEquivalent(resolved, target) ||
                    resolved.constructorOwner()?.let { element.manager.areElementsEquivalent(it, target) } == true
            }
        }

        override fun getVariants(): Array<Any> = emptyArray()

        private fun resolveUncached(): Array<ResolveResult> =
            resolver(element).map(::PsiElementResolveResult).toTypedArray()

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
