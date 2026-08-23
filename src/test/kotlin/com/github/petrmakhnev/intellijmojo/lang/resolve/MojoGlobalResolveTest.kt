package com.github.petrmakhnev.intellijmojo.lang.resolve

import com.github.petrmakhnev.intellijmojo.lang.psi.MojoComptimeStatement
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoDottedName
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoFile
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoFunctionDeclaration
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoMemberExpression
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoNameIdentifier
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoReferenceExpression
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoStructDeclaration
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.PsiReference
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class MojoGlobalResolveTest : BasePlatformTestCase() {
    fun testResolvesGlobalFunctionFromExplicitImport() {
        myFixture.addFileToProject("library.mojo", "def helper():\n    pass\n")
        val file = myFixture.configureByText("main.mojo", "from library import helper\nhelper()\n")

        val resolved = expression(file, "helper").reference!!.resolve()

        assertInstanceOf(resolved, MojoFunctionDeclaration::class.java)
        assertEquals("library.mojo", resolved!!.containingFile.name)
    }

    fun testResolvesRenamedExplicitImport() {
        myFixture.addFileToProject("library.mojo", "def helper():\n    pass\n")
        val file = myFixture.configureByText("main.mojo", "from library import helper as run\nrun()\n")

        val resolved = expression(file, "run").reference!!.resolve()

        assertEquals("helper", (resolved as MojoFunctionDeclaration).name)
    }

    fun testModuleImportBuildsDottedBindingChain() {
        myFixture.addFileToProject("pkg/__init__.mojo", "")
        myFixture.addFileToProject("pkg/tools.mojo", "def helper():\n    pass\n")
        val file = myFixture.configureByText("main.mojo", "import pkg.tools\npkg.tools.helper()\n")

        assertEquals("__init__.mojo", (expression(file, "pkg").reference!!.resolve() as MojoFile).name)
        assertEquals("tools.mojo", (member(file, "pkg.tools").reference!!.resolve() as MojoFile).name)
        assertEquals("helper", (member(file, "pkg.tools.helper").reference!!.resolve() as MojoFunctionDeclaration).name)
    }

    fun testModuleAliasBindsLeafModule() {
        myFixture.addFileToProject("pkg/__init__.mojo", "")
        myFixture.addFileToProject("pkg/tools.mojo", "def helper():\n    pass\n")
        val file = myFixture.configureByText("main.mojo", "import pkg.tools as tools\ntools.helper()\n")

        assertEquals("tools.mojo", (expression(file, "tools").reference!!.resolve() as MojoFile).name)
        assertEquals("helper", (member(file, "tools.helper").reference!!.resolve() as MojoFunctionDeclaration).name)
    }

    fun testResolvesPackageReexport() {
        myFixture.addFileToProject("pkg/tools.mojo", "def helper():\n    pass\n")
        myFixture.addFileToProject("pkg/__init__.mojo", "from .tools import helper\n")
        val file = myFixture.configureByText("main.mojo", "from pkg import helper\nhelper()\n")

        val resolved = expression(file, "helper").reference!!.resolve()

        assertEquals("tools.mojo", resolved!!.containingFile.name)
    }

    fun testWildcardUsesLatestImportThatProvidesName() {
        myFixture.addFileToProject("first.mojo", "def value():\n    pass\n")
        myFixture.addFileToProject("second.mojo", "def value():\n    pass\n")
        val file = myFixture.configureByText(
            "main.mojo",
            "from first import *\nfrom second import *\nvalue()\n",
        )

        val resolved = expression(file, "value").reference!!.resolve()

        assertEquals("second.mojo", resolved!!.containingFile.name)
    }

    fun testDirectImportWinsOverWildcard() {
        myFixture.addFileToProject("first.mojo", "def value():\n    pass\n")
        myFixture.addFileToProject("second.mojo", "def value():\n    pass\n")
        val file = myFixture.configureByText(
            "main.mojo",
            "from first import *\nfrom second import value\nvalue()\n",
        )

        val resolved = expression(file, "value").reference!!.resolve()

        assertEquals("second.mojo", resolved!!.containingFile.name)
    }

    fun testWildcardDoesNotExposePrivateName() {
        myFixture.addFileToProject("library.mojo", "def _hidden():\n    pass\n")
        val file = myFixture.configureByText("main.mojo", "from library import *\n_hidden()\n")

        assertNull(expression(file, "_hidden").reference!!.resolve())
    }

    fun testCyclicReexportsTerminate() {
        myFixture.addFileToProject("a.mojo", "from b import missing\n")
        myFixture.addFileToProject("b.mojo", "from a import missing\n")
        val file = myFixture.configureByText("main.mojo", "from a import missing\nmissing()\n")

        assertNull(expression(file, "missing").reference!!.resolve())
    }

    fun testResolvesImportedComptimeSymbol() {
        myFixture.addFileToProject("constants.mojo", "comptime ANSWER = 42\n")
        val file = myFixture.configureByText("main.mojo", "from constants import ANSWER\nprint(ANSWER)\n")

        val resolved = expression(file, "ANSWER").reference!!.resolve()

        assertInstanceOf(resolved, MojoComptimeStatement::class.java)
        assertEquals("ANSWER", (resolved as MojoComptimeStatement).name)
    }

    fun testResolvesImportedTypeInTypeExpression() {
        myFixture.addFileToProject("models.mojo", "struct User:\n    pass\n")
        val file = myFixture.configureByText("main.mojo", "from models import User\ndef load(value: User):\n    pass\n")
        val typeName = PsiTreeUtil.findChildrenOfType(file, MojoNameIdentifier::class.java)
            .single { it.text == "User" && it.references.isNotEmpty() }

        val resolved = typeName.reference!!.resolve()

        assertInstanceOf(resolved, MojoStructDeclaration::class.java)
        assertEquals("models.mojo", resolved!!.containingFile.name)
    }

    fun testImportedOverloadsProduceMultipleResolveResults() {
        myFixture.addFileToProject(
            "overloads.mojo",
            "def convert(value: Int):\n    pass\ndef convert(value: String):\n    pass\n",
        )
        val file = myFixture.configureByText("main.mojo", "from overloads import convert\nconvert(1)\n")
        val reference = expression(file, "convert").reference as PsiPolyVariantReference

        assertSize(2, reference.multiResolve(false))
    }

    fun testClassCallResolvesExplicitConstructor() {
        val file = myFixture.configureByText(
            "main.mojo",
            """
            class Widget:
                def __init__(out self):
                    pass

            Widget()
            """.trimIndent(),
        )

        val resolved = expression(file, "Widget").reference!!.resolve()

        assertEquals("__init__", (resolved as MojoFunctionDeclaration).name)
    }

    fun testParametricClassCallResolvesExplicitConstructor() {
        val file = myFixture.configureByText(
            "main.mojo",
            """
            class Box[T]:
                def __init__(out self):
                    pass

            Box[Int]()
            """.trimIndent(),
        )

        val resolved = expression(file, "Box").reference!!.resolve()

        assertEquals("__init__", (resolved as MojoFunctionDeclaration).name)
    }

    fun testClassCallWithoutExplicitConstructorResolvesClass() {
        val file = myFixture.configureByText("main.mojo", "class Marker:\n    pass\nMarker()\n")

        val resolved = expression(file, "Marker").reference!!.resolve()

        assertInstanceOf(resolved, MojoStructDeclaration::class.java)
    }

    fun testImportedClassCallsResolveConstructor() {
        myFixture.addFileToProject(
            "library.mojo",
            "class Widget:\n    def __init__(out self):\n        pass\n",
        )
        val file = myFixture.configureByText(
            "main.mojo",
            "import library\nfrom library import Widget\nWidget()\nlibrary.Widget()\n",
        )

        val direct = expression(file, "Widget").reference!!.resolve()
        val qualified = member(file, "library.Widget").reference!!.resolve()

        assertEquals("__init__", (direct as MojoFunctionDeclaration).name)
        assertEquals("__init__", (qualified as MojoFunctionDeclaration).name)
    }

    private fun expression(file: com.intellij.psi.PsiFile, text: String): MojoReferenceExpression =
        PsiTreeUtil.findChildrenOfType(file, MojoReferenceExpression::class.java).single { it.text == text }

    private fun member(file: com.intellij.psi.PsiFile, text: String): MojoMemberExpression =
        PsiTreeUtil.findChildrenOfType(file, MojoMemberExpression::class.java).single { it.text == text }
}
