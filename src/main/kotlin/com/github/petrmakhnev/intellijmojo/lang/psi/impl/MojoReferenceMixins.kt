package com.github.petrmakhnev.intellijmojo.lang.psi.impl

import com.github.petrmakhnev.intellijmojo.lang.psi.MojoDeclarationName
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoDottedName
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoMemberExpression
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoNameIdentifier
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoParameterDeclaration
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoReferenceExpression
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoTypeExpression
import com.github.petrmakhnev.intellijmojo.lang.resolve.MojoGlobalReferences
import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.psi.PsiReference
import com.intellij.psi.util.PsiTreeUtil

abstract class MojoReferenceExpressionMixin(node: ASTNode) :
    MojoExpressionImpl(node),
    MojoReferenceExpression {
    override fun getReferences(): Array<PsiReference> = MojoGlobalReferences.forExpression(this)

    override fun getReference(): PsiReference = references.single()
}

abstract class MojoMemberExpressionMixin(node: ASTNode) :
    MojoExpressionImpl(node),
    MojoMemberExpression {
    override fun getReferences(): Array<PsiReference> = MojoGlobalReferences.forMember(this)

    override fun getReference(): PsiReference = references.single()
}

abstract class MojoNameIdentifierMixin(node: ASTNode) : ASTWrapperPsiElement(node), MojoNameIdentifier {
    override fun getReferences(): Array<PsiReference> =
        if (isTypeReference()) MojoGlobalReferences.forTypeName(this) else PsiReference.EMPTY_ARRAY

    override fun getReference(): PsiReference? = references.singleOrNull()

    private fun isTypeReference(): Boolean {
        when (val parent = parent) {
            is MojoReferenceExpression,
            is MojoMemberExpression,
            is MojoDottedName,
            is MojoDeclarationName,
            -> return false

            is MojoParameterDeclaration -> if (parent.nameIdentifier == this) return false
        }
        return PsiTreeUtil.getParentOfType(this, MojoTypeExpression::class.java, false) != null
    }
}
