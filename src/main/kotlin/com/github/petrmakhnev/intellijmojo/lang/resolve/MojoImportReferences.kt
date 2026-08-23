package com.github.petrmakhnev.intellijmojo.lang.resolve

import com.github.petrmakhnev.intellijmojo.lang.psi.MojoDottedName
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoFile
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoModuleImportStatement
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoNamedElement
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoSelectiveImportStatement
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoTypes
import com.github.petrmakhnev.intellijmojo.lang.stub.index.MojoModuleIndex
import com.github.petrmakhnev.intellijmojo.lang.stub.index.MojoNamedElementIndex
import com.intellij.codeInsight.highlighting.HighlightedReference
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.PsiReference
import com.intellij.psi.ResolveResult
import com.intellij.psi.util.PsiTreeUtil

object MojoImportReferences {
    fun create(element: MojoDottedName): Array<PsiReference> {
        if (importContext(element) == null) return PsiReference.EMPTY_ARRAY

        return element.nameIdentifierList.mapIndexed { index, identifier ->
            val range = identifier.textRange.shiftLeft(element.textOffset)
            MojoModuleReference(element, range, index)
        }.toTypedArray()
    }

    private fun importContext(element: MojoDottedName): ImportContext? {
        val moduleImport = PsiTreeUtil.getParentOfType(element, MojoModuleImportStatement::class.java)
        if (moduleImport != null) return ImportContext.Module(relativeLevel = 0)

        val selectiveImport = PsiTreeUtil.getParentOfType(element, MojoSelectiveImportStatement::class.java)
            ?: return null
        val relativeModule = selectiveImport.relativeModule ?: return null
        val relativeLevel = relativeModule.node.getChildren(null).count { it.elementType == MojoTypes.DOT }

        return if (PsiTreeUtil.isAncestor(relativeModule, element, false)) {
            ImportContext.Module(relativeLevel)
        } else {
            ImportContext.Target(relativeModule.dottedName, relativeLevel)
        }
    }

    private sealed interface ImportContext {
        data class Module(val relativeLevel: Int) : ImportContext

        data class Target(
            val source: MojoDottedName?,
            val relativeLevel: Int,
        ) : ImportContext
    }

    private class MojoModuleReference(
        element: MojoDottedName,
        range: TextRange,
        private val segmentIndex: Int,
    ) : PsiPolyVariantReferenceBase<MojoDottedName>(element, range, false), HighlightedReference {
        override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
            val context = importContext(element) ?: return ResolveResult.EMPTY_ARRAY
            val names = element.nameIdentifierList.map { it.text.removeSurrounding("`") }
            if (segmentIndex !in names.indices) return ResolveResult.EMPTY_ARRAY

            val resolved = when (context) {
                is ImportContext.Module -> MojoModuleResolver.resolveModule(
                    element,
                    names.take(segmentIndex + 1),
                    context.relativeLevel,
                )

                is ImportContext.Target -> if (segmentIndex == 0) {
                    MojoGlobalSymbolResolver.resolveImportedTarget(
                        element,
                        context.source,
                        context.relativeLevel,
                        names.first(),
                    )
                } else {
                    MojoModuleResolver.resolveImportedTarget(
                        element,
                        context.source,
                        context.relativeLevel,
                        names.take(segmentIndex + 1),
                    )
                }
            }
            return resolved.map(::PsiElementResolveResult).toTypedArray()
        }

        override fun getVariants(): Array<Any> = emptyArray()
    }
}

internal object MojoModuleResolver {
    fun resolveModule(
        place: PsiElement,
        components: List<String>,
        relativeLevel: Int,
    ): List<PsiElement> {
        if (components.isEmpty()) return emptyList()
        if (relativeLevel > 0) {
            val anchor = relativeAnchor(place, relativeLevel) ?: return emptyList()
            return resolveFromDirectory(place.project, anchor, components)
        }

        val direct = importRoots(place).flatMap { resolveFromDirectory(place.project, it, components) }
            .distinctBy(::elementPath)
        if (direct.isNotEmpty()) return direct

        return findIndexedModules(place, components)
    }

    fun resolveImportedTarget(
        place: PsiElement,
        source: MojoDottedName?,
        relativeLevel: Int,
        targetComponents: List<String>,
    ): List<PsiElement> {
        if (targetComponents.isEmpty()) return emptyList()

        val sourceModules = resolveImportSource(place, source, relativeLevel)

        val importedName = targetComponents.first()
        val declarations = findDeclarations(importedName, sourceModules, place)
        if (declarations.isNotEmpty()) {
            return if (targetComponents.size == 1) declarations else emptyList()
        }

        return resolveSubmodule(place, sourceModules, targetComponents)
    }

