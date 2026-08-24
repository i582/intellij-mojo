package com.github.petrmakhnev.intellijmojo.lang.type

import com.github.petrmakhnev.intellijmojo.lang.psi.MojoComptimeStatement
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoExpression
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoLiteralExpression
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoMemberExpression
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoNameIdentifier
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoParenthesizedExpression
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoReferenceExpression
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoStructDeclaration
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoSubscriptExpression
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoTraitDeclaration
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoTransferExpression
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoTypes
import com.github.petrmakhnev.intellijmojo.lang.resolve.hasToken
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.util.PsiTreeUtil

internal fun MojoComptimeStatement.isTypeValuedComptime(
    visited: MutableSet<MojoComptimeStatement> = linkedSetOf(),
): Boolean {
    if (hasToken(MojoTypes.TYPE)) return true
    if (mojoInference?.getType(this) is MojoTy.TypeValue) return true
    if (!visited.add(this)) return false
    if (functionType != null) return true

    val equalsOffset = node.findChildByType(MojoTypes.EQ)?.startOffset
    if (equalsOffset != null && typeExpressionList.any { it.textOffset > equalsOffset }) return true
    val declaredType = typeExpressionList.firstOrNull { equalsOffset == null || it.textOffset < equalsOffset }
    if (declaredType != null && PsiTreeUtil.findChildrenOfType(declaredType, MojoNameIdentifier::class.java).any { name ->
        name.text.removeSurrounding("`") == "AnyType" || name.resolvedTargets().any { it is MojoTraitDeclaration }
    }) {
        return true
    }

    return expression?.isTypeValueExpression(visited) == true
}

private fun MojoExpression.isTypeValueExpression(visited: MutableSet<MojoComptimeStatement>): Boolean = when (this) {
    is MojoLiteralExpression -> hasToken(MojoTypes.SELF)
    is MojoReferenceExpression -> nameIdentifier.text.removeSurrounding("`") in MOJO_BUILTIN_TYPE_NAMES ||
        resolvedTargets().any { it.representsType(visited) }
    is MojoMemberExpression -> resolvedTargets().any { it.representsType(visited) }
    is MojoSubscriptExpression -> expression.isTypeValueExpression(visited)
    is MojoTransferExpression -> expression.isTypeValueExpression(visited)
    is MojoParenthesizedExpression -> expressionList.singleOrNull()?.isTypeValueExpression(visited) == true
    else -> false
}

private fun PsiElement.representsType(visited: MutableSet<MojoComptimeStatement>): Boolean =
    this is MojoStructDeclaration || this is MojoTraitDeclaration ||
        this is MojoComptimeStatement && isTypeValuedComptime(visited)

private fun PsiElement.resolvedTargets(): List<PsiElement> = references.flatMap { reference ->
    if (reference is PsiPolyVariantReference) {
        reference.multiResolve(false).mapNotNull { it.element }
    } else {
        listOfNotNull(reference.resolve())
    }
}.distinct()

internal val MOJO_BUILTIN_TYPE_NAMES = setOf(
    "AnyType",
    "Bool",
    "Byte",
    "DType",
    "SIMD",
    "SIMDLength",
    "Scalar",
    "Int",
    "Int8",
    "Int16",
    "Int32",
    "Int64",
    "Int128",
    "Int256",
    "UInt",
    "UInt8",
    "UInt16",
    "UInt32",
    "UInt64",
    "UInt128",
    "UInt256",
    "BFloat16",
    "Float8_e4m3fn",
    "Float8_e4m3fnuz",
    "Float8_e5m2",
    "Float8_e5m2fnuz",
    "Float8_e8m0fnu",
    "Float16",
    "Float32",
    "Float64",
    "String",
    "StringLiteral",
    "IntLiteral",
    "FloatLiteral",
    "NoneType",
    "Never",
    "Tuple",
    "List",
    "Dict",
)
