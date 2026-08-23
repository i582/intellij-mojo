package com.github.petrmakhnev.intellijmojo.lang.resolve

import com.github.petrmakhnev.intellijmojo.lang.psi.MojoDottedName
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoFile
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoCallExpression
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoExpression
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoFunctionDeclaration
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoImportItem
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoMemberExpression
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoModuleImportStatement
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoNamedElement
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoSelectiveImportStatement
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoStructDeclaration
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoSubscriptExpression
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoTraitDeclaration
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoTransferExpression
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoTypes
import com.github.petrmakhnev.intellijmojo.lang.stub.index.MojoNamedElementIndex
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.util.PsiTreeUtil

internal object MojoGlobalSymbolResolver {
    fun resolve(place: PsiElement, name: String): List<PsiElement> {
        val file = place.containingFile as? MojoFile ?: return emptyList()
        return resolveInFile(file, name, place, linkedSetOf())
    }

    fun resolveMember(place: MojoMemberExpression): List<PsiElement> {
        val owners = resolveExpression(place.expression)
        if (owners.isEmpty()) return emptyList()
        return resolveFromModules(
            owners,
            place.nameIdentifier.text.removeSurrounding("`"),
            place,
            linkedSetOf(),
        )
    }

    fun resolveCallAware(place: MojoExpression, targets: List<PsiElement>): List<PsiElement> {
        if (!place.isCallTarget()) return targets
        return targets.flatMap { target ->
            if (target is MojoStructDeclaration) {
                val constructors = target.constructors()
                if (constructors.isEmpty()) listOf(target) else constructors
            } else {
                listOf(target)
            }
        }.distinctBy(::elementKey)
    }

    fun resolveImportedTarget(
        place: PsiElement,
        source: MojoDottedName?,
        relativeLevel: Int,
        name: String,
    ): List<PsiElement> {
        val modules = MojoModuleResolver.resolveImportSource(place, source, relativeLevel)
        return resolveFromModules(modules, name, place, linkedSetOf())
    }

    private fun resolveInFile(
        file: MojoFile,
        name: String,
        place: PsiElement,
        visiting: MutableSet<LookupKey>,
    ): List<PsiElement> {
        val key = LookupKey(file.virtualFile?.path ?: file.name, name)
        if (!visiting.add(key)) return emptyList()

        try {
            val direct = buildList {
                addAll(findTopLevelDeclarations(file, name, place))
                addAll(resolveDirectImports(file, name, place, visiting))
            }.distinctBy(::elementKey)
            if (direct.isNotEmpty()) return direct
            if (name.startsWith('_')) return emptyList()

            for (statement in selectiveImports(file).asReversed()) {
                if (!statement.isWildcardImport()) continue
                val modules = resolveImportSource(statement)
                val resolved = resolveFromModules(modules, name, place, visiting)
                if (resolved.isNotEmpty()) return resolved
            }
            return emptyList()
        } finally {
            visiting.remove(key)
        }
    }

    private fun findTopLevelDeclarations(
        file: MojoFile,
        name: String,
        place: PsiElement,
    ): List<MojoNamedElement> {
        val indexed = MojoNamedElementIndex.find(name, place.project, place.resolveScope)
        val parsed = PsiTreeUtil.findChildrenOfType(file, MojoNamedElement::class.java)
        return (indexed.asSequence() + parsed.asSequence())
            .filter { it.name == name && it.containingFile?.virtualFile == file.virtualFile }
            .filter { it.isFileLevel() }
            .distinctBy(::elementKey)
            .toList()
    }

    private fun resolveDirectImports(
        file: MojoFile,
        name: String,
        place: PsiElement,
        visiting: MutableSet<LookupKey>,
    ): List<PsiElement> = buildList {
        for (statement in moduleImports(file)) {
            for (item in statement.importItemList) {
                val components = item.pathComponents()
                if (components.isEmpty()) continue
                val alias = item.nameIdentifier?.text?.removeSurrounding("`")
                val boundName = alias ?: components.first()
                if (boundName != name) continue

                val targetPath = if (alias == null) components.take(1) else components
                addAll(MojoModuleResolver.resolveModule(place, targetPath, relativeLevel = 0))
            }
        }

        for (statement in selectiveImports(file)) {
            if (statement.isWildcardImport()) continue
            val modules = resolveImportSource(statement)
            for (item in statement.importItemList) {
                val sourceName = item.pathComponents().singleOrNull() ?: continue
                val boundName = item.nameIdentifier?.text?.removeSurrounding("`") ?: sourceName
                if (boundName != name) continue
                addAll(resolveFromModules(modules, sourceName, place, visiting))
            }
        }
    }

