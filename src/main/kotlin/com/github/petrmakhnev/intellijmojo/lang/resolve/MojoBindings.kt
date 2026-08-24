package com.github.petrmakhnev.intellijmojo.lang.resolve

import com.github.petrmakhnev.intellijmojo.lang.psi.*
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import java.util.LinkedHashSet

internal enum class MojoBindingKind {
    TYPE_PARAMETER,
    PARAMETER,
    CAPTURE_PARAMETER,
    VARIABLE,
    LOOP_VARIABLE,
    COMPREHENSION_VARIABLE,
    WITH_VARIABLE,
    EXCEPTION_VARIABLE,
    MATCH_VARIABLE,
}

internal fun MojoNameIdentifier.staticBindingKind(): MojoBindingKind? {
    val parameter = parent as? MojoParameterDeclaration
    if (parameter?.nameIdentifier == this) {
        return when (parameter.parent) {
            is MojoTypeParameterList -> MojoBindingKind.TYPE_PARAMETER
            is MojoCaptureParameter -> MojoBindingKind.CAPTURE_PARAMETER
            else -> MojoBindingKind.PARAMETER
        }
    }

    val assignmentExpression = parent as? MojoAssignmentExpression
    if (assignmentExpression?.nameIdentifier == this &&
        (assignmentExpression.hasToken(MojoTypes.VAR) || assignmentExpression.hasToken(MojoTypes.REF))
    ) {
        return MojoBindingKind.VARIABLE
    }

    val assignment = PsiTreeUtil.getParentOfType(this, MojoAssignmentStatement::class.java, false)
    if (assignment != null && this in assignment.bindingIdentifiers() &&
        (assignment.hasToken(MojoTypes.VAR) || assignment.hasToken(MojoTypes.REF))
    ) {
        return MojoBindingKind.VARIABLE
    }

    val loop = PsiTreeUtil.getParentOfType(this, MojoForStatement::class.java, false)
    if (loop?.patternList?.bindingIdentifiers()?.contains(this) == true) {
        return MojoBindingKind.LOOP_VARIABLE
    }

    val comprehension = PsiTreeUtil.getParentOfType(this, MojoForInClause::class.java, false)
    if (comprehension?.patternList?.bindingIdentifiers()?.contains(this) == true) {
        return MojoBindingKind.COMPREHENSION_VARIABLE
    }

    val withItem = PsiTreeUtil.getParentOfType(this, MojoWithItem::class.java, false)
    val withTarget = withItem?.expressionList?.getOrNull(1) as? MojoReferenceExpression
    if (withTarget?.nameIdentifier == this) return MojoBindingKind.WITH_VARIABLE

    val exceptClause = PsiTreeUtil.getParentOfType(this, MojoExceptClause::class.java, false)
    val exceptTarget = exceptClause?.expression as? MojoReferenceExpression
    if (exceptTarget?.nameIdentifier == this) return MojoBindingKind.EXCEPTION_VARIABLE

    val caseClause = PsiTreeUtil.getParentOfType(this, MojoCaseClause::class.java, false)
    if (caseClause?.casePatternList?.bindingIdentifiers()?.contains(this) == true) {
        return MojoBindingKind.MATCH_VARIABLE
    }

    return null
}

internal fun MojoAssignmentStatement.bindingIdentifiers(): List<MojoNameIdentifier> {
    patternList?.let { return it.bindingIdentifiers() }

    val boundary = minOf(
        typeExpression?.textOffset ?: Int.MAX_VALUE,
        node.findChildByType(MojoTypes.EQ)?.startOffset ?: Int.MAX_VALUE,
    )
    val targetChildren = generateSequence(firstChild) { it.nextSibling }
        .takeWhile { it.textOffset < boundary }
        .toList()

    targetChildren.filterIsInstance<MojoParenthesizedExpression>()
        .firstOrNull()
        ?.let { return it.assignmentPatternIdentifiers() }
    targetChildren.filterIsInstance<MojoListExpression>()
        .firstOrNull()
        ?.let { return it.assignmentPatternIdentifiers() }

    if (targetChildren.any { child ->
            child is MojoArgumentList || child is MojoSubscriptItem ||
                child.node.elementType == MojoTypes.DOT || child.node.elementType == MojoTypes.CARET
        }
    ) {
        return emptyList()
    }

    return targetChildren.filterIsInstance<MojoNameIdentifier>().singleOrNull()?.let(::listOf).orEmpty()
}

internal fun PsiElement.bindingIdentifiers(): List<MojoNameIdentifier> {
    val result = LinkedHashSet<MojoNameIdentifier>()
    when (this) {
        is MojoPatternList -> patternList.forEach { it.collectAssignmentBindings(result) }
        is MojoPattern -> collectAssignmentBindings(result)
        is MojoCasePatternList -> collectCaseBindings(result)
        is MojoCasePattern -> collectCaseBindings(result)
        else -> collectCaseBindings(result)
    }
    return result.toList()
}

