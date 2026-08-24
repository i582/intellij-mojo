package com.github.petrmakhnev.intellijmojo.lang.highlighting

import com.github.petrmakhnev.intellijmojo.ide.colors.MojoColor
import com.github.petrmakhnev.intellijmojo.lang.psi.*
import com.github.petrmakhnev.intellijmojo.lang.resolve.MojoBindingKind
import com.github.petrmakhnev.intellijmojo.lang.resolve.staticBindingKind
import com.github.petrmakhnev.intellijmojo.lang.type.MOJO_BUILTIN_TYPE_NAMES
import com.github.petrmakhnev.intellijmojo.lang.type.isTypeValuedComptime
import com.github.petrmakhnev.intellijmojo.lang.type.mojoInference
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.util.PsiTreeUtil

class MojoSemanticAnnotator : Annotator {
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
        if (parent is MojoDeclarationName) return declarationColor(parent)
        if (parent is MojoParameterDeclaration) return parameterColor(parent)
        bindingColor(name)?.let { return it }
        return when (parent) {
            is MojoNamedParameter -> MojoColor.KEYWORD_ARGUMENT
            is MojoImportItem -> resolvedColor(name, importSite = true)
            is MojoMemberExpression -> memberColor(parent)
            is MojoReferenceExpression -> referenceColor(parent)
            is MojoClassPattern -> resolvedColor(name) ?: typeColor(name.text)
            is MojoDottedName -> resolvedColor(name, importSite = true)
            else -> bindingColor(name)
                ?: resolvedColor(name)
                ?: if (isTypeName(name)) typeColor(name.text) else null
        }
    }

    private fun declarationColor(declaration: MojoDeclarationName): MojoColor? = when (val owner = declaration.parent) {
        is MojoFunctionSignature -> functionColor(owner.parent as? MojoFunctionDeclaration)
        is MojoStructHeader -> MojoColor.STRUCT
        is MojoTraitDeclaration -> MojoColor.TRAIT
        is MojoMlirRegionDeclaration -> MojoColor.MLIR_SYMBOL
        is MojoComptimeStatement -> comptimeDeclarationColor(owner)
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
        resolvedColor(member.nameIdentifier, isCallTarget(member))
            ?: if (isCallTarget(member)) MojoColor.METHOD_CALL else MojoColor.FIELD

    private fun referenceColor(reference: MojoReferenceExpression): MojoColor? {
        val name = reference.nameIdentifier
        if (isDecoratorName(name)) return MojoColor.DECORATOR
        if (name.identifierText() in MOJO_BUILTIN_TYPE_NAMES) return MojoColor.BUILTIN_TYPE
        resolvedColor(name, isCallTarget(reference))?.let { return it }
        if (isTypeName(name)) return MojoColor.TYPE_REFERENCE
        if (isCallTarget(reference)) return MojoColor.FUNCTION_CALL
        return null
    }

    private fun resolvedColor(
        name: MojoNameIdentifier,
        callSite: Boolean = false,
        importSite: Boolean = false,
    ): MojoColor? = resolveTargets(name).firstNotNullOfOrNull { target ->
        targetColor(target, name.identifierText(), callSite, isTypeName(name), importSite)
    }

    private fun resolveTargets(name: MojoNameIdentifier): List<PsiElement> {
        val importItem = name.parent as? MojoImportItem
        val aliasReferences = importItem
            ?.takeIf { it.nameIdentifier == name }
            ?.dottedName
            ?.references
            ?.takeLast(1)
        val owner = when (val parent = name.parent) {
            is MojoReferenceExpression -> parent
            is MojoMemberExpression -> parent
            is MojoDottedName -> parent
            else -> name
        }
        val offset = name.textOffset - owner.textOffset
        val references = aliasReferences ?: owner.references
            .filter { offset >= it.rangeInElement.startOffset && offset < it.rangeInElement.endOffset }
        return references
            .flatMap { reference ->
                if (reference is PsiPolyVariantReference) {
                    reference.multiResolve(false).mapNotNull { it.element }
                } else {
                    listOfNotNull(reference.resolve())
                }
            }
            .distinctBy { target ->
                "${target.containingFile?.virtualFile?.path}:${target.textOffset}:${target.javaClass.name}"
            }
    }

    private fun targetColor(
        target: PsiElement,
        sourceName: String,
        callSite: Boolean,
        typeSite: Boolean,
        importSite: Boolean,
    ): MojoColor? = when (target) {
        is MojoFunctionDeclaration -> when {
            callSite && target.name == "__init__" && sourceName != "__init__" -> MojoColor.STRUCT
            callSite -> functionCallColor(target)
            else -> functionColor(target)
        }
        is MojoStructDeclaration -> MojoColor.STRUCT
        is MojoTraitDeclaration -> if (importSite) MojoColor.TRAIT else MojoColor.TYPE_REFERENCE
        is MojoComptimeStatement -> comptimeReferenceColor(target, importSite)
        is MojoParameterDeclaration -> parameterColor(target)
        is MojoMlirRegionDeclaration -> MojoColor.MLIR_SYMBOL
        is MojoNameIdentifier -> targetIdentifierColor(target, sourceName, callSite, typeSite, importSite)
        is PsiFile, is PsiDirectory -> MojoColor.MODULE
        else -> null
    }

    private fun targetIdentifierColor(
        target: MojoNameIdentifier,
        sourceName: String,
        callSite: Boolean,
        typeSite: Boolean,
        importSite: Boolean,
    ): MojoColor? {
        return when (val parent = target.parent) {
            is MojoDeclarationName -> when (val declaration = parent.parent) {
                is MojoFunctionSignature -> {
                    val function = declaration.parent as? MojoFunctionDeclaration
                    when {
                        callSite && function?.name == "__init__" && sourceName != "__init__" -> MojoColor.STRUCT
                        callSite -> functionCallColor(function)
                        else -> functionColor(function)
                    }
                }
                is MojoStructHeader -> MojoColor.STRUCT
                is MojoTraitDeclaration -> if (importSite) MojoColor.TRAIT else MojoColor.TYPE_REFERENCE
                is MojoComptimeStatement -> comptimeReferenceColor(declaration, importSite)
                is MojoMlirRegionDeclaration -> MojoColor.MLIR_SYMBOL
                else -> null
            }
            is MojoParameterDeclaration -> parameterColor(parent)
            else -> bindingColor(target) ?: if (typeSite) typeColor(target.text) else null
        }
    }

    private fun functionCallColor(function: MojoFunctionDeclaration?): MojoColor =
        if (functionColor(function) == MojoColor.METHOD) {
            MojoColor.METHOD_CALL
        } else {
            MojoColor.FUNCTION_CALL
        }

    private fun comptimeDeclarationColor(declaration: MojoComptimeStatement): MojoColor =
        if (declaration.isTypeValuedComptime()) MojoColor.TYPE_ALIAS else MojoColor.CONSTANT

    private fun comptimeReferenceColor(
        declaration: MojoComptimeStatement,
        importSite: Boolean,
    ): MojoColor = if (declaration.isTypeValuedComptime()) {
        if (importSite) MojoColor.TYPE_ALIAS else MojoColor.TYPE_REFERENCE
    } else {
        MojoColor.CONSTANT
    }

    private fun bindingColor(name: MojoNameIdentifier): MojoColor? {
        if (isAssignmentTargetField(name)) return MojoColor.FIELD

        val staticKind = name.staticBindingKind()
        val kind = staticKind ?: if (name.mojoInference?.isLocalDeclaration(name) == true) {
            MojoBindingKind.VARIABLE
        } else {
            return null
        }
        return when (kind) {
            MojoBindingKind.TYPE_PARAMETER,
            MojoBindingKind.PARAMETER,
            MojoBindingKind.CAPTURE_PARAMETER,
            -> (name.parent as? MojoParameterDeclaration)?.let(::parameterColor)

            MojoBindingKind.VARIABLE -> {
                val assignment = parentOf<MojoAssignmentStatement>(name)
                if (assignment != null && nearestScopeOwner(assignment) is MojoStructDeclaration) {
                    MojoColor.FIELD
                } else {
                    val assignmentExpression = parentOf<MojoAssignmentExpression>(name)
                    if (staticKind == null || assignment?.hasToken(MojoTypes.VAR) == true ||
                        assignmentExpression?.hasToken(MojoTypes.VAR) == true
                    ) {
                        MojoColor.MUTABLE_VARIABLE
                    } else {
                        MojoColor.VARIABLE
                    }
                }
            }

            MojoBindingKind.LOOP_VARIABLE -> {
                val loop = parentOf<MojoForStatement>(name)
                if (loop?.hasToken(MojoTypes.VAR) == true) MojoColor.MUTABLE_VARIABLE else MojoColor.VARIABLE
            }

            MojoBindingKind.WITH_VARIABLE,
            MojoBindingKind.EXCEPTION_VARIABLE,
            -> MojoColor.MUTABLE_VARIABLE

            MojoBindingKind.COMPREHENSION_VARIABLE,
            MojoBindingKind.MATCH_VARIABLE,
            -> MojoColor.VARIABLE
        }
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
        if (name.removeSurrounding("`") in MOJO_BUILTIN_TYPE_NAMES) MojoColor.BUILTIN_TYPE else MojoColor.TYPE_REFERENCE

    private fun MojoNameIdentifier.identifierText(): String = text.removeSurrounding("`")

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

}
