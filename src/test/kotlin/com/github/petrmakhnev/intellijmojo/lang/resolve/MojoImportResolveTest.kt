package com.github.petrmakhnev.intellijmojo.lang.resolve

import com.github.petrmakhnev.intellijmojo.lang.psi.MojoDottedName
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoFile
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoStructDeclaration
import com.intellij.psi.PsiDirectory
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class MojoImportResolveTest : BasePlatformTestCase() {
    fun testResolvesEveryModulePathSegment() {
        myFixture.addFileToProject("pkg/__init__.mojo", "")
        myFixture.addFileToProject("pkg/math.mojo", "def add():\n    pass\n")
        val file = myFixture.configureByText("main.mojo", "import pkg.math\n")

        val path = PsiTreeUtil.findChildOfType(file, MojoDottedName::class.java)!!
        val references = path.references

        assertSize(2, references)
        assertEquals("__init__.mojo", (references[0].resolve() as MojoFile).name)
        assertEquals("math.mojo", (references[1].resolve() as MojoFile).name)
    }

    fun testResolvesNamespaceDirectory() {
        myFixture.addFileToProject("vendor/tools.mojo", "")
        val file = myFixture.configureByText("main.mojo", "import vendor.tools\n")

        val path = PsiTreeUtil.findChildOfType(file, MojoDottedName::class.java)!!

        assertEquals("vendor", (path.references[0].resolve() as PsiDirectory).name)
        assertEquals("tools.mojo", (path.references[1].resolve() as MojoFile).name)
    }

    fun testResolvesSelectiveImportDeclaration() {
        myFixture.addFileToProject("pkg/__init__.mojo", "")
        myFixture.addFileToProject("pkg/models.mojo", "struct User:\n    pass\n")
        val file = myFixture.configureByText("main.mojo", "from pkg.models import User\n")
        val paths = PsiTreeUtil.findChildrenOfType(file, MojoDottedName::class.java).toList()

        val imported = paths.single { it.text == "User" }.reference!!.resolve()

        assertInstanceOf(imported, MojoStructDeclaration::class.java)
        assertEquals("User", (imported as MojoStructDeclaration).name)
    }

    fun testResolvesImportedSubmodule() {
        myFixture.addFileToProject("pkg/__init__.mojo", "")
        myFixture.addFileToProject("pkg/tools.mojo", "")
        val file = myFixture.configureByText("main.mojo", "from pkg import tools\n")
        val paths = PsiTreeUtil.findChildrenOfType(file, MojoDottedName::class.java).toList()

        val imported = paths.single { it.text == "tools" }.reference!!.resolve()

        assertEquals("tools.mojo", (imported as MojoFile).name)
    }

    fun testResolvesRelativeImport() {
        myFixture.addFileToProject("pkg/__init__.mojo", "")
        myFixture.addFileToProject("pkg/tools.mojo", "")
        myFixture.addFileToProject("pkg/main.mojo", "from . import tools\n")
        val file = myFixture.configureFromTempProjectFile("pkg/main.mojo")
        val imported = PsiTreeUtil.findChildOfType(file, MojoDottedName::class.java)!!.reference!!.resolve()

        assertEquals("tools.mojo", (imported as MojoFile).name)
    }
}
