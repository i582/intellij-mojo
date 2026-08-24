package com.github.petrmakhnev.intellijmojo.lang.type

import com.github.petrmakhnev.intellijmojo.lang.psi.*
import com.github.petrmakhnev.intellijmojo.lang.resolve.MojoGlobalSymbolResolver
import com.github.petrmakhnev.intellijmojo.lang.resolve.MojoLocalResolver
import com.github.petrmakhnev.intellijmojo.lang.resolve.assignmentTargetChains
import com.github.petrmakhnev.intellijmojo.lang.resolve.bindingIdentifiers
import com.github.petrmakhnev.intellijmojo.lang.resolve.enclosingTypeParameters
import com.github.petrmakhnev.intellijmojo.lang.resolve.hasToken
import com.github.petrmakhnev.intellijmojo.lang.resolve.identifierText
import com.github.petrmakhnev.intellijmojo.lang.resolve.lexicalTypeParameter
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.RecursionManager
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import java.util.LinkedHashMap

class MojoInferenceResult internal constructor(
    private val resolvedReferences: Map<PsiElement, List<PsiElement>>,
    private val values: Map<PsiElement, MojoValue>,
    private val resolvedCalls: Map<MojoCallExpression, List<MojoFunctionDeclaration>>,
    private val nestedEnvironments: Map<PsiElement, Map<String, PsiElement>>,
    private val localDeclarations: Set<PsiElement>,
) {
    fun getResolvedRefs(element: PsiElement): List<PsiElement> =
        resolvedReferences[element].orEmpty().filter { it.isValid }

    fun getValue(element: PsiElement?): MojoValue? = values[element]

    fun getType(element: PsiElement?): MojoTy? = getValue(element)?.type

    fun getResolvedCall(expression: MojoCallExpression): List<MojoFunctionDeclaration> =
        resolvedCalls[expression].orEmpty().filter { it.isValid }

    fun isLocalDeclaration(element: PsiElement): Boolean = element in localDeclarations

    internal fun environmentFor(owner: PsiElement): Map<String, PsiElement> =
        nestedEnvironments[owner].orEmpty().filterValues { it.isValid }

    internal companion object {
        val Empty = MojoInferenceResult(emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptySet())
    }
}

private val INFERENCE_KEY: Key<CachedValue<MojoInferenceResult>> = Key.create("MOJO_INFERENCE_RESULT")

private val PsiElement.ownInferenceResult: MojoInferenceResult
    get() = CachedValuesManager.getCachedValue(this, INFERENCE_KEY) {
        val result = RecursionManager.doPreventingRecursion(this, true) {
            when (this) {
                is MojoFunctionDeclaration -> MojoInferenceContext(this).infer()
                is MojoMlirRegionDeclaration -> inferMlirRegion()
                else -> MojoInferenceResult.Empty
            }
        } ?: MojoInferenceResult.Empty
        CachedValueProvider.Result.create(result, PsiModificationTracker.MODIFICATION_COUNT)
    }

val MojoFunctionDeclaration.selfInferenceResult: MojoInferenceResult
    get() = ownInferenceResult

val MojoMlirRegionDeclaration.selfInferenceResult: MojoInferenceResult
    get() = ownInferenceResult

val PsiElement.mojoInference: MojoInferenceResult?
    get() = nearestInferenceOwner()?.ownInferenceResult

val MojoExpression.inferredValue: MojoValue?
    get() = mojoInference?.getValue(this)

val MojoExpression.inferredType: MojoTy?
    get() = inferredValue?.type

private fun PsiElement.nearestInferenceOwner(): PsiElement? {
    var current: PsiElement? = this
    while (current != null && current !is PsiFile) {
        if (current is MojoFunctionDeclaration || current is MojoMlirRegionDeclaration) return current
        current = current.parent
    }
    return null
}

private fun MojoMlirRegionDeclaration.inferMlirRegion(): MojoInferenceResult {
    val outerBindings = parent?.nearestInferenceOwner()?.ownInferenceResult?.environmentFor(this).orEmpty()
    val localResolution = MojoLocalResolver(this, outerBindings).resolve()
    return MojoInferenceResult(
        localResolution.resolvedReferences,
        emptyMap(),
        emptyMap(),
        localResolution.nestedEnvironments,
        localResolution.declarations,
    )
}

private class MojoInferenceContext(private val owner: MojoFunctionDeclaration) {
    private val resolvedReferences = LinkedHashMap<PsiElement, List<PsiElement>>()
    private val values = LinkedHashMap<PsiElement, MojoValue>()
    private val resolvedCalls = LinkedHashMap<MojoCallExpression, List<MojoFunctionDeclaration>>()
    private lateinit var localDeclarations: Set<PsiElement>
    private lateinit var nestedEnvironments: Map<PsiElement, Map<String, PsiElement>>
    private lateinit var flow: MojoFlowContext

    fun infer(): MojoInferenceResult {
        val outerBindings = owner.parent?.nearestInferenceOwner()?.ownInferenceResult?.environmentFor(owner).orEmpty()
        val localResolution = MojoLocalResolver(owner, outerBindings).resolve()
        resolvedReferences.putAll(localResolution.resolvedReferences)
        localDeclarations = localResolution.declarations
        nestedEnvironments = localResolution.nestedEnvironments

        flow = MojoFlowContext()
        seedFunction(owner)
        walkChildren(owner, setOf(owner.functionSignature), flow)

        return MojoInferenceResult(
            resolvedReferences,
            values,
            resolvedCalls,
            nestedEnvironments,
            localDeclarations,
        )
    }

    private fun seedFunction(function: MojoFunctionDeclaration) {
        val signature = function.functionSignature
        function.enclosingTypeParameters().forEach { seedTypeParameter(it, flow) }
        signature.typeParameterList?.parameterDeclarationList.orEmpty().forEach { seedTypeParameter(it, flow) }
        signature.callableParameters?.parameterDeclarationList.orEmpty().forEach { parameter ->
            seedParameter(parameter, flow)
        }
        signature.captureParameters?.captureParameterList.orEmpty()
            .mapNotNull { it.parameterDeclaration }
            .forEach { seedParameter(it, flow) }
        values[function] = MojoValue(functionType(function), MojoValueCategory.IMMUTABLE_LVALUE)
    }

