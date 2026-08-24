package com.github.petrmakhnev.intellijmojo.lang.resolve

import com.github.petrmakhnev.intellijmojo.lang.psi.*
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import java.util.ArrayDeque
import java.util.LinkedHashMap

internal data class MojoLocalResolution(
    val resolvedReferences: Map<PsiElement, List<PsiElement>>,
    val nestedEnvironments: Map<PsiElement, Map<String, PsiElement>>,
    val declarations: Set<PsiElement>,
)

internal class MojoLocalResolver(
    private val owner: PsiElement,
    initialBindings: Map<String, PsiElement> = emptyMap(),
) {
    private val scopes = ArrayDeque<MutableMap<String, PsiElement>>()
    private val resolvedReferences = LinkedHashMap<PsiElement, List<PsiElement>>()
    private val nestedEnvironments = LinkedHashMap<PsiElement, Map<String, PsiElement>>()
    private val declarations = linkedSetOf<PsiElement>()
    private lateinit var functionScope: MutableMap<String, PsiElement>

    init {
        scopes.addLast(initialBindings.toMutableMap())
    }

    fun resolve(): MojoLocalResolution {
        withScope {
            functionScope = scopes.last()
            when (owner) {
                is MojoFunctionDeclaration -> {
                    owner.name?.let { functionScope[it] = owner }
                    owner.enclosingTypeParameters().forEach(::addParameter)
                    seedParameters(owner.functionSignature.typeParameterList)
                    seedParameters(owner.functionSignature.callableParameters)
                    seedCaptureParameters(owner.functionSignature.captureParameters)
                    visit(owner.functionSignature)
                    visitChildren(owner, setOf(owner.functionSignature))
                }
                is MojoMlirRegionDeclaration -> {
                    owner.name?.let { functionScope[it] = owner }
                    seedParameters(owner.callableParameters)
                    visit(owner.callableParameters)
                    visitChildren(owner, setOfNotNull(owner.callableParameters))
                }
            }
        }
        return MojoLocalResolution(resolvedReferences, nestedEnvironments, declarations)
    }

    private fun visit(element: PsiElement?) {
        when (element) {
            null -> Unit
            is PsiFile -> visitChildren(element)
            is MojoReferenceExpression -> resolveReference(element, element.nameIdentifier.text)
            is MojoMemberExpression -> visit(element.expression)
            is MojoNameIdentifier -> resolveTypeReference(element)
            is MojoFunctionDeclaration -> visitNestedFunction(element)
            is MojoStructDeclaration -> addNamedDeclaration(element)
            is MojoTraitDeclaration -> addNamedDeclaration(element)
            is MojoMlirRegionDeclaration -> visitNestedRegion(element)
            is MojoComptimeStatement -> visitComptimeDeclaration(element)
            is MojoAssignmentStatement -> visitAssignment(element)
            is MojoAugmentedAssignmentStatement -> visitAugmentedAssignment(element)
            is MojoAssignmentExpression -> visitAssignmentExpression(element)
            is MojoForStatement -> visitForStatement(element)
            is MojoIfStatement -> visitIfStatement(element)
            is MojoWhileStatement -> visitWhileStatement(element)
            is MojoTryStatement -> visitTryStatement(element)
            is MojoWithStatement -> visitWithStatement(element)
            is MojoMatchStatement -> visitMatchStatement(element)
            is MojoLambdaExpression -> visitLambda(element)
            is MojoParenthesizedExpression -> visitComprehension(element, element.comprehensionClauses)
            is MojoListExpression -> visitComprehension(element, element.comprehensionClauses)
            is MojoBraceExpression -> visitComprehension(element, element.comprehensionClauses)
            else -> visitChildren(element)
        }
    }

    private fun visitNestedFunction(function: MojoFunctionDeclaration) {
        if (function == owner) return
        addNamedDeclaration(function)
        nestedEnvironments[function] = visibleBindings()
    }

    private fun visitNestedRegion(region: MojoMlirRegionDeclaration) {
        if (region == owner) return
        addNamedDeclaration(region)
        nestedEnvironments[region] = visibleBindings()
    }

    private fun visitComptimeDeclaration(declaration: MojoComptimeStatement) {
        visitChildren(declaration, setOfNotNull(declaration.declarationName))
        addNamedDeclaration(declaration)
    }

    private fun visitAssignment(statement: MojoAssignmentStatement) {
        visitChildren(statement)

        statement.assignmentTargetChains().forEach { chain ->
            chain.firstOrNull()?.let { resolveReference(it, it.identifierText()) }
        }

        val bindings = statement.bindingIdentifiers()
        if (bindings.isEmpty()) return
        val explicitDeclaration = statement.hasToken(MojoTypes.VAR) || statement.hasToken(MojoTypes.REF)
        for (identifier in bindings) {
            val name = identifier.identifierText()
            if (explicitDeclaration) {
                addSymbol(name, identifier)
                resolvedReferences[identifier] = listOf(identifier)
            } else {
                val existing = lookup(name)
                if (existing != null) {
                    resolvedReferences[identifier] = listOf(existing)
                } else {
                    functionScope[name] = identifier
                    declarations += identifier
                    resolvedReferences[identifier] = listOf(identifier)
                }
            }
        }
    }

    private fun visitAssignmentExpression(expression: MojoAssignmentExpression) {
        visit(expression.expression)
        val identifier = expression.nameIdentifier
        val name = identifier.identifierText()
        if (expression.hasToken(MojoTypes.VAR) || expression.hasToken(MojoTypes.REF)) {
            addSymbol(name, identifier)
            resolvedReferences[identifier] = listOf(identifier)
        } else {
            val existing = lookup(name)
            if (existing != null) {
                resolvedReferences[identifier] = listOf(existing)
            } else {
                functionScope[name] = identifier
                declarations += identifier
                resolvedReferences[identifier] = listOf(identifier)
            }
        }
    }

    private fun visitAugmentedAssignment(statement: MojoAugmentedAssignmentStatement) {
        visitChildren(statement)
        val rhsOffset = statement.expressionSequence?.textOffset ?: Int.MAX_VALUE
        val targets = statement.patternList?.patternList.orEmpty().mapNotNull { pattern ->
            pattern.nameIdentifierList.firstOrNull()
                ?: pattern.splatPattern?.nameIdentifier
        }.ifEmpty {
            statement.nameIdentifierList.filter { it.textOffset < rhsOffset }.take(1)
        }
        targets.forEach { identifier -> resolveReference(identifier, identifier.identifierText()) }
    }

    private fun visitForStatement(statement: MojoForStatement) {
        visit(statement.expressionSequence)
        withScope {
            statement.patternList?.bindingIdentifiers()?.forEach { addSymbol(it.identifierText(), it) }
            visitChildren(
                statement,
                setOfNotNull(statement.expressionSequence, statement.patternList, statement.elseClause),
            )
        }
        visitElseClause(statement.elseClause)
    }

    private fun visitIfStatement(statement: MojoIfStatement) {
        visit(statement.expression)
        withScope {
            visitChildren(
                statement,
                setOfNotNull(statement.expression, statement.elseClause) + statement.elifClauseList,
            )
        }
        statement.elifClauseList.forEach(::visitElifClause)
        visitElseClause(statement.elseClause)
    }

    private fun visitElifClause(clause: MojoElifClause) {
        visit(clause.expression)
        withScope {
            visitChildren(clause, setOfNotNull(clause.expression))
        }
    }

    private fun visitElseClause(clause: MojoElseClause?) {
        if (clause == null) return
        withScope { visitChildren(clause) }
    }

    private fun visitWhileStatement(statement: MojoWhileStatement) {
        visit(statement.expression)
        withScope {
            visitChildren(statement, setOfNotNull(statement.expression, statement.elseClause))
        }
        visitElseClause(statement.elseClause)
    }

    private fun visitTryStatement(statement: MojoTryStatement) {
        withScope {
            visitChildren(
                statement,
                statement.exceptClauseList.toSet() + setOfNotNull(statement.elseClause, statement.finallyClause),
            )
        }
        statement.exceptClauseList.forEach(::visitExceptClause)
        visitElseClause(statement.elseClause)
        statement.finallyClause?.let { clause -> withScope { visitChildren(clause) } }
    }

    private fun visitExceptClause(clause: MojoExceptClause) {
        visit(clause.expressionSequence)
        withScope {
            val target = clause.expression as? MojoReferenceExpression
            if (target != null) addSymbol(target.nameIdentifier.identifierText(), target.nameIdentifier)
            visitChildren(clause, setOfNotNull(clause.expressionSequence, clause.expression))
        }
    }

    private fun visitWithStatement(statement: MojoWithStatement) {
        withScope {
            statement.withClause?.withItemList?.forEach { item ->
                val expressions = item.expressionList
                visit(expressions.firstOrNull())
                val target = expressions.getOrNull(1) as? MojoReferenceExpression
                if (target != null) addSymbol(target.nameIdentifier.identifierText(), target.nameIdentifier)
            }
            visitChildren(statement, setOfNotNull(statement.withClause))
        }
    }

    private fun visitMatchStatement(statement: MojoMatchStatement) {
        visit(statement.expressionSequence)
        statement.caseClauseList.forEach { clause ->
            withScope {
                clause.casePatternList?.bindingIdentifiers()?.forEach { addSymbol(it.identifierText(), it) }
                visit(clause.expression)
                visitChildren(clause, setOfNotNull(clause.casePatternList, clause.expression))
            }
        }
    }

    private fun visitComprehension(container: PsiElement, clauses: MojoComprehensionClauses?) {
        if (clauses == null) {
            visitChildren(container)
            return
        }

        withScope {
            var clause = clauses.firstChild
            while (clause != null) {
                when (clause) {
                    is MojoForInClause -> {
                        visitChildren(clause, setOfNotNull(clause.patternList))
                        clause.patternList?.bindingIdentifiers()?.forEach { identifier ->
                            addSymbol(identifier.identifierText(), identifier)
                        }
                    }
                    is MojoIfClause -> visitChildren(clause)
                }
                clause = clause.nextSibling
            }
            visitChildren(container, setOf(clauses))
        }
    }

    private fun visitLambda(expression: MojoLambdaExpression) {
        withScope {
            seedParameters(expression.typeParameterList)
            seedParameters(expression.callableParameters)
            expression.parameterDeclarationList.forEach(::addParameter)
            seedCaptureParameters(expression.captureParameters)
            visitChildren(expression, setOfNotNull(expression.expression))
            visit(expression.expression)
        }
    }

    private fun seedParameters(list: MojoTypeParameterList?) {
        list?.parameterDeclarationList?.forEach(::addParameter)
    }

    private fun seedParameters(list: MojoCallableParameters?) {
        list?.parameterDeclarationList?.forEach(::addParameter)
    }

    private fun seedCaptureParameters(captures: MojoCaptureParameters?) {
        captures?.captureParameterList
            ?.mapNotNull { it.parameterDeclaration }
            ?.forEach(::addParameter)
    }

    private fun addParameter(parameter: MojoParameterDeclaration) {
        val identifier = parameter.nameIdentifier ?: return
        addSymbol(identifier.identifierText(), identifier)
    }

    private fun addNamedDeclaration(declaration: MojoNamedElement) {
        val name = declaration.name ?: return
        addSymbol(name, declaration)
    }

    private fun resolveReference(reference: PsiElement, spelling: String) {
        val target = lookup(spelling.removeSurrounding("`")) ?: return
        resolvedReferences[reference] = listOf(target)
    }

    private fun resolveTypeReference(identifier: MojoNameIdentifier) {
        if (!identifier.isTypeReference()) return
        resolveReference(identifier, identifier.text)
    }

    private fun addSymbol(name: String, declaration: PsiElement) {
        scopes.last()[name.removeSurrounding("`")] = declaration
        declarations += declaration
    }

    private fun lookup(name: String): PsiElement? {
        val normalized = name.removeSurrounding("`")
        val iterator = scopes.descendingIterator()
        while (iterator.hasNext()) {
            iterator.next()[normalized]?.let { return it }
        }
        return null
    }

    private fun visibleBindings(): Map<String, PsiElement> = buildMap {
        scopes.forEach { putAll(it) }
    }

    private inline fun withScope(block: () -> Unit) {
        scopes.addLast(LinkedHashMap())
        try {
            block()
        } finally {
            scopes.removeLast()
        }
    }

    private fun visitChildren(element: PsiElement, excluded: Set<PsiElement> = emptySet()) {
        var child = element.firstChild
        while (child != null) {
            if (child !in excluded) visit(child)
            child = child.nextSibling
        }
    }
}

internal fun PsiElement.hasToken(type: com.intellij.psi.tree.IElementType): Boolean =
    node.findChildByType(type) != null

internal fun MojoNameIdentifier.identifierText(): String = text.removeSurrounding("`")

private fun MojoNameIdentifier.isTypeReference(): Boolean {
    when (val owner = parent) {
        is MojoReferenceExpression,
        is MojoMemberExpression,
        is MojoDottedName,
        is MojoDeclarationName,
        -> return false

        is MojoParameterDeclaration -> if (owner.nameIdentifier == this) return false
    }
    return PsiTreeUtil.getParentOfType(this, MojoTypeExpression::class.java, false) != null
}
