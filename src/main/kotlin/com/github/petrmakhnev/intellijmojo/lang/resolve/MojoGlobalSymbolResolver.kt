package com.github.petrmakhnev.intellijmojo.lang.resolve

import com.github.petrmakhnev.intellijmojo.lang.psi.MojoDottedName
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoFile
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoAssignmentStatement
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
import com.github.petrmakhnev.intellijmojo.lang.psi.mojoPsiManager
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiTreeUtil

internal object MojoGlobalSymbolResolver {
    fun resolve(place: PsiElement, name: String): List<PsiElement> {
        val file = place.containingFile as? MojoFile ?: return emptyList()
        return resolveInFile(file, name, place, linkedSetOf())
    }

    fun resolveMember(place: MojoMemberExpression): List<PsiElement> {
        val owners = resolveExpression(place.expression)
        if (owners.isEmpty()) return emptyList()
        return resolveMember(place, owners)
    }

    fun resolveMember(place: MojoMemberExpression, owners: Collection<PsiElement>): List<PsiElement> {
        val name = place.nameIdentifier.text.removeSurrounding("`")
        val moduleMembers = resolveFromModules(
            owners.filter { it is PsiFile || it is PsiDirectory },
            name,
            place,
            linkedSetOf(),
        )
        val typeMembers = resolveTypeMember(owners, name)
        return (moduleMembers + typeMembers).distinctBy(::elementKey)
    }

    fun resolveTypeMember(owners: Collection<PsiElement>, name: String): List<PsiElement> =
        owners.flatMap { owner -> findTypeMembers(owner, name) }.distinctBy(::elementKey)

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
            val snapshot = fileSnapshot(file)
            val declarations = snapshot.topLevelDeclarations[name].orEmpty().filter(PsiElement::isValid)
            if (declarations.isNotEmpty()) return declarations

            val imported = resolveDirectImports(snapshot, name, place, visiting)
                .distinctBy(::elementKey)
            if (imported.isNotEmpty()) return imported

            if (name == STDLIB_PACKAGE_NAME) {
                val std = MojoModuleResolver.resolveModule(place, listOf(STDLIB_PACKAGE_NAME), relativeLevel = 0)
                if (std.isNotEmpty()) return std
            }
            if (name.startsWith('_')) return emptyList()

            for (statement in snapshot.wildcardImports.asReversed()) {
                if (!statement.isValid) continue
                val modules = resolveImportSource(statement)
                val resolved = resolveFromModules(modules, name, place, visiting)
                if (resolved.isNotEmpty()) return resolved
            }

