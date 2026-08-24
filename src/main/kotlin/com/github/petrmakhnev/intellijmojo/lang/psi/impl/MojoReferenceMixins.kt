package com.github.petrmakhnev.intellijmojo.lang.psi.impl

import com.github.petrmakhnev.intellijmojo.lang.MojoFileType
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoDeclarationName
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoDottedName
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoLiteralExpression
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoMemberExpression
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoNameIdentifier
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoNamedParameter
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoParameterDeclaration
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoReferenceExpression
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoTypeExpression
import com.github.petrmakhnev.intellijmojo.lang.resolve.MojoGlobalReferences
import com.github.petrmakhnev.intellijmojo.lang.resolve.MojoNamedArgumentReferences
import com.github.petrmakhnev.intellijmojo.lang.type.mojoInference
import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiNamedElement
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

abstract class MojoLiteralExpressionMixin(node: ASTNode) :
    MojoExpressionImpl(node),
    MojoLiteralExpression {
    override fun getReferences(): Array<PsiReference> = MojoGlobalReferences.forSelf(this)

    override fun getReference(): PsiReference? = references.singleOrNull()
}

abstract class MojoNameIdentifierMixin(node: ASTNode) :
    ASTWrapperPsiElement(node),
    MojoNameIdentifier,
    PsiNamedElement {
    override fun getReferences(): Array<PsiReference> = when {
        parent is MojoNamedParameter -> MojoNamedArgumentReferences.forName(this)
        isTypeReference() -> MojoGlobalReferences.forTypeName(this)
        mojoInference?.getResolvedRefs(this)?.any { it != this } == true -> MojoGlobalReferences.forIdentifier(this)
        else -> PsiReference.EMPTY_ARRAY
    }

    override fun getReference(): PsiReference? = references.singleOrNull()

    override fun getName(): String = text.removeSurrounding("`")

    override fun setName(name: String): PsiElement {
        val replacement = PsiFileFactory.getInstance(project)
            .createFileFromText("rename.mojo", MojoFileType, "def $name():\n    pass\n")
            .let { PsiTreeUtil.findChildOfType(it, MojoNameIdentifier::class.java) }
        return replacement?.let { replace(it) } ?: this
    }

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