    private fun resolveFromModules(
        modules: Collection<PsiElement>,
        name: String,
        place: PsiElement,
        visiting: MutableSet<LookupKey>,
    ): List<PsiElement> {
        val declarations = modules.flatMap { module ->
            val file = MojoModuleResolver.moduleFile(place.project, module) ?: return@flatMap emptyList()
            resolveInFile(file, name, place, visiting)
        }.distinctBy(::elementKey)
        if (declarations.isNotEmpty()) return declarations

        return MojoModuleResolver.resolveSubmodule(place, modules, listOf(name))
    }

    private fun resolveExpression(expression: PsiElement): List<PsiElement> = expression.references.flatMap { reference ->
        if (reference is PsiPolyVariantReference) {
            reference.multiResolve(false).mapNotNull { it.element }
        } else {
            listOfNotNull(reference.resolve())
        }
    }.distinctBy(::elementKey)

    private fun MojoExpression.isCallTarget(): Boolean {
        var target = this
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
            return parent is MojoCallExpression && parent.expression == target
        }
    }

    private fun MojoStructDeclaration.constructors(): List<MojoFunctionDeclaration> =
        PsiTreeUtil.findChildrenOfType(this, MojoFunctionDeclaration::class.java)
            .filter { it.name == "__init__" && it.declaringType() == this }

    private fun MojoFunctionDeclaration.declaringType(): PsiElement? {
        var ancestor = parent
        while (ancestor != null) {
            when (ancestor) {
                is MojoFunctionDeclaration -> return null
                is MojoStructDeclaration, is MojoTraitDeclaration -> return ancestor
            }
            ancestor = ancestor.parent
        }
        return null
    }

    private fun resolveImportSource(statement: MojoSelectiveImportStatement): List<PsiElement> {
        val relativeModule = statement.relativeModule ?: return emptyList()
        val relativeLevel = relativeModule.node.getChildren(null).count { it.elementType == MojoTypes.DOT }
        return MojoModuleResolver.resolveImportSource(statement, relativeModule.dottedName, relativeLevel)
    }

    private fun moduleImports(file: MojoFile): List<MojoModuleImportStatement> =
        PsiTreeUtil.findChildrenOfType(file, MojoModuleImportStatement::class.java)
            .filter { it.isFileLevel() }
            .sortedBy { it.textOffset }

    private fun selectiveImports(file: MojoFile): List<MojoSelectiveImportStatement> =
        PsiTreeUtil.findChildrenOfType(file, MojoSelectiveImportStatement::class.java)
            .filter { it.isFileLevel() }
            .sortedBy { it.textOffset }

    private fun MojoSelectiveImportStatement.isWildcardImport(): Boolean =
        importItemList.isEmpty() && node.findChildByType(MojoTypes.STAR) != null

    private fun MojoImportItem.pathComponents(): List<String> =
        dottedName.nameIdentifierList.map { it.text.removeSurrounding("`") }

    private fun PsiElement.isFileLevel(): Boolean {
        var current = parent
        while (current != null && current !is PsiFile) {
            if (current is MojoFunctionDeclaration || current is MojoStructDeclaration || current is MojoTraitDeclaration) {
                return false
            }
            current = current.parent
        }
        return current is PsiFile
    }

    private fun elementKey(element: PsiElement): String = when (element) {
        is PsiDirectory -> element.virtualFile.path
        is PsiFile -> element.virtualFile?.path ?: element.name
        else -> "${element.containingFile?.virtualFile?.path}:${element.textOffset}:${element.javaClass.name}"
    }

    private data class LookupKey(val file: String, val name: String)
}