    private fun seedTypeParameter(parameter: MojoParameterDeclaration, targetFlow: MojoFlowContext) {
        val declaredType = parameter.typeExpression?.let { resolveType(it, owner) }
        val value = if (declaredType == null || declaredType.isTypeConstraint()) {
            MojoValue(MojoTy.TypeValue(MojoTy.TypeParameter(parameter)), MojoValueCategory.TYPE)
        } else {
            MojoValue(declaredType, MojoValueCategory.PARAMETER)
        }
        setValue(parameter, value, targetFlow)
        parameter.nameIdentifier?.let { setValue(it, value, targetFlow) }
    }

    private fun seedParameter(parameter: MojoParameterDeclaration, targetFlow: MojoFlowContext) {
        val type = parameter.typeExpression?.let { resolveType(it, owner) }
            ?: implicitSelfType(parameter)
            ?: MojoTy.Unknown
        val convention = parameterConvention(parameter)
        val category = when (convention) {
            MojoParameterConvention.MUT,
            MojoParameterConvention.VAR,
            MojoParameterConvention.OUT,
            -> MojoValueCategory.MUTABLE_LVALUE

            MojoParameterConvention.REF,
            MojoParameterConvention.IMM,
            MojoParameterConvention.READ,
            -> MojoValueCategory.IMMUTABLE_LVALUE

            else -> MojoValueCategory.PARAMETER
        }
        val value = MojoValue(type, category)
        setValue(parameter, value, targetFlow)
        parameter.nameIdentifier?.let { setValue(it, value, targetFlow) }
    }

    private fun walk(element: PsiElement?, currentFlow: MojoFlowContext): MojoFlowContext = when (element) {
        null -> currentFlow
        is MojoFunctionDeclaration -> {
            if (element != owner) values[element] = MojoValue(functionType(element), MojoValueCategory.IMMUTABLE_LVALUE)
            currentFlow
        }
        is MojoStructDeclaration -> {
            values[element] = MojoValue(MojoTy.TypeValue(MojoTy.Struct(element)), MojoValueCategory.TYPE)
            currentFlow
        }
        is MojoTraitDeclaration -> {
            values[element] = MojoValue(MojoTy.TypeValue(MojoTy.Trait(element)), MojoValueCategory.TYPE)
            currentFlow
        }
        is MojoComptimeStatement -> inferComptime(element, currentFlow)
        is MojoAssignmentStatement -> inferAssignment(element, currentFlow)
        is MojoAssignmentExpression -> {
            inferExpression(element, currentFlow)
            currentFlow
        }
        is MojoForStatement -> inferForStatement(element, currentFlow)
        is MojoIfStatement -> inferIfStatement(element, currentFlow)
        is MojoWhileStatement -> inferWhileStatement(element, currentFlow)
        is MojoTryStatement -> inferTryStatement(element, currentFlow)
        is MojoWithStatement -> inferWithStatement(element, currentFlow)
        is MojoMatchStatement -> inferMatchStatement(element, currentFlow)
        is MojoReturnStatement -> {
            val hint = owner.functionSignature.returnType?.typeExpression?.let { resolveType(it, owner) }
            inferExpressionSequence(element.expressionSequence, currentFlow, hint)
            currentFlow
        }
        is MojoExpression -> {
            inferExpression(element, currentFlow)
            currentFlow
        }
        else -> walkChildren(element, emptySet(), currentFlow)
    }

    private fun inferComptime(statement: MojoComptimeStatement, currentFlow: MojoFlowContext): MojoFlowContext {
        val value = comptimeValue(statement, currentFlow)
        setValue(statement, value, currentFlow)
        return currentFlow
    }

    private fun inferAssignment(statement: MojoAssignmentStatement, currentFlow: MojoFlowContext): MojoFlowContext {
        val bindings = statement.bindingIdentifiers()
        val annotation = statement.typeExpression?.let { resolveType(it, owner) }
        val rhs = inferAssignmentRhs(statement, currentFlow, annotation)
        inferAssignmentTargets(statement, currentFlow)
        val tupleTypes = (rhs.type as? MojoTy.Tuple)?.elements

        bindings.forEachIndexed { index, identifier ->
            val target = resolvedReferences[identifier]?.firstOrNull() ?: identifier
            val isDeclaration = target == identifier && target in localDeclarations
            val existing = valueOfTarget(target, currentFlow)
            val inferredType = (annotation ?: tupleTypes?.getOrNull(index) ?: rhs.type).materialized()
            if (isDeclaration || existing == null) {
                val category = if (statement.hasToken(MojoTypes.REF)) {
                    MojoValueCategory.IMMUTABLE_LVALUE
                } else {
                    MojoValueCategory.MUTABLE_LVALUE
                }
                setValue(target, MojoValue(inferredType, category), currentFlow)
            } else {
                values[identifier] = existing
            }
        }
        return currentFlow
    }

    private fun inferAssignmentTargets(statement: MojoAssignmentStatement, currentFlow: MojoFlowContext) {
        statement.assignmentTargetChains().forEach { chain ->
            val root = chain.firstOrNull() ?: return@forEach
            var targets = resolvedReferences[root].orEmpty()
            if (targets.isEmpty()) return@forEach
            var value = valueOfTargets(targets, currentFlow)
            for (member in chain.drop(1)) {
                val owners = value.type.declarationOwners()
                if (owners.isEmpty()) return@forEach
                targets = MojoGlobalSymbolResolver.resolveTypeMember(owners, member.identifierText())
                if (targets.isEmpty()) return@forEach
                resolvedReferences[member] = targets
                value = valueOfTargets(targets, currentFlow)
            }
        }
    }

    private fun inferAssignmentRhs(
        statement: MojoAssignmentStatement,
        currentFlow: MojoFlowContext,
        hint: MojoTy?,
    ): MojoValue {
        val sequence = statement.expressionSequenceList.lastOrNull()
        if (sequence != null) return inferExpressionSequence(sequence, currentFlow, hint)
        val expression = statement.expressionList.lastOrNull()
        return expression?.let { inferExpression(it, currentFlow, hint) } ?: MojoValue.Unknown
    }

