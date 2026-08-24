package com.github.petrmakhnev.intellijmojo.lang.resolve

import com.github.petrmakhnev.intellijmojo.lang.psi.MojoArgument
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoCallExpression
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoExpression
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoFunctionDeclaration
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoNameIdentifier
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoNamedParameter
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoParameterDeclaration
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoStructDeclaration
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoSubscriptExpression
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoSubscriptItem
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoTraitDeclaration
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoTransferExpression
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoTypeComposition
import com.github.petrmakhnev.intellijmojo.lang.type.mojoInference
import com.intellij.codeInsight.highlighting.HighlightedReference
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.PsiReference
import com.intellij.psi.ResolveResult
import com.intellij.psi.impl.source.resolve.ResolveCache

object MojoNamedArgumentReferences {
    fun forName(name: MojoNameIdentifier): Array<PsiReference> {
        val argument = name.parent as? MojoNamedParameter ?: return PsiReference.EMPTY_ARRAY
        if (argument.parent !is MojoArgument && argument.parent !is MojoSubscriptItem) {
            return PsiReference.EMPTY_ARRAY
        }
        return arrayOf(MojoNamedArgumentReference(name))
    }

    private fun resolve(name: MojoNameIdentifier): List<PsiElement> {
        name.mojoInference?.getResolvedRefs(name)
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }

        val argument = name.parent as? MojoNamedParameter ?: return emptyList()
        val targets = when (val container = argument.parent) {
            is MojoArgument -> resolveCallArgument(name, container)
            is MojoSubscriptItem -> resolveTypeArgument(name, container)
            else -> emptyList()
        }
        return targets.distinct()
    }

    private fun resolveCallArgument(name: MojoNameIdentifier, argument: MojoArgument): List<PsiElement> {
        val call = generateSequence(argument.parent) { it.parent }
            .filterIsInstance<MojoCallExpression>()
            .firstOrNull()
            ?: return emptyList()
        val selected = call.mojoInference?.getResolvedCall(call).orEmpty()
        val declarations = selected.takeIf { it.isNotEmpty() }
            ?: resolveExpressionTargets(call.expression).flatMap(::callableDeclarations)
        val spelling = name.identifierText()
        return declarations.flatMap { function ->
            function.functionSignature.callableParameters
                ?.parameterDeclarationList
                .orEmpty()
                .filter { it.nameIdentifier?.identifierText() == spelling }
                .mapNotNull { it.nameIdentifier }
        }
    }

    private fun resolveTypeArgument(name: MojoNameIdentifier, item: MojoSubscriptItem): List<PsiElement> {
        val owner = generateSequence(item.parent) { it.parent }
            .firstOrNull { it is MojoSubscriptExpression || it is MojoTypeComposition }
            ?: return emptyList()
        val declarations = when (owner) {
            is MojoSubscriptExpression -> resolveExpressionTargets(owner.expression)
            is MojoTypeComposition -> resolveTypeCompositionTargets(owner)
            else -> emptyList()
        }
        val spelling = name.identifierText()
        return declarations.flatMap(::typeParameterDeclarations)
            .filter { it.nameIdentifier?.identifierText() == spelling }
            .mapNotNull { it.nameIdentifier }
    }

    private fun resolveTypeCompositionTargets(composition: MojoTypeComposition): List<PsiElement> {
        val identifier = composition.nameIdentifierList.lastOrNull() ?: return emptyList()
        val resolved = resolveReferences(identifier)
        return resolved.ifEmpty {
            MojoGlobalSymbolResolver.resolve(identifier, identifier.identifierText())
        }
    }

    private fun resolveExpressionTargets(expression: MojoExpression): List<PsiElement> {
        val resolved = resolveReferences(expression)
        if (resolved.isNotEmpty()) return resolved
        return when (expression) {
            is MojoSubscriptExpression -> resolveExpressionTargets(expression.expression)
            is MojoTransferExpression -> resolveExpressionTargets(expression.expression)
            else -> emptyList()
        }
    }

    private fun resolveReferences(element: PsiElement): List<PsiElement> =
        element.references.flatMap { reference ->
            if (reference is PsiPolyVariantReference) {
                reference.multiResolve(false).mapNotNull { it.element }
            } else {
                listOfNotNull(reference.resolve())
            }
        }

    private fun callableDeclarations(target: PsiElement): List<MojoFunctionDeclaration> = when (target) {
        is MojoFunctionDeclaration -> listOf(target)
        is MojoStructDeclaration -> MojoGlobalSymbolResolver.resolveTypeMember(listOf(target), "__init__")
            .filterIsInstance<MojoFunctionDeclaration>()
        else -> emptyList()
    }

    private fun typeParameterDeclarations(target: PsiElement): List<MojoParameterDeclaration> = when (target) {
        is MojoStructDeclaration -> target.structHeader.typeParameterList?.parameterDeclarationList.orEmpty()
        is MojoTraitDeclaration -> target.typeParameterList?.parameterDeclarationList.orEmpty()
        is MojoFunctionDeclaration -> if (target.name == "__init__") {
            target.enclosingTypeParameters()
        } else {
            target.functionSignature.typeParameterList?.parameterDeclarationList.orEmpty()
        }
        else -> emptyList()
    }

    private class MojoNamedArgumentReference(element: MojoNameIdentifier) :
        PsiPolyVariantReferenceBase<MojoNameIdentifier>(element, TextRange(0, element.textLength), false),
        HighlightedReference {
        private val resolver = ResolveCache.PolyVariantResolver<MojoNamedArgumentReference> { reference, _ ->
            reference.resolveUncached()
        }

        override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
            if (!element.isValid) return ResolveResult.EMPTY_ARRAY
            return ResolveCache.getInstance(element.project)
                .resolveWithCaching(this, resolver, false, incompleteCode)
        }

        override fun isReferenceTo(target: PsiElement): Boolean {
            val normalized = (target as? MojoParameterDeclaration)?.nameIdentifier ?: target
            return multiResolve(false).any { result ->
                result.element?.let { element.manager.areElementsEquivalent(it, normalized) } == true
            }
        }

        override fun getVariants(): Array<Any> = emptyArray()

        private fun resolveUncached(): Array<ResolveResult> =
            resolve(element).map(::PsiElementResolveResult).toTypedArray()
    }
}
