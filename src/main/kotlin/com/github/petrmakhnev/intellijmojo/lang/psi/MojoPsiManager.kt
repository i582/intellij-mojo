package com.github.petrmakhnev.intellijmojo.lang.psi

import com.github.petrmakhnev.intellijmojo.ide.settings.mojoSettings
import com.github.petrmakhnev.intellijmojo.lang.MojoFileType
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootEvent
import com.intellij.openapi.roots.ModuleRootListener
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.util.SimpleModificationTracker
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiTreeChangeEvent
import com.intellij.psi.PsiTreeChangeListener
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.impl.PsiTreeChangeEventImpl
import java.util.WeakHashMap

class MojoPsiManager(private val project: Project) : Disposable {
    private val structureTracker = SimpleModificationTracker()
    private val functionTrackers = WeakHashMap<MojoFunctionDeclaration, MojoFunctionModificationTracker>()
    private val cacheInvalidator = MojoCacheInvalidator()

    val structureModificationTracker: ModificationTracker
        get() = structureTracker

    init {
        PsiManager.getInstance(project).addPsiTreeChangeListener(cacheInvalidator, this)
        project.messageBus.connect(this).subscribe(
            ModuleRootListener.TOPIC,
            object : ModuleRootListener {
                override fun rootsChanged(event: ModuleRootEvent) {
                    incStructureModificationCount()
                }
            },
        )
    }

    fun functionModificationTracker(function: MojoFunctionDeclaration): ModificationTracker =
        synchronized(functionTrackers) {
            functionTrackers.getOrPut(function) { MojoFunctionModificationTracker() }
        }

    fun inferenceDependencies(owner: PsiElement): Array<Any> {
        val dependencies = mutableListOf<Any>(structureTracker)
        var current: PsiElement? = owner
        while (current != null && current !is PsiFile) {
            if (current is MojoFunctionDeclaration) {
                dependencies += functionModificationTracker(current)
            }
            current = current.parent
        }
        return dependencies.toTypedArray()
    }

    fun incStructureModificationCount() {
        structureTracker.incModificationCount()
    }

    override fun dispose() {
        synchronized(functionTrackers) {
            functionTrackers.clear()
        }
    }

    private fun markChanged(file: PsiFile?, element: PsiElement?) {
        if (element == null) return

        if (file == null) {
            if (element is MojoFile || element is PsiDirectory) {
                incStructureModificationCount()
            }
            return
        }
        if (file.fileType != MojoFileType || element is PsiComment || element is PsiWhiteSpace) return

        val function = element.findContainingMojoFunction()
        if (function != null && function.containsBodyElement(element)) {
            synchronized(functionTrackers) {
                functionTrackers.getOrPut(function) { MojoFunctionModificationTracker() }.markChanged()
            }
        } else {
            incStructureModificationCount()
        }
    }

    private inner class MojoCacheInvalidator : PsiTreeChangeListener {
        override fun beforeChildAddition(event: PsiTreeChangeEvent) = Unit

        override fun beforeChildRemoval(event: PsiTreeChangeEvent) {
            markChanged(event.file, event.child)
        }

        override fun beforeChildReplacement(event: PsiTreeChangeEvent) {
            markChanged(event.file, event.oldChild)
        }

        override fun beforeChildMovement(event: PsiTreeChangeEvent) = Unit

        override fun beforeChildrenChange(event: PsiTreeChangeEvent) = Unit

        override fun beforePropertyChange(event: PsiTreeChangeEvent) = Unit

        override fun childAdded(event: PsiTreeChangeEvent) {
            markChanged(event.file, event.child)
        }

        override fun childRemoved(event: PsiTreeChangeEvent) = Unit

        override fun childReplaced(event: PsiTreeChangeEvent) = Unit

        override fun childrenChanged(event: PsiTreeChangeEvent) {
            if ((event as? PsiTreeChangeEventImpl)?.isGenericChange == true) return
            markChanged(event.file, event.parent)
        }

        override fun childMoved(event: PsiTreeChangeEvent) {
            markChanged(event.file, event.child)
        }

        override fun propertyChanged(event: PsiTreeChangeEvent) {
            markChanged(event.file, event.element)
        }
    }
}

val Project.mojoPsiManager: MojoPsiManager
    get() = service()

fun PsiElement.mojoInferenceCacheDependencies(): Array<Any> =
    project.mojoPsiManager.inferenceDependencies(this) + project.mojoSettings.modificationTracker