    private fun inferForStatement(statement: MojoForStatement, currentFlow: MojoFlowContext): MojoFlowContext {
        val iterable = inferExpressionSequence(statement.expressionSequence, currentFlow)
        val itemType = iterableItemType(iterable.type)
        val bodyFlow = currentFlow.copy()
        statement.patternList?.bindingIdentifiers().orEmpty().forEach { identifier ->
            setValue(identifier, MojoValue(itemType, MojoValueCategory.MUTABLE_LVALUE), bodyFlow)
        }
        walkChildren(
            statement,
            setOfNotNull(statement.expressionSequence, statement.patternList, statement.elseClause),
            bodyFlow,
        )
        val elseFlow = statement.elseClause?.let { walkChildren(it, emptySet(), currentFlow.copy()) } ?: currentFlow
        return currentFlow.join(bodyFlow).join(elseFlow)
    }

    private fun iterableItemType(type: MojoTy): MojoTy = when (type) {
        is MojoTy.Builtin -> type.arguments.firstOrNull() ?: MojoTy.Unknown
        is MojoTy.Tuple -> type.elements.reduceOrNull(MojoTy::join) ?: MojoTy.Unknown
        else -> MojoTy.Unknown
    }

    private fun inferIfStatement(statement: MojoIfStatement, currentFlow: MojoFlowContext): MojoFlowContext {
        statement.expression?.let { inferExpression(it, currentFlow, MojoTy.Builtin("Bool")) }
        var joined = walkChildren(
            statement,
            setOfNotNull(statement.expression, statement.elseClause) + statement.elifClauseList,
            currentFlow.copy(),
        )
        for (clause in statement.elifClauseList) {
            clause.expression?.let { inferExpression(it, currentFlow, MojoTy.Builtin("Bool")) }
            joined = joined.join(walkChildren(clause, setOfNotNull(clause.expression), currentFlow.copy()))
        }
        val elseFlow = statement.elseClause?.let { walkChildren(it, emptySet(), currentFlow.copy()) } ?: currentFlow
        return joined.join(elseFlow)
    }

    private fun inferWhileStatement(statement: MojoWhileStatement, currentFlow: MojoFlowContext): MojoFlowContext {
        statement.expression?.let { inferExpression(it, currentFlow, MojoTy.Builtin("Bool")) }
        val body = walkChildren(
            statement,
            setOfNotNull(statement.expression, statement.elseClause),
            currentFlow.copy(),
        )
        val elseFlow = statement.elseClause?.let { walkChildren(it, emptySet(), currentFlow.copy()) } ?: currentFlow
        return currentFlow.join(body).join(elseFlow)
    }

    private fun inferTryStatement(statement: MojoTryStatement, currentFlow: MojoFlowContext): MojoFlowContext {
        var joined = walkChildren(
            statement,
            statement.exceptClauseList.toSet() + setOfNotNull(statement.elseClause, statement.finallyClause),
            currentFlow.copy(),
        )
        statement.exceptClauseList.forEach { clause ->
            inferExpressionSequence(clause.expressionSequence, currentFlow)
            val exceptFlow = currentFlow.copy()
            val target = (clause.expression as? MojoReferenceExpression)?.nameIdentifier
            if (target != null) {
                setValue(target, MojoValue(MojoTy.Unknown, MojoValueCategory.MUTABLE_LVALUE), exceptFlow)
            }
            joined = joined.join(
                walkChildren(clause, setOfNotNull(clause.expressionSequence, clause.expression), exceptFlow),
            )
        }
        statement.elseClause?.let { joined = joined.join(walkChildren(it, emptySet(), currentFlow.copy())) }
        statement.finallyClause?.let { joined = walkChildren(it, emptySet(), joined) }
        return joined
    }

    private fun inferWithStatement(statement: MojoWithStatement, currentFlow: MojoFlowContext): MojoFlowContext {
        val bodyFlow = currentFlow.copy()
        statement.withClause?.withItemList.orEmpty().forEach { item ->
            val expressions = item.expressionList
            val value = expressions.firstOrNull()?.let { inferExpression(it, bodyFlow) } ?: MojoValue.Unknown
            val target = (expressions.getOrNull(1) as? MojoReferenceExpression)?.nameIdentifier
            if (target != null) setValue(target, value.copy(category = MojoValueCategory.MUTABLE_LVALUE), bodyFlow)
        }
        return currentFlow.join(walkChildren(statement, setOfNotNull(statement.withClause), bodyFlow))
    }

    private fun inferMatchStatement(statement: MojoMatchStatement, currentFlow: MojoFlowContext): MojoFlowContext {
        inferExpressionSequence(statement.expressionSequence, currentFlow)
        return statement.caseClauseList.fold(currentFlow) { joined, clause ->
            val caseFlow = currentFlow.copy()
            clause.casePatternList?.bindingIdentifiers().orEmpty().forEach { identifier ->
                setValue(identifier, MojoValue(MojoTy.Unknown, MojoValueCategory.IMMUTABLE_LVALUE), caseFlow)
            }
            clause.expression?.let { inferExpression(it, caseFlow, MojoTy.Builtin("Bool")) }
            joined.join(walkChildren(clause, setOfNotNull(clause.casePatternList, clause.expression), caseFlow))
        }
    }

    private fun walkChildren(
        element: PsiElement,
        excluded: Set<PsiElement>,
        initialFlow: MojoFlowContext,
    ): MojoFlowContext {
        var currentFlow = initialFlow
        var child = element.firstChild
        while (child != null) {
            if (child !in excluded) currentFlow = walk(child, currentFlow)
            child = child.nextSibling
        }
        return currentFlow
    }

    private fun inferExpressionSequence(
        sequence: MojoExpressionSequence?,
        currentFlow: MojoFlowContext,
        hint: MojoTy? = null,
    ): MojoValue {
        val expressions = sequence?.expressionList.orEmpty()
        if (expressions.isEmpty()) return MojoValue.Unknown
        if (expressions.size == 1) return inferExpression(expressions.single(), currentFlow, hint)
        val hints = (hint as? MojoTy.Tuple)?.elements.orEmpty()
        return MojoValue(
            MojoTy.Tuple(expressions.mapIndexed { index, expression ->
                inferExpression(expression, currentFlow, hints.getOrNull(index)).type
            }),
        )
    }

