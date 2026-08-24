package com.github.petrmakhnev.intellijmojo.lang.ide

import com.github.petrmakhnev.intellijmojo.lang.MojoFileType
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoCallExpression
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

    fun testFindsDirectConstructorCallButNotTypeReference() {
        val file = myFixture.configureByText(
            MojoFileType,
            """
            struct Widget:
                def __init__(out self):
                    pass

            def make() -> Widget:
                return Widget()
            """.trimIndent(),
        )
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val constructor = PsiTreeUtil.findChildrenOfType(file, MojoFunctionDeclaration::class.java)
            .single { it.name == "__init__" }

        assertEquals(1, myFixture.findUsages(constructor).size)
    }

    fun testFindsImportedConstructorCalls() {
        val library = myFixture.addFileToProject(
            "library.mojo",
            """
            struct Widget:
                def __init__(out self):
                    pass
            """.trimIndent(),
        )
        myFixture.configureByText(
            MojoFileType,
            """
            import library
            from library import Widget

            def direct() -> Widget:
                return Widget()

            def qualified():
                library.Widget()
            """.trimIndent(),
        )
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val constructor = PsiTreeUtil.findChildrenOfType(library, MojoFunctionDeclaration::class.java)
            .single { it.name == "__init__" }

        assertEquals(2, myFixture.findUsages(constructor).size)
    }

    fun testFindsParametricConstructorCallButNotParametricTypeReference() {
        val file = myFixture.configureByText(
            MojoFileType,
            """
            struct Box[T]:
                def __init__(out self):
                    pass

            def make() -> Box[Int]:
                return Box[Int]()
            """.trimIndent(),
        )
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val constructor = PsiTreeUtil.findChildrenOfType(file, MojoFunctionDeclaration::class.java)
            .single { it.name == "__init__" }

        assertEquals(1, myFixture.findUsages(constructor).size)
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

            comptime Index = Int
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

    fun testNestedLocalFunctionCallResolvesAndFindsUsage() {
        val file = myFixture.configureByText(
            MojoFileType,
            """
            struct Vector[length: Int]:
                def join(self, other: Self) -> Self:
                    def indices() -> StaticTuple[SIMDLength, 2 * Int(Self.length)]:
                        var res = StaticTuple[SIMDLength, 2 * Int(Self.length)](0)
                        for i in range(len(res)):
                            res[i] = i
                        return res

                    return self._shuffle_list[2 * Self.length, indices()](other)
            """.trimIndent(),
        )
        PsiDocumentManager.getInstance(project).commitAllDocuments()

        val declaration = PsiTreeUtil.findChildrenOfType(file, MojoFunctionDeclaration::class.java)
            .single { it.name == "indices" }
        val call = PsiTreeUtil.findChildrenOfType(file, MojoCallExpression::class.java)
            .single { it.expression.text == "indices" }

        assertSame(declaration, call.expression.reference!!.resolve())
        assertEquals(1, myFixture.findUsages(declaration).size)
        assertEquals(1, myFixture.findUsages(declaration.nameIdentifier!!).size)
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

    fun testFindsStructParameterAndAssociatedComptimeTypeUsages() {
        val file = myFixture.configureByText(
            MojoFileType,
            """
            struct StridedRange[dtype: DType = DType.int]:
                comptime Element = Scalar[Self.dtype]
                var element: Self.Element

                def inspect(self):
                    var kind = Self.dtype
            """.trimIndent(),
        )
        PsiDocumentManager.getInstance(project).commitAllDocuments()

        val dtype = PsiTreeUtil.findChildrenOfType(file, MojoParameterDeclaration::class.java)
            .single { it.nameIdentifier?.text == "dtype" }
            .nameIdentifier!!
        val element = PsiTreeUtil.findChildrenOfType(file, MojoComptimeStatement::class.java)
            .single { it.name == "Element" }

        assertEquals(2, myFixture.findUsages(dtype).size)
        assertEquals(1, myFixture.findUsages(element).size)
    }

    fun testAssociatedComptimeTypeIsFindUsagesTargetAtDeclaration() {
        myFixture.configureByText(
            MojoFileType,
            """
            struct StridedRange:
                comptime <caret>ReversedType = StridedRange

                def reversed(self) -> Self.ReversedType:
                    return Self.ReversedType()
            """.trimIndent(),
        )
        PsiDocumentManager.getInstance(project).commitAllDocuments()

        val target = myFixture.elementAtCaret

        assertInstanceOf(target, com.github.petrmakhnev.intellijmojo.lang.psi.MojoNameIdentifier::class.java)
        assertTrue(MojoFindUsagesProvider().canFindUsagesFor(target))
        assertEquals("Type alias", MojoFindUsagesProvider().getType(target))
        assertEquals(2, myFixture.findUsages(target).size)
    }
}