internal fun MojoAssignmentStatement.assignmentTargetChains(): List<List<MojoNameIdentifier>> {
    val patterns = patternList?.let { PsiTreeUtil.findChildrenOfType(it, MojoPattern::class.java) }.orEmpty()
    return patterns.mapNotNull { pattern ->
        val names = pattern.nameIdentifierList
        val hasPostfix = names.size > 1 ||
            pattern.argumentListList.isNotEmpty() ||
            pattern.subscriptItemList.isNotEmpty() ||
            pattern.node.findChildByType(MojoTypes.DOT) != null ||
            pattern.node.findChildByType(MojoTypes.CARET) != null
        names.takeIf { hasPostfix && it.isNotEmpty() }
    }.distinctBy { chain -> chain.joinToString(":") { it.textOffset.toString() } }
}

internal fun MojoFunctionDeclaration.enclosingTypeParameters(): List<MojoParameterDeclaration> {
    val owners = mutableListOf<PsiElement>()
    var current = parent
    while (current != null && current !is PsiFile && current !is MojoFunctionDeclaration) {
        if (current is MojoStructDeclaration || current is MojoTraitDeclaration || current is MojoExtensionDeclaration) {
            owners += current
        }
        current = current.parent
    }

    return owners.asReversed().flatMap { enclosing ->
        when (enclosing) {
            is MojoStructDeclaration -> enclosing.structHeader.typeParameterList?.parameterDeclarationList.orEmpty()
            is MojoTraitDeclaration -> enclosing.typeParameterList?.parameterDeclarationList.orEmpty()
            is MojoExtensionDeclaration -> enclosing.typeParameterList?.parameterDeclarationList.orEmpty()
            else -> emptyList()
        }
    }
}

internal fun PsiElement.lexicalTypeParameter(name: String): MojoNameIdentifier? {
    val normalized = name.removeSurrounding("`")
    var current: PsiElement? = this
    while (current != null && current !is PsiFile) {
        val parameters = when (current) {
            is MojoFunctionDeclaration ->
                current.functionSignature.typeParameterList?.parameterDeclarationList.orEmpty()
            is MojoStructDeclaration ->
                current.structHeader.typeParameterList?.parameterDeclarationList.orEmpty()
            is MojoTraitDeclaration ->
                current.typeParameterList?.parameterDeclarationList.orEmpty()
            is MojoExtensionDeclaration ->
                current.typeParameterList?.parameterDeclarationList.orEmpty()
            else -> emptyList()
        }
        parameters.firstOrNull { it.nameIdentifier?.identifierText() == normalized }
            ?.nameIdentifier
            ?.let { return it }
        current = current.parent
    }
    return null
}

private fun MojoPattern.collectAssignmentBindings(result: MutableSet<MojoNameIdentifier>) {
    listPattern?.casePatternList?.collectCaseBindings(result)
    tuplePattern?.casePatternList?.collectCaseBindings(result)
    splatPattern?.nameIdentifier?.let(result::add)
    if (listPattern != null || tuplePattern != null || splatPattern != null) return

    if (argumentListList.isNotEmpty() || subscriptItemList.isNotEmpty() ||
        node.findChildByType(MojoTypes.DOT) != null || node.findChildByType(MojoTypes.CARET) != null
    ) {
        return
    }
    nameIdentifierList.singleOrNull()?.let(result::add)
}

private fun MojoExpression.assignmentPatternIdentifiers(): List<MojoNameIdentifier> {
    val result = LinkedHashSet<MojoNameIdentifier>()
    fun collect(expression: MojoExpression) {
        when (expression) {
            is MojoReferenceExpression -> result += expression.nameIdentifier
            is MojoParenthesizedExpression -> expression.expressionList.forEach(::collect)
            is MojoListExpression -> expression.expressionList.forEach(::collect)
        }
    }
    collect(this)
    return result.toList()
}

private fun PsiElement.collectCaseBindings(result: MutableSet<MojoNameIdentifier>) {
    when (this) {
        is MojoClassPattern -> {
            casePatternList?.collectCaseBindings(result)
            return
        }
        is MojoSplatPattern -> {
            nameIdentifier?.let(result::add)
            return
        }
        is MojoDottedName -> {
            nameIdentifierList.singleOrNull()?.let(result::add)
            return
        }
        is MojoCasePattern -> nameIdentifier?.let(result::add)
    }

    var child = firstChild
    while (child != null) {
        if (this !is MojoCasePattern || child != nameIdentifier) child.collectCaseBindings(result)
        child = child.nextSibling
    }
}