    private fun inferExpression(
        expression: MojoExpression,
        currentFlow: MojoFlowContext,
        hint: MojoTy? = null,
    ): MojoValue {
        val previous = values[expression]
        if (previous != null && (previous.type != MojoTy.Unknown || hint == null)) return previous

        val inferred = when (expression) {
            is MojoLiteralExpression -> inferLiteral(expression)
            is MojoReferenceExpression -> inferReference(expression, currentFlow)
            is MojoMemberExpression -> inferMember(expression, currentFlow)
            is MojoCallExpression -> inferCall(expression, currentFlow, hint)
            is MojoSubscriptExpression -> inferSubscript(expression, currentFlow)
            is MojoParenthesizedExpression -> {
                val expressionFlow = inferComprehensionFlow(expression.comprehensionClauses, currentFlow)
                inferExpressionList(expression.expressionList, expressionFlow, hint)
            }
            is MojoListExpression -> inferList(expression, currentFlow, hint)
            is MojoBraceExpression -> inferBrace(expression, currentFlow)
            is MojoBinExpression -> inferBinary(expression, currentFlow, hint)
            is MojoConditionalExpression -> inferConditional(expression, currentFlow, hint)
            is MojoNotExpression -> {
                expression.expression?.let { inferExpression(it, currentFlow, MojoTy.Builtin("Bool")) }
                MojoValue(MojoTy.Builtin("Bool"))
            }
            is MojoPrefixExpression -> expression.expression?.let { inferExpression(it, currentFlow, hint) } ?: MojoValue.Unknown
            is MojoTransferExpression -> inferExpression(expression.expression, currentFlow, hint).copy(category = MojoValueCategory.RVALUE)
            is MojoAssignmentExpression -> inferAssignmentExpression(expression, currentFlow, hint)
            is MojoLambdaExpression -> inferLambda(expression, currentFlow)
            is MojoYieldExpression -> expression.expressionSequence?.let { inferExpressionSequence(it, currentFlow, hint) }
                ?: MojoValue(MojoTy.None)
            else -> MojoValue.Unknown
        }
        values[expression] = inferred
        return inferred
    }

    private fun inferLiteral(expression: MojoLiteralExpression): MojoValue {
        val type = when {
            expression.stringValue != null -> MojoTy.Builtin("StringLiteral")
            expression.hasToken(MojoTypes.INTEGER_LITERAL) -> MojoTy.Builtin("IntLiteral")
            expression.hasToken(MojoTypes.FLOAT_LITERAL) -> MojoTy.Builtin("FloatLiteral")
            expression.hasToken(MojoTypes.TRUE) || expression.hasToken(MojoTypes.FALSE) -> MojoTy.Builtin("Bool")
            expression.hasToken(MojoTypes.NONE) -> MojoTy.None
            expression.hasToken(MojoTypes.SELF) -> enclosingType(owner)?.let { MojoTy.TypeValue(it) } ?: MojoTy.Unknown
            else -> MojoTy.Unknown
        }
        return MojoValue(type, if (type is MojoTy.TypeValue) MojoValueCategory.TYPE else MojoValueCategory.RVALUE)
    }

    private fun inferReference(expression: MojoReferenceExpression, currentFlow: MojoFlowContext): MojoValue {
        val localTargets = resolvedReferences[expression].orEmpty()
        if (localTargets.isNotEmpty()) return valueOfTargets(localTargets, currentFlow)

        val name = expression.nameIdentifier.identifierText()
        val rawTargets = MojoGlobalSymbolResolver.resolve(expression, name)
        if (rawTargets.isNotEmpty()) {
            val navigationTargets = MojoGlobalSymbolResolver.resolveCallAware(expression, rawTargets)
            resolvedReferences[expression] = navigationTargets
            return valueOfTargets(rawTargets, currentFlow)
        }
        if (name in BUILTIN_TYPES) {
            return MojoValue(MojoTy.TypeValue(MojoTy.Builtin(name)), MojoValueCategory.TYPE)
        }
        return MojoValue.Unknown
    }

    private fun inferMember(expression: MojoMemberExpression, currentFlow: MojoFlowContext): MojoValue {
        val receiver = inferExpression(expression.expression, currentFlow)
        val owners = receiver.type.declarationOwners()
        if (owners.isEmpty()) return MojoValue.Unknown

        val rawTargets = MojoGlobalSymbolResolver.resolveMember(expression, owners)
        if (rawTargets.isEmpty()) return MojoValue.Unknown
        resolvedReferences[expression] = MojoGlobalSymbolResolver.resolveCallAware(expression, rawTargets)
        val value = valueOfTargets(rawTargets, currentFlow)
        return when (val type = value.type) {
            is MojoTy.Function -> value.copy(type = type.withoutReceiver())
            is MojoTy.Overload -> value.copy(
                type = MojoTy.Overload(type.candidates.map(MojoTy.Function::withoutReceiver)),
            )
            else -> value
        }
    }

    private fun inferCall(
        expression: MojoCallExpression,
        currentFlow: MojoFlowContext,
        hint: MojoTy?,
    ): MojoValue {
        val callee = inferExpression(expression.expression, currentFlow)
        val arguments = expression.argumentList.argumentList
        val candidates = when (val type = callee.type) {
            is MojoTy.Function -> listOf(type)
            is MojoTy.Overload -> type.candidates
            else -> emptyList()
        }

        if (candidates.isNotEmpty()) {
            val matches = candidates.mapNotNull { matchCall(it, arguments, currentFlow, hint) }
            val bestScore = matches.minOfOrNull { it.score }
            val best = matches.filter { it.score == bestScore }
            val selected = best.mapNotNull { it.function.declaration }
            if (selected.isNotEmpty()) resolvedCalls[expression] = selected
            val returnType = best.map { it.returnType }.reduceOrNull(MojoTy::join) ?: MojoTy.Unknown
            return MojoValue(returnType)
        }

        if (callee.type is MojoTy.TypeValue) {
            arguments.forEach { argumentExpression(it)?.let { value -> inferExpression(value, currentFlow) } }
            return MojoValue(callee.type.instanceType)
        }

        arguments.forEach { argumentExpression(it)?.let { value -> inferExpression(value, currentFlow) } }
        return MojoValue.Unknown
    }

