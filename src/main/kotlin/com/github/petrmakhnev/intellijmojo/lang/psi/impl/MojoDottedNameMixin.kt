package com.github.petrmakhnev.intellijmojo.lang.psi.impl

import com.github.petrmakhnev.intellijmojo.lang.psi.MojoDottedName
import com.github.petrmakhnev.intellijmojo.lang.resolve.MojoImportReferences
import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.psi.PsiReference

abstract class MojoDottedNameMixin(node: ASTNode) : ASTWrapperPsiElement(node), MojoDottedName {
    override fun getReferences(): Array<PsiReference> = MojoImportReferences.create(this)

    override fun getReference(): PsiReference? = references.lastOrNull()
}
