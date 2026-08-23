package com.github.petrmakhnev.intellijmojo.lang.highlighting

import com.github.petrmakhnev.intellijmojo.ide.colors.MojoColor
import com.github.petrmakhnev.intellijmojo.lang.psi.*
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil

/** Applies semantic colors without resolving names. */
class MojoSemanticAnnotator : Annotator, DumbAware {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        val name = element as? MojoNameIdentifier ?: return
        val color = colorFor(name) ?: return
        holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
            .range(name)
            .textAttributes(color.textAttributesKey)
            .create()
    }

    private fun colorFor(name: MojoNameIdentifier): MojoColor? {
        val parent = name.parent
        return when (parent) {
            is MojoDeclarationName -> declarationColor(parent)
            is MojoParameterDeclaration -> parameterColor(parent)
            is MojoNamedParameter -> MojoColor.KEYWORD_ARGUMENT
            is MojoMemberExpression -> memberColor(parent)
            is MojoReferenceExpression -> referenceColor(parent)
            is MojoClassPattern -> typeColor(name.text)
            else -> bindingColor(name) ?: if (isTypeName(name)) typeColor(name.text) else null
        }
    }

    private fun declarationColor(declaration: MojoDeclarationName): MojoColor? = when (val owner = declaration.parent) {
        is MojoFunctionSignature -> functionColor(owner.parent as? MojoFunctionDeclaration)
        is MojoStructHeader -> MojoColor.STRUCT
        is MojoTraitDeclaration -> MojoColor.TRAIT
        is MojoMlirRegionDeclaration -> MojoColor.MLIR_SYMBOL
        is MojoComptimeStatement -> if (owner.node.findChildByType(MojoTypes.TYPE) != null) {
            MojoColor.TYPE_ALIAS
        } else {
            MojoColor.CONSTANT
        }
        else -> null
    }

    private fun functionColor(function: MojoFunctionDeclaration?): MojoColor {
        var ancestor = function?.parent
        while (ancestor != null && ancestor !is PsiFile) {
            when (ancestor) {
                is MojoStructDeclaration, is MojoTraitDeclaration -> return MojoColor.METHOD
                is MojoFunctionDeclaration -> return MojoColor.NESTED_FUNCTION
            }
            ancestor = ancestor.parent
        }
        return MojoColor.FUNCTION
    }

    private fun parameterColor(parameter: MojoParameterDeclaration): MojoColor {
        if (parentOf<MojoTypeParameterList>(parameter) != null) return MojoColor.TYPE_PARAMETER
        val name = parameter.nameIdentifier?.text
        if (name == "self") return MojoColor.SELF_PARAMETER

        val capture = parameter.parent as? MojoCaptureParameter
        val mutable = parameter.hasToken(MojoTypes.VAR) ||
            parameter.hasToken(MojoTypes.MUT) ||
            parameter.hasToken(MojoTypes.OUT) ||
            capture?.hasToken(MojoTypes.VAR) == true
        return if (mutable) MojoColor.MUTABLE_PARAMETER else MojoColor.PARAMETER
    }

    private fun memberColor(member: MojoMemberExpression): MojoColor =
        if (isCallTarget(member)) MojoColor.METHOD_CALL else MojoColor.FIELD

    private fun referenceColor(reference: MojoReferenceExpression): MojoColor? {
        val name = reference.nameIdentifier
        if (isDecoratorName(name)) return MojoColor.DECORATOR
        if (name.text in BUILTIN_TYPE_NAMES) return MojoColor.BUILTIN_TYPE
        if (isTypeName(name)) return MojoColor.TYPE_REFERENCE
        if (isCallTarget(reference)) return MojoColor.FUNCTION_CALL
        return null
    }

    private fun bindingColor(name: MojoNameIdentifier): MojoColor? {
        if (isAssignmentTargetField(name)) return MojoColor.FIELD

        val assignment = parentOf<MojoAssignmentStatement>(name)
        if (assignment != null && isAssignmentBinding(name, assignment)) {
            if (nearestScopeOwner(assignment) is MojoStructDeclaration) return MojoColor.FIELD
            return if (assignment.hasToken(MojoTypes.VAR)) MojoColor.MUTABLE_VARIABLE else MojoColor.VARIABLE
        }

        val loop = parentOf<MojoForStatement>(name)
        if (loop?.patternList?.let { PsiTreeUtil.isAncestor(it, name, false) } == true) {
            return if (loop.hasToken(MojoTypes.VAR)) MojoColor.MUTABLE_VARIABLE else MojoColor.VARIABLE
        }

        val comprehension = parentOf<MojoForInClause>(name)
        if (comprehension?.patternList?.let { PsiTreeUtil.isAncestor(it, name, false) } == true) {
            return MojoColor.VARIABLE
        }
        return null
    }

    private fun isAssignmentTargetField(name: MojoNameIdentifier): Boolean {
        if (PsiTreeUtil.prevLeaf(name)?.node?.elementType != MojoTypes.DOT) return false

        val assignment = parentOf<MojoAssignmentStatement>(name)
        if (assignment != null) {
            val equalsStart = assignment.node.findChildByType(MojoTypes.EQ)?.startOffset ?: return false
            return name.textOffset < equalsStart
        }

        val augmented = parentOf<MojoAugmentedAssignmentStatement>(name) ?: return false
        val rhsStart = augmented.expressionSequence?.textOffset ?: return false
        return name.textOffset < rhsStart
    }

    private fun isAssignmentBinding(name: MojoNameIdentifier, assignment: MojoAssignmentStatement): Boolean {
        if (!assignment.hasToken(MojoTypes.VAR) && !assignment.hasToken(MojoTypes.REF)) return false
        val typeStart = assignment.typeExpression?.textOffset ?: Int.MAX_VALUE
        val equalsStart = assignment.node.findChildByType(MojoTypes.EQ)?.startOffset ?: Int.MAX_VALUE
        return name.textOffset < minOf(typeStart, equalsStart)
    }

    private fun isCallTarget(expression: MojoExpression): Boolean {
        var target = expression
        while (true) {
            val parent = target.parent
            if (parent is MojoSubscriptExpression && parent.expression == target) {
                target = parent
                continue
            }
            if (parent is MojoTransferExpression && parent.expression == target) {
                target = parent
                continue
            }
            val call = parent as? MojoCallExpression ?: return false
            return call.expression == target
        }
    }

    private fun isDecoratorName(name: MojoNameIdentifier): Boolean {
        val decorator = parentOf<MojoDecorator>(name) ?: return false
        val arguments = parentOf<MojoArgumentList>(name)
        return arguments == null || !PsiTreeUtil.isAncestor(decorator, arguments, true)
    }

    private fun isTypeName(name: MojoNameIdentifier): Boolean = parentOf<MojoTypeExpression>(name) != null

    private fun typeColor(name: String): MojoColor =
        if (name in BUILTIN_TYPE_NAMES) MojoColor.BUILTIN_TYPE else MojoColor.TYPE_REFERENCE

    private fun nearestScopeOwner(element: PsiElement): PsiElement? {
        var ancestor = element.parent
        while (ancestor != null && ancestor !is PsiFile) {
            if (ancestor is MojoFunctionDeclaration || ancestor is MojoStructDeclaration || ancestor is MojoTraitDeclaration) {
                return ancestor
            }
            ancestor = ancestor.parent
        }
        return ancestor
    }

    private fun PsiElement.hasToken(type: com.intellij.psi.tree.IElementType): Boolean = node.findChildByType(type) != null

    private inline fun <reified T : PsiElement> parentOf(element: PsiElement): T? =
        PsiTreeUtil.getParentOfType(element, T::class.java, true)

    private companion object {
        /** Names that receive keyword-style highlighting without resolution. */
        val BUILTIN_TYPE_NAMES = setOf(
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
        )
    }

}