    private fun matchCall(
        function: MojoTy.Function,
        arguments: List<MojoArgument>,
        currentFlow: MojoFlowContext,
        hint: MojoTy?,
    ): CallMatch? {
        val required = function.parameters.count { !it.hasDefault && !it.variadic }
        val variadic = function.parameters.any { it.variadic }
        if (arguments.size < required || (!variadic && arguments.size > function.parameters.size)) return null

        val substitution = LinkedHashMap<PsiElement, MojoTy>()
        if (hint != null) substitution.deduce(function.returnType, hint)
        var score = 0
        var positionalIndex = 0
        for (argument in arguments) {
            val name = argument.namedParameter?.nameIdentifier?.identifierText()
            val parameter = if (name != null) {
                function.parameters.firstOrNull { it.name == name }
            } else {
                function.parameters.getOrNull(positionalIndex++) ?: function.parameters.lastOrNull { it.variadic }
            } ?: return null
            val expected = parameter.type.substitute(substitution)
            val argumentExpression = argumentExpression(argument) ?: continue
            val actual = inferExpression(
                argumentExpression,
                currentFlow,
                expected.takeUnless { it is MojoTy.TypeParameter },
            ).type
            substitution.deduce(parameter.type, actual)
            val specializedExpected = parameter.type.substitute(substitution)
            val compatibility = compatibilityScore(specializedExpected, actual)
            if (compatibility == INCOMPATIBLE) return null
            score += compatibility
        }
        return CallMatch(function, function.returnType.substitute(substitution), score)
    }

    private fun inferSubscript(expression: MojoSubscriptExpression, currentFlow: MojoFlowContext): MojoValue {
        val base = inferExpression(expression.expression, currentFlow)
        val arguments = expression.subscriptItemList.map { item ->
            item.typeExpression?.let { resolveType(it, owner) }
                ?: item.expression?.let { argument ->
                    val value = inferExpression(argument, currentFlow)
                    (value.type as? MojoTy.TypeValue)?.instanceType ?: value.type
                }
                ?: MojoTy.Unknown
        }
        val type = when (val baseType = base.type) {
            is MojoTy.TypeValue -> MojoTy.TypeValue(baseType.instanceType.withArguments(arguments))
            is MojoTy.Builtin -> baseType.arguments.firstOrNull() ?: MojoTy.Unknown
            else -> baseType
        }
        return MojoValue(type, if (type is MojoTy.TypeValue) MojoValueCategory.TYPE else MojoValueCategory.RVALUE)
    }

    private fun inferExpressionList(
        expressions: List<MojoExpression>,
        currentFlow: MojoFlowContext,
        hint: MojoTy?,
    ): MojoValue {
        if (expressions.isEmpty()) return MojoValue(MojoTy.None)
        if (expressions.size == 1) return inferExpression(expressions.single(), currentFlow, hint)
        val hints = (hint as? MojoTy.Tuple)?.elements.orEmpty()
        return MojoValue(MojoTy.Tuple(expressions.mapIndexed { index, expression ->
            inferExpression(expression, currentFlow, hints.getOrNull(index)).type
        }))
    }

    private fun inferList(
        expression: MojoListExpression,
        currentFlow: MojoFlowContext,
        hint: MojoTy?,
    ): MojoValue {
        val expressionFlow = inferComprehensionFlow(expression.comprehensionClauses, currentFlow)
        val expectedElement = (hint as? MojoTy.Builtin)?.arguments?.firstOrNull()
        val elementType = expression.expressionList
            .map { inferExpression(it, expressionFlow, expectedElement).type }
            .reduceOrNull(MojoTy::join)
            ?: expectedElement
            ?: MojoTy.Unknown
        return MojoValue(MojoTy.Builtin("List", listOf(elementType)))
    }

    private fun inferBrace(expression: MojoBraceExpression, currentFlow: MojoFlowContext): MojoValue {
        val expressionFlow = inferComprehensionFlow(expression.comprehensionClauses, currentFlow)
        val types = expression.expressionList.map { inferExpression(it, expressionFlow).type }
        return if (types.isEmpty()) {
            MojoValue(MojoTy.Unknown)
        } else {
            MojoValue(MojoTy.Builtin("Dict", types.take(2)))
        }
    }

    private fun inferComprehensionFlow(
        clauses: MojoComprehensionClauses?,
        currentFlow: MojoFlowContext,
    ): MojoFlowContext {
        if (clauses == null) return currentFlow
        val comprehensionFlow = currentFlow.copy()
        var clause = clauses.firstChild
        while (clause != null) {
            when (clause) {
                is MojoForInClause -> {
                    val iterable = generateSequence(clause.firstChild) { it.nextSibling }
                        .filterIsInstance<MojoExpression>()
                        .map { inferExpression(it, comprehensionFlow) }
                        .lastOrNull()
                        ?: MojoValue.Unknown
                    val itemType = iterableItemType(iterable.type)
                    clause.patternList?.bindingIdentifiers().orEmpty().forEach { identifier ->
                        setValue(identifier, MojoValue(itemType, MojoValueCategory.IMMUTABLE_LVALUE), comprehensionFlow)
                    }
                }
                is MojoIfClause -> {
                    generateSequence(clause.firstChild) { it.nextSibling }
                        .filterIsInstance<MojoExpression>()
                        .forEach { inferExpression(it, comprehensionFlow, MojoTy.Builtin("Bool")) }
                }
            }
            clause = clause.nextSibling
        }
        return comprehensionFlow
    }

    private fun inferBinary(
        expression: MojoBinExpression,
        currentFlow: MojoFlowContext,
        hint: MojoTy?,
    ): MojoValue {
        val operands = expression.expressionList
        val left = operands.firstOrNull()?.let { inferExpression(it, currentFlow, hint) } ?: MojoValue.Unknown
        val right = operands.getOrNull(1)?.let { inferExpression(it, currentFlow, left.type) } ?: left
        val operator = expression.binaryOp.text
        val type = if (operator in COMPARISON_OPERATORS || operator == "and" || operator == "or") {
            MojoTy.Builtin("Bool")
        } else {
            left.type.join(right.type)
        }
        return MojoValue(type)
    }