    fun resolveImportSource(
        place: PsiElement,
        source: MojoDottedName?,
        relativeLevel: Int,
    ): List<PsiElement> = if (source != null) {
        resolveModule(
            place,
            source.nameIdentifierList.map { it.text.removeSurrounding("`") },
            relativeLevel,
        )
    } else {
        val anchor = relativeAnchor(place, relativeLevel) ?: return emptyList()
        listOfNotNull(toPsi(place.project, anchor))
    }

    fun resolveSubmodule(
        place: PsiElement,
        sourceModules: Collection<PsiElement>,
        components: List<String>,
    ): List<PsiElement> = sourceModules.flatMap { sourceModule ->
        val directory = moduleDirectory(sourceModule) ?: return@flatMap emptyList()
        resolveFromDirectory(place.project, directory, components)
    }.distinctBy(::elementPath)

    fun moduleFile(project: Project, module: PsiElement): MojoFile? = when (module) {
        is MojoFile -> module
        is PsiDirectory -> module.virtualFile.findChild(MojoFile.PACKAGE_INITIALIZER)
            ?.let { PsiManager.getInstance(project).findFile(it) as? MojoFile }
        else -> null
    }

    private fun findDeclarations(
        name: String,
        sourceModules: List<PsiElement>,
        place: PsiElement,
    ): List<MojoNamedElement> {
        val sourceFiles = sourceModules.mapNotNull {
            when (it) {
                is MojoFile -> it.virtualFile
                is PsiDirectory -> it.virtualFile.findChild(MojoFile.PACKAGE_INITIALIZER)
                else -> null
            }
        }.toSet()
        if (sourceFiles.isEmpty()) return emptyList()

        return MojoNamedElementIndex.find(name, place.project, place.resolveScope)
            .filter { it.containingFile?.virtualFile in sourceFiles && it.isTopLevelDeclaration() }
    }

    private fun MojoNamedElement.isTopLevelDeclaration(): Boolean {
        var current = parent
        while (current != null && current !is PsiFile) {
            if (current is MojoNamedElement) return false
            current = current.parent
        }
        return true
    }

    private fun findIndexedModules(place: PsiElement, components: List<String>): List<PsiElement> {
        val relativeModule = components.joinToString("/")
        return MojoModuleIndex.find(components.last(), place.project, place.resolveScope)
            .filter { file ->
                val path = file.virtualFile?.path ?: return@filter false
                path.endsWith("/$relativeModule.mojo") ||
                    path.endsWith("/$relativeModule/${MojoFile.PACKAGE_INITIALIZER}")
            }
            .distinctBy(::elementPath)
    }

    private fun importRoots(place: PsiElement): List<VirtualFile> = buildList {
        val rootManager = ProjectRootManager.getInstance(place.project)
        addAll(rootManager.contentSourceRoots)
        addAll(rootManager.contentRoots)
    }.filter { it.isDirectory }.distinctBy { it.path }

    private fun relativeAnchor(place: PsiElement, relativeLevel: Int): VirtualFile? {
        var directory = place.containingFile?.originalFile?.virtualFile?.parent ?: return null
        repeat((relativeLevel - 1).coerceAtLeast(0)) {
            directory = directory.parent ?: return null
        }
        return directory
    }

    private fun resolveFromDirectory(
        project: Project,
        root: VirtualFile,
        components: List<String>,
    ): List<PsiElement> {
        var directory = root
        for ((index, component) in components.withIndex()) {
            val childDirectory = directory.findChild(component)?.takeIf { it.isDirectory }
            if (index != components.lastIndex) {
                directory = childDirectory ?: return emptyList()
                continue
            }

            return buildList {
                directory.findChild("$component.mojo")
                    ?.takeIf { !it.isDirectory }
                    ?.let { toPsi(project, it) }
                    ?.let(::add)

                childDirectory?.let { packageDirectory ->
                    val initializer = packageDirectory.findChild(MojoFile.PACKAGE_INITIALIZER)
                    val target = initializer?.takeIf { !it.isDirectory } ?: packageDirectory
                    toPsi(project, target)?.let(::add)
                }
            }
        }
        return emptyList()
    }

    private fun moduleDirectory(element: PsiElement): VirtualFile? = when (element) {
        is PsiDirectory -> element.virtualFile
        is MojoFile -> element.virtualFile?.takeIf { it.name == MojoFile.PACKAGE_INITIALIZER }?.parent
        else -> null
    }

    private fun toPsi(project: Project, file: VirtualFile): PsiElement? =
        if (file.isDirectory) {
            PsiManager.getInstance(project).findDirectory(file)
        } else {
            PsiManager.getInstance(project).findFile(file)
        }

    private fun elementPath(element: PsiElement): String = when (element) {
        is PsiDirectory -> element.virtualFile.path
        is PsiFile -> element.virtualFile?.path ?: element.name
        else -> "${element.containingFile?.virtualFile?.path}:${element.textOffset}"
    }
}
