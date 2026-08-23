package com.github.petrmakhnev.intellijmojo.lang.stub

import com.github.petrmakhnev.intellijmojo.lang.stub.index.MojoFunctionIndex
import com.github.petrmakhnev.intellijmojo.lang.stub.index.MojoModuleIndex
import com.github.petrmakhnev.intellijmojo.lang.stub.index.MojoNamedElementIndex
import com.github.petrmakhnev.intellijmojo.lang.stub.index.MojoStructIndex
import com.github.petrmakhnev.intellijmojo.lang.stub.index.MojoTraitIndex
import com.github.petrmakhnev.intellijmojo.lang.stub.index.MojoTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class MojoStubIndexTest : BasePlatformTestCase() {
    fun testIndexesNamedDeclarationsByKind() {
        myFixture.addFileToProject(
            "symbols.mojo",
            """
            def render():
                pass

            struct Grid:
                def render(self):
                    pass

            trait Drawable:
                fn draw(self):
                    pass

            comptime MAX_SIZE = 1024

            __mlir_region lower():
                pass
            """.trimIndent(),
        )

        val scope = GlobalSearchScope.projectScope(project)

        assertEquals(2, MojoFunctionIndex.find("render", project, scope).size)
        assertEquals(listOf("Grid"), MojoStructIndex.find("Grid", project, scope).mapNotNull { it.name })
        assertEquals(listOf("Drawable"), MojoTraitIndex.find("Drawable", project, scope).mapNotNull { it.name })
        assertEquals(
            setOf("Grid", "Drawable"),
            listOf("Grid", "Drawable").flatMap { MojoTypeIndex.find(it, project, scope) }.mapNotNull { it.name }.toSet(),
        )
        assertEquals(listOf("lower"), MojoNamedElementIndex.find("lower", project, scope).mapNotNull { it.name })
        assertEquals(listOf("MAX_SIZE"), MojoNamedElementIndex.find("MAX_SIZE", project, scope).mapNotNull { it.name })
    }

    fun testIndexKeepsOverloadsFromDifferentFiles() {
        myFixture.addFileToProject("first.mojo", "def map(value: Int):\n    pass\n")
        myFixture.addFileToProject("second.mojo", "def map(value: String):\n    pass\n")

        val functions = MojoFunctionIndex.find("map", project, GlobalSearchScope.projectScope(project))

        assertEquals(setOf("first.mojo", "second.mojo"), functions.map { it.containingFile.name }.toSet())
    }

    fun testIndexesFilesAndPackageInitializersAsModules() {
        myFixture.addFileToProject("math.mojo", "def add():\n    pass\n")
        myFixture.addFileToProject("collections/__init__.mojo", "struct List:\n    pass\n")

        val scope = GlobalSearchScope.projectScope(project)

        assertEquals(listOf("math.mojo"), MojoModuleIndex.find("math", project, scope).map { it.name })
        assertEquals(
            listOf("__init__.mojo"),
            MojoModuleIndex.find("collections", project, scope).map { it.name },
        )
    }
}