    private fun inferConditional(
        expression: MojoConditionalExpression,
        currentFlow: MojoFlowContext,
        hint: MojoTy?,
    ): MojoValue {
        val expressions = expression.expressionList
        if (expressions.size < 3) return MojoValue.Unknown
        val trueType = inferExpression(expressions[0], currentFlow, hint).type
        inferExpression(expressions[1], currentFlow, MojoTy.Builtin("Bool"))
        val falseType = inferExpression(expressions[2], currentFlow, hint).type
        return MojoValue(trueType.join(falseType))
    }

    private fun inferAssignmentExpression(
        expression: MojoAssignmentExpression,
        currentFlow: MojoFlowContext,
        hint: MojoTy?,
    ): MojoValue {
        val rawValue = expression.expression?.let { inferExpression(it, currentFlow, hint) } ?: MojoValue.Unknown
        val value = rawValue.copy(type = rawValue.type.materialized())
        val target = resolvedReferences[expression.nameIdentifier]?.firstOrNull() ?: expression.nameIdentifier
        if (target in localDeclarations) setValue(target, value.copy(category = MojoValueCategory.MUTABLE_LVALUE), currentFlow)
        return value
    }

    private fun inferLambda(expression: MojoLambdaExpression, currentFlow: MojoFlowContext): MojoValue {
        val lambdaFlow = currentFlow.copy()
        val typeParameters = expression.typeParameterList?.parameterDeclarationList.orEmpty()
        val captureParameters = expression.captureParameters?.captureParameterList.orEmpty()
            .mapNotNull { it.parameterDeclaration }
        typeParameters.forEach { seedTypeParameter(it, lambdaFlow) }

        val parameters = (expression.callableParameters?.parameterDeclarationList.orEmpty() +
            expression.parameterDeclarationList)
            .distinct()
            .filterNot { it in typeParameters || it in captureParameters }
        parameters.forEach { seedParameter(it, lambdaFlow) }
        captureParameters.forEach { seedParameter(it, lambdaFlow) }

        val returnHint = expression.returnType?.typeExpression?.let { resolveType(it, owner) }
        val returnType = expression.expression?.let { inferExpression(it, lambdaFlow, returnHint).type }
            ?: returnHint
            ?: MojoTy.None
        return MojoValue(
            MojoTy.Function(parameters.map { parameter -> callableParameter(parameter, owner) }, returnType),
            MojoValueCategory.IMMUTABLE_LVALUE,
        )
    }

    private fun valueOfTargets(targets: List<PsiElement>, currentFlow: MojoFlowContext): MojoValue {
        if (targets.size == 1) return valueOfTarget(targets.single(), currentFlow) ?: MojoValue.Unknown
        val functions = targets.mapNotNull { (valueOfTarget(it, currentFlow)?.type as? MojoTy.Function) }
        return if (functions.isNotEmpty() && functions.size == targets.size) {
            MojoValue(MojoTy.Overload(functions), MojoValueCategory.IMMUTABLE_LVALUE)
        } else {
            MojoValue.Unknown
        }
    }

    private fun valueOfTarget(target: PsiElement, currentFlow: MojoFlowContext): MojoValue? {
        currentFlow[target]?.let { return it }
        values[target]?.let { return it }
        val externalOwner = PsiTreeUtil.getParentOfType(target, MojoFunctionDeclaration::class.java, false)
        if (externalOwner != null && externalOwner != owner) {
            externalOwner.selfInferenceResult.getValue(target)?.let { return it }
        }
        return when (target) {
            is MojoParameterDeclaration -> MojoValue(
                target.typeExpression?.let { resolveType(it, externalOwner ?: owner) } ?: MojoTy.Unknown,
                MojoValueCategory.PARAMETER,
            )
            is MojoFunctionDeclaration -> MojoValue(functionType(target), MojoValueCategory.IMMUTABLE_LVALUE)
            is MojoStructDeclaration -> MojoValue(MojoTy.TypeValue(MojoTy.Struct(target)), MojoValueCategory.TYPE)
            is MojoTraitDeclaration -> MojoValue(MojoTy.TypeValue(MojoTy.Trait(target)), MojoValueCategory.TYPE)
            is MojoComptimeStatement -> valueOfComptime(target, currentFlow)
            is MojoNameIdentifier -> valueOfIdentifier(target, currentFlow)
            is PsiFile, is PsiDirectory -> MojoValue(MojoTy.Module(target), MojoValueCategory.MODULE)
            else -> null
        }
    }

    private fun valueOfComptime(target: MojoComptimeStatement, currentFlow: MojoFlowContext): MojoValue {
        return comptimeValue(target, currentFlow)
    }

    private fun comptimeValue(statement: MojoComptimeStatement, currentFlow: MojoFlowContext): MojoValue {
        val inferred = when {
            statement.expression != null -> inferExpression(statement.expression!!, currentFlow)
            statement.functionType != null -> MojoValue(
                MojoTy.TypeValue(functionType(statement.functionType!!, owner)),
                MojoValueCategory.TYPE,
            )
            statement.rhsTypeExpression() != null -> MojoValue(
                MojoTy.TypeValue(resolveType(statement.rhsTypeExpression()!!, owner)),
                MojoValueCategory.TYPE,
            )
            statement.hasToken(MojoTypes.TYPE) -> MojoValue(
                MojoTy.TypeValue(MojoTy.TypeParameter(statement)),
                MojoValueCategory.TYPE,
            )
            else -> {
                val declaredType = statement.declaredTypeExpression()?.let { resolveType(it, owner) }
                when {
                    declaredType == null -> MojoValue.Unknown
                    declaredType.isTypeConstraint() -> MojoValue(
                        MojoTy.TypeValue(MojoTy.TypeParameter(statement)),
                        MojoValueCategory.TYPE,
                    )
                    else -> MojoValue(declaredType, MojoValueCategory.PARAMETER)
                }
            }
        }
        return if (inferred.type is MojoTy.TypeValue) {
            inferred.copy(category = MojoValueCategory.TYPE)
        } else {
            inferred.copy(category = MojoValueCategory.PARAMETER)
        }
    }

