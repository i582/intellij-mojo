package com.github.petrmakhnev.intellijmojo.lang.ide

import com.github.petrmakhnev.intellijmojo.lang.psi.MojoDeclarationName
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoFunctionDeclaration
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoNameIdentifier
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoStructDeclaration
import com.intellij.openapi.application.QueryExecutorBase
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.search.SingleTargetRequestResultProcessor
import com.intellij.psi.search.UsageSearchContext
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.util.Processor

class MojoConstructorReferencesSearch : QueryExecutorBase<PsiReference, ReferencesSearch.SearchParameters>(true) {
    override fun processQuery(
        queryParameters: ReferencesSearch.SearchParameters,
        consumer: Processor<in PsiReference>,
    ) {
        val constructor = queryParameters.elementToSearch.constructorDeclaration() ?: return
        if (constructor.name != CONSTRUCTOR_NAME) return

        val struct = constructor.declaringStruct() ?: return
        val structName = struct.name ?: return
        queryParameters.optimizer.searchWord(
            structName,
            queryParameters.effectiveSearchScope,
            UsageSearchContext.IN_CODE,
            true,
            constructor,
            SingleTargetRequestResultProcessor(constructor),
        )
    }

    private fun PsiElement.constructorDeclaration(): MojoFunctionDeclaration? = when (this) {
        is MojoFunctionDeclaration -> this
        is MojoNameIdentifier -> (parent as? MojoDeclarationName)?.parent as? MojoFunctionDeclaration
        else -> null
    }

    private fun MojoFunctionDeclaration.declaringStruct(): MojoStructDeclaration? {
        var ancestor = parent
        while (ancestor != null) {
            when (ancestor) {
                is MojoFunctionDeclaration -> return null
                is MojoStructDeclaration -> return ancestor
            }
            ancestor = ancestor.parent
        }
        return null
    }

    private companion object {
        const val CONSTRUCTOR_NAME = "__init__"
    }
}
