package com.github.petrmakhnev.intellijmojo.lang.ide

import com.github.petrmakhnev.intellijmojo.lang.MojoFileType
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoComptimeStatement
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoAssignmentStatement
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoFunctionDeclaration
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoParameterDeclaration
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoStructDeclaration
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoTraitDeclaration
import com.github.petrmakhnev.intellijmojo.lang.resolve.bindingIdentifiers
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class MojoFindUsagesTest : BasePlatformTestCase() {
    fun testFindsGlobalUsagesAcrossImports() {
        val library = myFixture.addFileToProject(
            "library.mojo",
            """
            def `fn`():
                pass

            def caller():
                `fn`()
            """.trimIndent(),
        )
        myFixture.configureByText(
            MojoFileType,
            """
            import library
            from library import `fn`

            `fn`()
            library.`fn`()
            """.trimIndent(),
        )
        PsiDocumentManager.getInstance(project).commitAllDocuments()

        val target = PsiTreeUtil.findChildrenOfType(library, MojoFunctionDeclaration::class.java)
            .single { it.name == "fn" }
        val usages = myFixture.findUsages(target)

        assertEquals(4, usages.size)
    }

    fun testClassUsagesIncludeConstructorCalls() {
        val library = myFixture.addFileToProject(
            "library.mojo",
            "class Widget:\n    def __init__(out self):\n        pass\n",
        )
        myFixture.configureByText(
            MojoFileType,
            """
            import library
            from library import Widget

            Widget()
            library.Widget()
            """.trimIndent(),
        )
        PsiDocumentManager.getInstance(project).commitAllDocuments()

        val widget = PsiTreeUtil.findChildOfType(library, MojoStructDeclaration::class.java)!!
        val usages = myFixture.findUsages(widget)

        assertEquals(3, usages.size)
    }

    fun testReportsMojoSymbolKinds() {
        val file = myFixture.configureByText(
            MojoFileType,
            """
            def build():
                pass

            struct Item:
                fn method(self):
                    pass

            trait Printable:
                pass

            type Index = Int
            comptime LIMIT = 16
            """.trimIndent(),
        )
        val provider = MojoFindUsagesProvider()

        val functions = PsiTreeUtil.findChildrenOfType(file, MojoFunctionDeclaration::class.java)
            .associateBy { it.name }
        val function = functions.getValue("build")
        val method = functions.getValue("method")
        val parameter = PsiTreeUtil.findChildOfType(file, MojoParameterDeclaration::class.java)!!
        val struct = PsiTreeUtil.findChildOfType(file, MojoStructDeclaration::class.java)!!
        val trait = PsiTreeUtil.findChildOfType(file, MojoTraitDeclaration::class.java)!!
        val comptime = PsiTreeUtil.findChildrenOfType(file, MojoComptimeStatement::class.java)
            .associateBy { it.name }

        assertTrue(provider.canFindUsagesFor(function))
        assertTrue(provider.canFindUsagesFor(method))
        assertTrue(provider.canFindUsagesFor(parameter.nameIdentifier!!))
        assertEquals("Function", provider.getType(function))
        assertEquals("Struct", provider.getType(struct))
        assertEquals("Trait", provider.getType(trait))
        assertEquals("Type alias", provider.getType(comptime.getValue("Index")))
        assertEquals("Constant", provider.getType(comptime.getValue("LIMIT")))
        assertEquals("build", provider.getDescriptiveName(function))
        assertEquals("build", provider.getNodeText(function, false))
    }

    fun testFindsParameterAndLocalVariableUsages() {
        val file = myFixture.configureByText(
            MojoFileType,
            """
            def main(input: Int):
                var value = input
                print(value)
                print(input, value)
            """.trimIndent(),
        )
        PsiDocumentManager.getInstance(project).commitAllDocuments()

        val parameter = PsiTreeUtil.findChildOfType(file, MojoParameterDeclaration::class.java)!!
        val local = PsiTreeUtil.findChildrenOfType(file, com.github.petrmakhnev.intellijmojo.lang.psi.MojoNameIdentifier::class.java)
            .single { identifier ->
                identifier.text == "value" &&
                    identifier.parent is com.github.petrmakhnev.intellijmojo.lang.psi.MojoPattern
            }

        assertEquals(2, myFixture.findUsages(parameter.nameIdentifier!!).size)
        assertEquals(2, myFixture.findUsages(local).size)
    }

    fun testFindsMethodUsagesThroughSelfAndInferredReceiver() {
        val file = myFixture.configureByText(
            MojoFileType,
            """
            struct Point:
                def reset(mut self):
                    pass

                def reset_twice(mut self):
                    self.reset()
                    self.reset()

            def main():
                var point = Point()
                point.reset()
            """.trimIndent(),
        )
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val method = PsiTreeUtil.findChildrenOfType(file, MojoFunctionDeclaration::class.java)
            .single { it.name == "reset" }

        assertEquals(3, myFixture.findUsages(method).size)
    }

    fun testFindsMethodUsagesAcrossImportedReceiverType() {
        val library = myFixture.addFileToProject(
            "geometry.mojo",
            """
            struct Point:
                def reset(mut self):
                    pass
            """.trimIndent(),
        )
        myFixture.configureByText(
            MojoFileType,
            """
            from geometry import Point

            def reset_point(point: Point):
                point.reset()

            def main():
                var point = Point()
                point.reset()
            """.trimIndent(),
        )
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val method = PsiTreeUtil.findChildrenOfType(library, MojoFunctionDeclaration::class.java)
            .single { it.name == "reset" }

        assertEquals(2, myFixture.findUsages(method).size)
    }

    fun testFindsFieldUsagesOnReadsAndAssignmentTargets() {
        val file = myFixture.configureByText(
            MojoFileType,
            """
            struct Point:
                var x: Int

                def update(mut self):
                    self.x = self.x + 1

            def main():
                var point = Point()
                point.x = point.x + 1
            """.trimIndent(),
        )
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val field = PsiTreeUtil.findChildrenOfType(file, MojoAssignmentStatement::class.java)
            .first()
            .bindingIdentifiers()
            .single()

        assertTrue(MojoFindUsagesProvider().canFindUsagesFor(field))
        assertEquals(4, myFixture.findUsages(field).size)
    }
}