    private fun valueOfIdentifier(identifier: MojoNameIdentifier, currentFlow: MojoFlowContext): MojoValue? {
        val assignment = PsiTreeUtil.getParentOfType(identifier, MojoAssignmentStatement::class.java, false)
        if (assignment != null && identifier in assignment.bindingIdentifiers()) {
            val annotation = assignment.typeExpression?.let { resolveType(it, owner) }
            if (annotation != null) return MojoValue(annotation, MojoValueCategory.MUTABLE_LVALUE)
        }
        return values[identifier] ?: currentFlow[identifier]
    }

    private fun setValue(target: PsiElement, value: MojoValue, currentFlow: MojoFlowContext) {
        values[target] = value
        currentFlow[target] = value
    }

    private fun functionType(function: MojoFunctionDeclaration): MojoTy.Function {
        val signature = function.functionSignature
        val typeParameters = signature.typeParameterList?.parameterDeclarationList.orEmpty()
            .filter { it.nameIdentifier != null }
        val parameters = signature.callableParameters?.parameterDeclarationList.orEmpty()
            .filter { it.nameIdentifier != null }
            .map { callableParameter(it, function) }
        val returnType = signature.returnType?.typeExpression?.let { resolveType(it, function) } ?: MojoTy.None
        return MojoTy.Function(parameters, returnType, function, typeParameters)
    }

    private fun callableParameter(parameter: MojoParameterDeclaration, function: MojoFunctionDeclaration): MojoCallableParameter {
        val type = parameter.typeExpression?.let { resolveType(it, function) }
            ?: implicitSelfType(parameter)
            ?: MojoTy.Unknown
        return MojoCallableParameter(
            declaration = parameter,
            name = parameter.nameIdentifier?.identifierText(),
            type = type,
            convention = parameterConvention(parameter),
            variadic = parameter.hasToken(MojoTypes.STAR) || parameter.hasToken(MojoTypes.POWER),
            hasDefault = parameter.expression != null,
        )
    }

    private fun resolveType(expression: MojoTypeExpression, function: MojoFunctionDeclaration): MojoTy {
        expression.functionType?.let { return functionType(it, function) }
        expression.parenthesizedFunctionType?.functionType?.let { return functionType(it, function) }
        val union = expression.typeUnion ?: return MojoTy.Unknown
        val variants = union.typeCompositionList.map { resolveTypeComposition(it, function) }
        return if (variants.size == 1) variants.single() else MojoTy.Union(variants)
    }

    private fun resolveTypeComposition(composition: MojoTypeComposition, function: MojoFunctionDeclaration): MojoTy {
        composition.functionTypeList.firstOrNull()?.let { return functionType(it, function) }
        val selfType = PsiTreeUtil.getChildOfType(composition, MojoLiteralExpression::class.java)
            ?.hasToken(MojoTypes.SELF) == true
        if (selfType) {
            composition.nameIdentifierList.lastOrNull()?.let { identifier ->
                val owners = enclosingType(composition)?.declarationOwners().orEmpty()
                val targets = MojoGlobalSymbolResolver.resolveTypeMember(owners, identifier.identifierText())
                if (targets.isNotEmpty()) {
                    resolvedReferences[identifier] = targets
                    targets.firstNotNullOfOrNull(::typeRepresentedByTarget)?.let { return it }
                }
                return resolveTypeName(identifier, function)
            }
            return enclosingType(composition) ?: MojoTy.Unknown
        }
        val nameIdentifier = composition.nameIdentifierList.firstOrNull()
        val base = nameIdentifier?.let { resolveTypeName(it, function) } ?: MojoTy.Unknown
        val arguments = composition.subscriptItemList.map { item ->
            item.typeExpression?.let { resolveType(it, function) }
                ?: item.expression?.let { expression ->
                    val value = inferExpression(expression, flow)
                    (value.type as? MojoTy.TypeValue)?.instanceType ?: value.type
                }
                ?: MojoTy.Unknown
        }
        return base.withArguments(arguments)
    }

    private fun resolveTypeName(identifier: MojoNameIdentifier, function: MojoFunctionDeclaration): MojoTy {
        val name = identifier.identifierText()
        function.functionSignature.typeParameterList?.parameterDeclarationList.orEmpty()
            .firstOrNull { it.nameIdentifier?.identifierText() == name }
            ?.let { return MojoTy.TypeParameter(it) }

        identifier.lexicalTypeParameter(name)?.let { target ->
            resolvedReferences[identifier] = listOf(target)
            return typeRepresentedByTarget(target) ?: MojoTy.Unknown
        }

        resolvedReferences[identifier]?.firstOrNull()?.let { target ->
            return typeRepresentedByTarget(target) ?: MojoTy.Unknown
        }
        MojoGlobalSymbolResolver.resolve(identifier, name).firstOrNull()?.let { target ->
            resolvedReferences[identifier] = listOf(target)
            return typeRepresentedByTarget(target) ?: MojoTy.Unknown
        }
        return if (name in BUILTIN_TYPES) MojoTy.Builtin(name) else MojoTy.Unknown
    }

    private fun typeRepresentedByTarget(target: PsiElement): MojoTy? = when (target) {
        is MojoStructDeclaration -> MojoTy.Struct(target)
        is MojoTraitDeclaration -> MojoTy.Trait(target)
        is MojoParameterDeclaration -> {
            val value = values[target]?.type
            (value as? MojoTy.TypeValue)?.instanceType ?: (value as? MojoTy.TypeParameter) ?: MojoTy.TypeParameter(target)
        }
        is MojoNameIdentifier -> {
            val parameter = target.parent as? MojoParameterDeclaration
            if (parameter != null) {
                val value = values[target]?.type
                (value as? MojoTy.TypeValue)?.instanceType ?: MojoTy.TypeParameter(parameter)
            } else {
                null
            }
        }
        is MojoComptimeStatement -> {
            val value = values[target] ?: valueOfComptime(target, flow)
            (value.type as? MojoTy.TypeValue)?.instanceType
        }
        else -> null
    }