            val prelude = MojoModuleResolver.resolveModule(place, PRELUDE_MODULE, relativeLevel = 0)
            return resolveFromModules(prelude, name, place, visiting)
        } finally {
            visiting.remove(key)
        }
    }

    private fun resolveDirectImports(
        snapshot: FileSnapshot,
        name: String,
        place: PsiElement,
        visiting: MutableSet<LookupKey>,
    ): List<PsiElement> = buildList {
        for (targetPath in snapshot.moduleImports[name].orEmpty()) {
            addAll(MojoModuleResolver.resolveModule(place, targetPath, relativeLevel = 0))
        }

        for ((statement, sourceNames) in snapshot.selectiveImports[name].orEmpty().groupBy { it.statement }) {
            if (!statement.isValid) continue
            val modules = resolveImportSource(statement)
            for (binding in sourceNames) {
                addAll(resolveFromModules(modules, binding.sourceName, place, visiting))
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
        findTypeMembers(this, "__init__").filterIsInstance<MojoFunctionDeclaration>()

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

    private fun findTypeMembers(owner: PsiElement, name: String): List<PsiElement> {
        if (owner !is MojoStructDeclaration && owner !is MojoTraitDeclaration) return emptyList()
        if (!owner.isValid) return emptyList()
        return typeMemberSnapshot(owner).members[name].orEmpty().filter(PsiElement::isValid)
    }

    private fun nearestDeclaringType(element: PsiElement): PsiElement? {
        var current = element.parent
        while (current != null) {
            when (current) {
                is MojoFunctionDeclaration -> return null
                is MojoStructDeclaration, is MojoTraitDeclaration -> return current
            }
            current = current.parent
        }
        return null
    }

    private fun resolveImportSource(statement: MojoSelectiveImportStatement): List<PsiElement> {
        val relativeModule = statement.relativeModule ?: return emptyList()
        val relativeLevel = relativeModule.node.getChildren(null).count { it.elementType == MojoTypes.DOT }
        return MojoModuleResolver.resolveImportSource(statement, relativeModule.dottedName, relativeLevel)
    }

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

    private fun fileSnapshot(file: MojoFile): FileSnapshot =
        CachedValuesManager.getCachedValue(file, FILE_SNAPSHOT_KEY) {
            CachedValueProvider.Result.create(
                buildFileSnapshot(file),
                file.project.mojoPsiManager.structureModificationTracker,
            )
        }

    private fun buildFileSnapshot(file: MojoFile): FileSnapshot {
        val declarations = LinkedHashMap<String, MutableList<MojoNamedElement>>()
        val moduleStatements = mutableListOf<MojoModuleImportStatement>()
        val selectiveStatements = mutableListOf<MojoSelectiveImportStatement>()

        PsiTreeUtil.processElements(file) { element ->
            when (element) {
                is MojoModuleImportStatement -> if (element.isFileLevel()) moduleStatements += element
                is MojoSelectiveImportStatement -> if (element.isFileLevel()) selectiveStatements += element
                is MojoNamedElement -> if (element.isFileLevel()) {
                    element.name?.let { name -> declarations.getOrPut(name, ::mutableListOf) += element }
                }
            }
            true
        }

        val moduleImports = LinkedHashMap<String, MutableList<List<String>>>()
        for (statement in moduleStatements.sortedBy(PsiElement::getTextOffset)) {
            for (item in statement.importItemList) {
                val components = item.pathComponents()
                if (components.isEmpty()) continue
                val alias = item.nameIdentifier?.text?.removeSurrounding("`")
                val boundName = alias ?: components.first()
                val targetPath = if (alias == null) components.take(1) else components
                moduleImports.getOrPut(boundName, ::mutableListOf) += targetPath
            }
        }

        val selectiveImports = LinkedHashMap<String, MutableList<SelectiveImportBinding>>()
        val wildcardImports = mutableListOf<MojoSelectiveImportStatement>()
        for (statement in selectiveStatements.sortedBy(PsiElement::getTextOffset)) {
            if (statement.isWildcardImport()) {
                wildcardImports += statement
                continue
            }
            for (item in statement.importItemList) {
                val sourceName = item.pathComponents().singleOrNull() ?: continue
                val boundName = item.nameIdentifier?.text?.removeSurrounding("`") ?: sourceName
                selectiveImports.getOrPut(boundName, ::mutableListOf) +=
                    SelectiveImportBinding(statement, sourceName)
            }
        }

        return FileSnapshot(
            declarations.mapValues { (_, elements) -> elements.distinctBy(::elementKey) },
            moduleImports.mapValues { (_, paths) -> paths.toList() },
            selectiveImports.mapValues { (_, bindings) -> bindings.toList() },
            wildcardImports,
        )
    }

    private fun typeMemberSnapshot(owner: PsiElement): TypeMemberSnapshot =
        CachedValuesManager.getCachedValue(owner, TYPE_MEMBER_SNAPSHOT_KEY) {
            CachedValueProvider.Result.create(
                buildTypeMemberSnapshot(owner),
                owner.project.mojoPsiManager.structureModificationTracker,
            )
        }

    private fun buildTypeMemberSnapshot(owner: PsiElement): TypeMemberSnapshot {
        val members = LinkedHashMap<String, LinkedHashSet<PsiElement>>()
        fun add(name: String?, element: PsiElement) {
            val normalized = name?.removeSurrounding("`") ?: return
            members.getOrPut(normalized, ::linkedSetOf) += element
        }

        when (owner) {
            is MojoStructDeclaration -> owner.structHeader.typeParameterList?.parameterDeclarationList.orEmpty()
            is MojoTraitDeclaration -> owner.typeParameterList?.parameterDeclarationList.orEmpty()
            else -> emptyList()
        }.mapNotNull { it.nameIdentifier }
            .forEach { add(it.text, it) }

        PsiTreeUtil.processElements(owner) { element ->
            when {
                element === owner -> Unit
                element is MojoNamedElement -> {
                    val belongsToOwner = when (element) {
                        is MojoFunctionDeclaration -> element.declaringType() == owner
                        else -> nearestDeclaringType(element) == owner
                    }
                    if (belongsToOwner) add(element.name, element)
                }
                element is MojoAssignmentStatement &&
                    nearestDeclaringType(element) == owner &&
                    PsiTreeUtil.getParentOfType(element, MojoFunctionDeclaration::class.java) == null -> {
                    element.bindingIdentifiers().forEach { add(it.text, it) }
                }
            }
            true
        }

        return TypeMemberSnapshot(members.mapValues { (_, elements) -> elements.toList() })
    }

    private data class LookupKey(val file: String, val name: String)

    private data class SelectiveImportBinding(
        val statement: MojoSelectiveImportStatement,
        val sourceName: String,
    )

    private data class FileSnapshot(
        val topLevelDeclarations: Map<String, List<MojoNamedElement>>,
        val moduleImports: Map<String, List<List<String>>>,
        val selectiveImports: Map<String, List<SelectiveImportBinding>>,
        val wildcardImports: List<MojoSelectiveImportStatement>,
    )

    private data class TypeMemberSnapshot(val members: Map<String, List<PsiElement>>)

    private val FILE_SNAPSHOT_KEY: Key<CachedValue<FileSnapshot>> =
        Key.create("MOJO_GLOBAL_FILE_SNAPSHOT")
    private val TYPE_MEMBER_SNAPSHOT_KEY: Key<CachedValue<TypeMemberSnapshot>> =
        Key.create("MOJO_TYPE_MEMBER_SNAPSHOT")

    private const val STDLIB_PACKAGE_NAME = "std"
    private val PRELUDE_MODULE = listOf(STDLIB_PACKAGE_NAME, "prelude")
}
