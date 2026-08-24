package com.github.petrmakhnev.intellijmojo.lang.psi

import com.intellij.openapi.util.SimpleModificationTracker
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil

internal class MojoFunctionModificationTracker : SimpleModificationTracker() {
    fun markChanged() {
        incModificationCount()
    }
}

internal fun PsiElement.findContainingMojoFunction(strict: Boolean = false): MojoFunctionDeclaration? {
    var current: PsiElement? = if (strict) parent else this
    while (current != null && current !is PsiFile) {
        if (current is MojoFunctionDeclaration) return current
        current = current.parent
    }
    return null
}

internal fun MojoFunctionDeclaration.containsBodyElement(element: PsiElement): Boolean {
    if (element == this || !PsiTreeUtil.isAncestor(this, element, false)) return false

    val signature = functionSignature
    return element != signature && !PsiTreeUtil.isAncestor(signature, element, false)
}