    private fun MojoComptimeStatement.rhsTypeExpression(): MojoTypeExpression? {
        val equalsOffset = node.findChildByType(MojoTypes.EQ)?.startOffset ?: return null
        return typeExpressionList.firstOrNull { it.textOffset > equalsOffset }
    }

    private fun MojoComptimeStatement.declaredTypeExpression(): MojoTypeExpression? {
        val equalsOffset = node.findChildByType(MojoTypes.EQ)?.startOffset ?: Int.MAX_VALUE
        return typeExpressionList.firstOrNull { it.textOffset < equalsOffset }
    }

    private fun functionType(type: MojoFunctionType, function: MojoFunctionDeclaration): MojoTy.Function {
        val parameters = type.parameterDeclarationList
            .filter { it.nameIdentifier != null }
            .map { callableParameter(it, function) }
        val returnType = type.returnType?.typeExpression?.let { resolveType(it, function) } ?: MojoTy.None
        return MojoTy.Function(parameters, returnType)
    }

    private fun implicitSelfType(parameter: MojoParameterDeclaration): MojoTy? {
        if (parameter.nameIdentifier?.identifierText() != "self") return null
        return enclosingType(parameter)
    }

    private fun enclosingType(element: PsiElement): MojoTy? {
        val struct = PsiTreeUtil.getParentOfType(element, MojoStructDeclaration::class.java, true)
        if (struct != null) return MojoTy.Struct(struct)
        val trait = PsiTreeUtil.getParentOfType(element, MojoTraitDeclaration::class.java, true)
        if (trait != null) return MojoTy.Trait(trait)
        return null
    }

    private fun parameterConvention(parameter: MojoParameterDeclaration): MojoParameterConvention = when {
        parameter.hasToken(MojoTypes.OUT) -> MojoParameterConvention.OUT
        parameter.hasToken(MojoTypes.VAR) -> MojoParameterConvention.VAR
        parameter.hasToken(MojoTypes.MUT) -> MojoParameterConvention.MUT
        parameter.hasToken(MojoTypes.IMM) -> MojoParameterConvention.IMM
        parameter.hasToken(MojoTypes.REF) -> MojoParameterConvention.REF
        parameter.hasToken(MojoTypes.OWNED) -> MojoParameterConvention.OWNED
        parameter.hasToken(MojoTypes.READ) -> MojoParameterConvention.READ
        parameter.hasToken(MojoTypes.DEINIT) -> MojoParameterConvention.DEINIT
        else -> MojoParameterConvention.DEFAULT
    }

    private fun argumentExpression(argument: MojoArgument): MojoExpression? =
        argument.namedParameter?.expression ?: argument.expression

    private fun compatibilityScore(expected: MojoTy, actual: MojoTy): Int = when {
        expected == MojoTy.Unknown || actual == MojoTy.Unknown -> 4
        expected is MojoTy.TypeParameter -> 0
        expected == actual -> 0
        actual == MojoTy.Builtin("IntLiteral") && expected.isIntegerType() -> 1
        actual == MojoTy.Builtin("FloatLiteral") && expected.isFloatType() -> 1
        actual == MojoTy.Builtin("StringLiteral") && expected == MojoTy.Builtin("String") -> 1
        expected is MojoTy.Union && expected.variants.any { compatibilityScore(it, actual) < INCOMPATIBLE } -> 2
        else -> INCOMPATIBLE
    }

    private data class CallMatch(
        val function: MojoTy.Function,
        val returnType: MojoTy,
        val score: Int,
    )

    private companion object {
        const val INCOMPATIBLE = 1_000_000
        val COMPARISON_OPERATORS = setOf("<", "<=", "==", "!=", "<>", ">=", ">", "in", "not in", "is", "is not")
        val BUILTIN_TYPES = setOf(
            "Bool", "Byte", "DType", "SIMD", "SIMDLength", "Scalar", "Int", "Int8", "Int16", "Int32",
            "Int64", "Int128", "Int256", "UInt", "UInt8", "UInt16", "UInt32", "UInt64", "UInt128",
            "UInt256", "BFloat16", "Float8_e4m3fn", "Float8_e4m3fnuz", "Float8_e5m2", "Float8_e5m2fnuz",
            "Float8_e8m0fnu", "Float16", "Float32", "Float64", "String", "StringLiteral", "IntLiteral",
            "FloatLiteral", "NoneType", "Never", "Tuple", "List", "Dict",
        )
    }
}

private fun MojoTy.isTypeConstraint(): Boolean = when (this) {
    MojoTy.Unknown -> true
    is MojoTy.Trait, is MojoTy.TypeParameter -> true
    is MojoTy.Builtin -> name == "AnyType"
    is MojoTy.Union -> variants.isNotEmpty() && variants.all(MojoTy::isTypeConstraint)
    else -> false
}

private fun MojoTy.declarationOwners(): List<PsiElement> = when (this) {
    is MojoTy.Struct -> listOf(declaration)
    is MojoTy.Trait -> listOf(declaration)
    is MojoTy.TypeValue -> instanceType.declarationOwners()
    is MojoTy.Module -> listOf(declaration)
    is MojoTy.Union -> variants.flatMap(MojoTy::declarationOwners).distinct()
    else -> emptyList()
}

private fun MojoTy.withArguments(arguments: List<MojoTy>): MojoTy {
    if (arguments.isEmpty()) return this
    return when (this) {
        is MojoTy.Builtin -> copy(arguments = arguments)
        is MojoTy.Struct -> copy(arguments = arguments)
        is MojoTy.Trait -> copy(arguments = arguments)
        else -> this
    }
}

private fun MojoTy.Function.withoutReceiver(): MojoTy.Function =
    if (parameters.firstOrNull()?.name == "self") copy(parameters = parameters.drop(1)) else this

private fun MojoTy.isIntegerType(): Boolean =
    this is MojoTy.Builtin && (name == "Int" || name.startsWith("Int") || name.startsWith("UInt"))

private fun MojoTy.isFloatType(): Boolean =
    this is MojoTy.Builtin && (name.startsWith("Float") || name == "BFloat16")
