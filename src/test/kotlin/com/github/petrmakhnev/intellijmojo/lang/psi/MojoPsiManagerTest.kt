package com.github.petrmakhnev.intellijmojo.lang.psi

import com.github.petrmakhnev.intellijmojo.ide.settings.mojoSettings
import com.github.petrmakhnev.intellijmojo.lang.type.selfInferenceResult
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.ModificationTracker
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class MojoPsiManagerTest : BasePlatformTestCase() {
    fun testBodyChangeInvalidatesOnlyOwningFunction() {
        val file = myFixture.configureByText(
            "main.mojo",
            """
            def first() -> Int:
                return 1

            def second() -> Int:
                return 2
            """.trimIndent(),
        )
        val functions = PsiTreeUtil.findChildrenOfType(file, MojoFunctionDeclaration::class.java)
            .sortedBy { it.textOffset }
        val manager = project.mojoPsiManager
        val firstTracker = manager.functionModificationTracker(functions[0])
        val secondTracker = manager.functionModificationTracker(functions[1])
        val structureBefore = manager.structureModificationTracker.modificationCount
        val firstBefore = firstTracker.modificationCount
        val secondBefore = secondTracker.modificationCount

        replaceText("return 1", "return 3")

        assertTrue(firstTracker.modificationCount > firstBefore)
        assertEquals(secondBefore, secondTracker.modificationCount)
        assertEquals(structureBefore, manager.structureModificationTracker.modificationCount)
    }

    fun testSignatureChangeInvalidatesProjectStructure() {
        myFixture.configureByText(
            "main.mojo",
            """
            def first() -> Int:
                return 1

            def second() -> Int:
                return 2
            """.trimIndent(),
        )
        val manager = project.mojoPsiManager
        val structureBefore = manager.structureModificationTracker.modificationCount

        replaceText("first() -> Int", "first() -> Float32")

        assertTrue(manager.structureModificationTracker.modificationCount > structureBefore)
    }

    fun testInferenceDependenciesContainStructureAndStableFunctionTracker() {
        val file = myFixture.configureByText(
            "main.mojo",
            """
            def compute() -> Int:
                return 1
            """.trimIndent(),
        )
        val function = PsiTreeUtil.findChildOfType(file, MojoFunctionDeclaration::class.java)!!
        val manager = project.mojoPsiManager

        val first = function.mojoInferenceCacheDependencies()
        val second = function.mojoInferenceCacheDependencies()

        assertEquals(3, first.size)
        assertSame(manager.structureModificationTracker, first[0])
        assertSame(first[1], second[1])
        assertSame(manager.functionModificationTracker(function), first[1])
        assertInstanceOf(first[1], ModificationTracker::class.java)
        assertSame(project.mojoSettings.modificationTracker, first[2])
        assertEquals(2, file.mojoInferenceCacheDependencies().size)
    }

    fun testWhitespaceChangeDoesNotInvalidateInference() {
        val file = myFixture.configureByText(
            "main.mojo",
            """
            def compute() -> Int:
                return 1
            """.trimIndent(),
        )
        val function = PsiTreeUtil.findChildOfType(file, MojoFunctionDeclaration::class.java)!!
        val manager = project.mojoPsiManager
        val functionTracker = manager.functionModificationTracker(function)
        val functionBefore = functionTracker.modificationCount
        val structureBefore = manager.structureModificationTracker.modificationCount

        replaceText("return 1", "return  1")

        assertEquals(functionBefore, functionTracker.modificationCount)
        assertEquals(structureBefore, manager.structureModificationTracker.modificationCount)
    }

    fun testBodyChangeKeepsSiblingInferenceCached() {
        var file = myFixture.configureByText(
            "main.mojo",
            """
            def first() -> Int:
                return 1

            def second() -> Int:
                return 2
            """.trimIndent(),
        )
        var functions = PsiTreeUtil.findChildrenOfType(file, MojoFunctionDeclaration::class.java)
            .associateBy { it.name }
        val firstBefore = functions.getValue("first").selfInferenceResult
        val secondBefore = functions.getValue("second").selfInferenceResult

        replaceText("return 1", "return 3")

        file = myFixture.file
        functions = PsiTreeUtil.findChildrenOfType(file, MojoFunctionDeclaration::class.java)
            .associateBy { it.name }
        assertNotSame(firstBefore, functions.getValue("first").selfInferenceResult)
        assertSame(secondBefore, functions.getValue("second").selfInferenceResult)
    }

    fun testNestedInferenceDependsOnOuterFunctionBody() {
        var file = myFixture.configureByText(
            "main.mojo",
            """
            def outer():
                var value = 1

                def inner():
                    return value

                return inner()
            """.trimIndent(),
        )
        var functions = PsiTreeUtil.findChildrenOfType(file, MojoFunctionDeclaration::class.java)
            .associateBy { it.name }
        val outer = functions.getValue("outer")
        val inner = functions.getValue("inner")
        val dependencies = inner.mojoInferenceCacheDependencies()
        val innerBefore = inner.selfInferenceResult

        assertEquals(4, dependencies.size)
        assertSame(project.mojoPsiManager.functionModificationTracker(inner), dependencies[1])
        assertSame(project.mojoPsiManager.functionModificationTracker(outer), dependencies[2])

        replaceText("value = 1", "value = 2")

        file = myFixture.file
        functions = PsiTreeUtil.findChildrenOfType(file, MojoFunctionDeclaration::class.java)
            .associateBy { it.name }
        assertNotSame(innerBefore, functions.getValue("inner").selfInferenceResult)
    }

    private fun replaceText(oldText: String, newText: String) {
        val document = myFixture.editor.document
        val offset = document.text.indexOf(oldText)
        assertTrue("Expected text '$oldText' in fixture", offset >= 0)
        WriteCommandAction.runWriteCommandAction(project) {
            document.replaceString(offset, offset + oldText.length, newText)
        }
        PsiDocumentManager.getInstance(project).commitDocument(document)
    }
}
