package com.github.petrmakhnev.intellijmojo.lang.ide.hints

import com.github.petrmakhnev.intellijmojo.MojoBundle
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoCallExpression
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoExpression
import com.github.petrmakhnev.intellijmojo.lang.type.MojoTy
import com.github.petrmakhnev.intellijmojo.lang.type.displayName
import com.github.petrmakhnev.intellijmojo.lang.type.inferredType
import com.intellij.lang.ExpressionTypeProvider
import com.intellij.openapi.util.NlsContexts
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.util.parents

class MojoTypeProvider : ExpressionTypeProvider<MojoExpression>() {
    override fun getInformationHint(elementAt: MojoExpression): @NlsContexts.HintText String =
        StringUtil.escapeXmlEntities((elementAt.inferredType ?: MojoTy.Unknown).displayName)

    override fun getErrorHint(): @NlsContexts.HintText String =
        MojoBundle["codeInsight.hint.error.hint"]

    override fun getExpressionsAt(elementAt: PsiElement): List<MojoExpression> =
        elementAt.parents(true)
            .filterIsInstance<MojoExpression>()
            .filterNot { expression ->
                val call = expression.parent as? MojoCallExpression
                call?.expression == expression
            }
            .toList()
}
