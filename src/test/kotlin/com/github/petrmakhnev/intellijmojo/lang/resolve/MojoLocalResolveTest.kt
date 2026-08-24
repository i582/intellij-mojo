package com.github.petrmakhnev.intellijmojo.lang.resolve

import com.github.petrmakhnev.intellijmojo.lang.MojoFileType
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoAssignmentStatement
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoAssignmentExpression
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoAugmentedAssignmentStatement
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoComptimeStatement
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoDeclarationName
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoForInClause
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoForStatement
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoLambdaExpression
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoNameIdentifier
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoParameterDeclaration
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoReferenceExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class MojoLocalResolveTest : BasePlatformTestCase() {
    fun testParameterReferenceResolvesToParameter() {
        val file = myFixture.configureByText(
            MojoFileType,
            "def identity(value: Int) -> Int:\n    return value\n",
        )
        val parameter = PsiTreeUtil.findChildOfType(file, MojoParameterDeclaration::class.java)!!

        assertSame(parameter.nameIdentifier, references(file, "value").single().reference!!.resolve())
    }

    fun testLocalVariableIsVisibleOnlyAfterInitializer() {
        val file = myFixture.configureByText(
            MojoFileType,
            "def main():\n    var value = value\n    print(value)\n",
        )
        val references = references(file, "value")
        val declaration = PsiTreeUtil.findChildOfType(file, MojoAssignmentStatement::class.java)!!
            .bindingIdentifiers()
            .single()

        assertNull(references[0].reference!!.resolve())
        assertSame(declaration, references[1].reference!!.resolve())
    }

    fun testInnerBlockShadowsParameterWithoutLeaking() {
        val file = myFixture.configureByText(
            MojoFileType,
            """
            def main(value: Int):
                var before = value
                if True:
                    var value = 2
                    print(value)
                print(value)
            """.trimIndent(),
        )
        val parameter = PsiTreeUtil.findChildOfType(file, MojoParameterDeclaration::class.java)!!
        val assignments = PsiTreeUtil.findChildrenOfType(file, MojoAssignmentStatement::class.java)
        val inner = assignments.single { it.bindingIdentifiers().single().text == "value" }
            .bindingIdentifiers()
            .single()
        val references = references(file, "value")

        assertSame(parameter.nameIdentifier, references[0].reference!!.resolve())
        assertSame(inner, references[1].reference!!.resolve())
        assertSame(parameter.nameIdentifier, references[2].reference!!.resolve())
    }

    fun testNestedFunctionCapturesOuterLocal() {
        val file = myFixture.configureByText(
            MojoFileType,
            """
            def outer():
                var answer = 42
                def inner() -> Int:
                    return answer
                print(answer)
            """.trimIndent(),
        )
        val declaration = PsiTreeUtil.findChildOfType(file, MojoAssignmentStatement::class.java)!!
            .bindingIdentifiers()
            .single()
        val references = references(file, "answer")

        assertSame(declaration, references[0].reference!!.resolve())
        assertSame(declaration, references[1].reference!!.resolve())
    }

    fun testLocalDeclarationWinsOverGlobalFunction() {
        val file = myFixture.configureByText(
            MojoFileType,
            """
            def value():
                pass

            def main():
                var value = 1
                print(value)
            """.trimIndent(),
        )
        val declaration = PsiTreeUtil.findChildrenOfType(file, MojoAssignmentStatement::class.java)
            .single()
            .bindingIdentifiers()
            .single()

        assertSame(declaration, references(file, "value").single().reference!!.resolve())
    }

    fun testTypeParameterResolvesInsideTypeExpression() {
        val file = myFixture.configureByText(
            MojoFileType,
            "def identity[T](value: T) -> T:\n    return value\n",
        )
        val parameters = PsiTreeUtil.findChildrenOfType(file, MojoParameterDeclaration::class.java)
        val typeParameter = parameters.single { it.nameIdentifier?.text == "T" }
        val typeReferences = PsiTreeUtil.findChildrenOfType(file, com.github.petrmakhnev.intellijmojo.lang.psi.MojoNameIdentifier::class.java)
            .filter { it.text == "T" && it.references.isNotEmpty() }

        assertSize(2, typeReferences)
        typeReferences.forEach { assertSame(typeParameter.nameIdentifier, it.reference!!.resolve()) }
    }

    fun testCaptureParameterShadowsCapturedOuterName() {
        val file = myFixture.configureByText(
            MojoFileType,
            """
            def outer():
                var value = 1
                def inner() {value}:
                    print(value)
            """.trimIndent(),
        )
        val parameters = PsiTreeUtil.findChildrenOfType(file, MojoParameterDeclaration::class.java)
        val capture = parameters.single { it.nameIdentifier?.text == "value" }

        assertSame(capture.nameIdentifier, references(file, "value").single().reference!!.resolve())
    }

    fun testEnclosingStructTypeParameterResolvesInsideMethodSignature() {
        val file = myFixture.configureByText(
            MojoFileType,
            """
            struct Box[T]:
                def identity(self, value: T) -> T:
                    return value
            """.trimIndent(),
        )
        val typeParameter = PsiTreeUtil.findChildrenOfType(file, MojoParameterDeclaration::class.java)
            .single { it.nameIdentifier?.text == "T" }
        val typeReferences = PsiTreeUtil.findChildrenOfType(file, MojoNameIdentifier::class.java)
            .filter { it.text == "T" && it.references.isNotEmpty() }

        assertSize(2, typeReferences)
        typeReferences.forEach { assertSame(typeParameter.nameIdentifier, it.reference!!.resolve()) }
    }

    fun testEnclosingTraitTypeParameterResolvesInsideMethodSignature() {
        val file = myFixture.configureByText(
            MojoFileType,
            """
            trait Producer[T]:
                def produce(self) -> T:
                    pass
            """.trimIndent(),
        )
        val typeParameter = PsiTreeUtil.findChildrenOfType(file, MojoParameterDeclaration::class.java)
            .single { it.nameIdentifier?.text == "T" }
        val typeReference = PsiTreeUtil.findChildrenOfType(file, MojoNameIdentifier::class.java)
            .single { it.text == "T" && it.references.isNotEmpty() }

        assertSame(typeParameter.nameIdentifier, typeReference.reference!!.resolve())
    }

    fun testMlirRegionParameterResolvesInRegionBody() {
        val file = myFixture.configureByText(
            MojoFileType,
            "__mlir_region lower(value: Int):\n    print(value)\n",
        )
        val parameter = PsiTreeUtil.findChildOfType(file, MojoParameterDeclaration::class.java)!!

        assertSame(parameter.nameIdentifier, references(file, "value").single().reference!!.resolve())
    }

    fun testLambdaParameterResolvesInLambdaBody() {
        val file = myFixture.configureByText(
            MojoFileType,
            "def main():\n    var identity = lambda(value): value\n",
        )
        val lambda = PsiTreeUtil.findChildOfType(file, MojoLambdaExpression::class.java)!!
        val parameter = lambda.callableParameters!!.parameterDeclarationList.single().nameIdentifier!!

        assertSame(parameter, references(file, "value").single().reference!!.resolve())
    }

    fun testForAndComprehensionBindingsHaveIndependentScopes() {
        val file = myFixture.configureByText(
            MojoFileType,
            """
            def main(values: List[Int]):
                for item in values:
                    print(item)
                var mapped = [item for item in values if item]
            """.trimIndent(),
        )
        val loopBinding = PsiTreeUtil.findChildOfType(file, MojoForStatement::class.java)!!
            .patternList!!.bindingIdentifiers().single()
        val comprehensionBinding = PsiTreeUtil.findChildOfType(file, MojoForInClause::class.java)!!
            .patternList!!.bindingIdentifiers().single()
        val itemReferences = references(file, "item")

        assertSame(loopBinding, itemReferences[0].reference!!.resolve())
        assertSame(comprehensionBinding, itemReferences[1].reference!!.resolve())
        assertSame(comprehensionBinding, itemReferences[2].reference!!.resolve())
    }

    fun testWithExceptMatchAndWalrusBindingsResolveInTheirBodies() {
        val file = myFixture.configureByText(
            MojoFileType,
            """
            def main(manager, value):
                with manager as resource:
                    print(resource)
                try:
                    pass
                except Error as error:
                    print(error)
                match value:
                    case Some(item):
                        print(item)
                if result := value:
                    print(result)
            """.trimIndent(),
        )
        val declarations = (PsiTreeUtil.findChildrenOfType(file, MojoNameIdentifier::class.java)
            .filter { it.staticBindingKind() != null }
            .associateBy { it.text } + mapOf(
                "result" to PsiTreeUtil.findChildOfType(file, MojoAssignmentExpression::class.java)!!.nameIdentifier,
            ))

        for (name in listOf("resource", "error", "item", "result")) {
            val usage = references(file, name).last()
            assertSame("Failed to resolve $name", declarations.getValue(name), usage.reference!!.resolve())
        }
    }

    fun testTypeAnnotationAndMemberTargetAreNotLocalBindings() {
        val file = myFixture.configureByText(
            MojoFileType,
            """
            struct Thing:
                var field: Int

                def update(mut self, source: Thing):
                    var value: Thing = source
                    self.field = 1
                    print(self, value)
            """.trimIndent(),
        )
        val assignments = PsiTreeUtil.findChildrenOfType(file, MojoAssignmentStatement::class.java)
        val localBindings = assignments.flatMap { it.bindingIdentifiers() }.map { it.text }
        val selfParameter = PsiTreeUtil.findChildrenOfType(file, MojoParameterDeclaration::class.java)
            .single { it.nameIdentifier?.text == "self" }

        assertEquals(listOf("field", "value"), localBindings)
        references(file, "self").forEach { reference ->
            assertSame(selfParameter.nameIdentifier, reference.reference!!.resolve())
        }
    }

    fun testAugmentedAssignmentResolvesFunctionLocalInsideNestedLoops() {
        val file = myFixture.configureByText(
            MojoFileType,
            """
            def grid_str(rows: Int, cols: Int) -> String:
                var str = String()
                for row in range(rows):
                    for col in range(cols):
                        str += "*"
                return str
            """.trimIndent(),
        )
        val declaration = PsiTreeUtil.findChildOfType(file, MojoAssignmentStatement::class.java)!!
            .bindingIdentifiers().single()
        val augmented = PsiTreeUtil.findChildOfType(file, MojoAugmentedAssignmentStatement::class.java)!!
            .patternList!!.bindingIdentifiers().single()

        assertSame(declaration, augmented.reference!!.resolve())
        assertSame(declaration, references(file, "str").single().reference!!.resolve())
    }

    fun testLocalComptimeValuesAndTypesResolveAfterDeclaration() {
        val file = myFixture.configureByText(
            MojoFileType,
            """
            struct Progress:
                def advance(mut self, steps: Int = 1):
                    comptime BLOCK = "▇"
                    comptime PLACE_HOLDER = " "
                    comptime Element = Int

                    if steps <= 0:
                        return
                    for _ in range(steps):
                        pass

                    var value: Element = 1
                    print(BLOCK * value, end="")
                    print(PLACE_HOLDER * value, end="")
            """.trimIndent(),
        )
        val declarations = PsiTreeUtil.findChildrenOfType(file, MojoComptimeStatement::class.java)
            .associateBy { it.name }

        assertSame(declarations.getValue("BLOCK"), references(file, "BLOCK").single().reference!!.resolve())
        assertSame(
            declarations.getValue("PLACE_HOLDER"),
            references(file, "PLACE_HOLDER").single().reference!!.resolve(),
        )
        val typeReference = PsiTreeUtil.findChildrenOfType(file, MojoNameIdentifier::class.java)
            .single { it.text == "Element" && it.parent !is MojoDeclarationName }
        assertSame(declarations.getValue("Element"), typeReference.reference!!.resolve())
    }

    private fun references(file: com.intellij.psi.PsiFile, name: String): List<MojoReferenceExpression> =
        PsiTreeUtil.findChildrenOfType(file, MojoReferenceExpression::class.java)
            .filter { it.nameIdentifier.text.removeSurrounding("`") == name }
            .sortedBy { it.textOffset }
}
