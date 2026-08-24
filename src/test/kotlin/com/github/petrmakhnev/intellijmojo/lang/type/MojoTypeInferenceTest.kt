package com.github.petrmakhnev.intellijmojo.lang.type

import com.github.petrmakhnev.intellijmojo.lang.MojoFileType
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoMemberExpression
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoLiteralExpression
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoNameIdentifier
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoAssignmentStatement
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoComptimeStatement
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoDeclarationName
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoFunctionDeclaration
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoParameterDeclaration
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoReferenceExpression
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoStructDeclaration
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoTypeParameterList
import com.github.petrmakhnev.intellijmojo.lang.resolve.bindingIdentifiers
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class MojoTypeInferenceTest : BasePlatformTestCase() {
    fun testParameterCarriesDeclaredType() {
        val file = myFixture.configureByText(
            MojoFileType,
            "def identity(value: Int) -> Int:\n    return value\n",
        )

        assertEquals("Int", reference(file, "value").inferredType?.displayName)
    }

    fun testConstructorDeterminesLocalVariableType() {
        val file = myFixture.configureByText(
            MojoFileType,
            """
            struct Point:
                pass

            def make():
                var point = Point()
                print(point)
            """.trimIndent(),
        )
        val pointReference = reference(file, "point")
        val type = pointReference.inferredType

        assertInstanceOf(type, MojoTy.Struct::class.java)
        assertEquals("Point", type?.displayName)
    }

    fun testFunctionReturnTypeFlowsIntoAssignment() {
        val file = myFixture.configureByText(
            MojoFileType,
            """
            def make_value() -> String:
                return String()

            def main():
                var value = make_value()
                print(value)
            """.trimIndent(),
        )

        assertEquals("String", reference(file, "value").inferredType?.displayName)
    }

    fun testGenericReturnTypeIsDeducedFromArgument() {
        val file = myFixture.configureByText(
            MojoFileType,
            """
            def identity[T](value: T) -> T:
                return value

            def main():
                var value = identity(1)
                print(value)
            """.trimIndent(),
        )

        assertEquals("Int", reference(file, "value").inferredType?.displayName)
    }

    fun testMemberMethodUsesReceiverType() {
        val file = myFixture.configureByText(
            MojoFileType,
            """
            struct Point:
                var x: Int

                def get(self) -> Int:
                    return self.x

            def main():
                var point = Point()
                var value = point.get()
                print(value)
            """.trimIndent(),
        )
        val member = PsiTreeUtil.findChildrenOfType(file, MojoMemberExpression::class.java)
            .single { it.nameIdentifier.text == "get" }
        val point = PsiTreeUtil.findChildOfType(file, MojoStructDeclaration::class.java)!!

        assertEquals("Int", reference(file, "value").inferredType?.displayName)
        assertEquals("get", member.reference!!.resolve()?.let { (it as com.github.petrmakhnev.intellijmojo.lang.psi.MojoFunctionDeclaration).name })
        assertEquals(point, (reference(file, "point").inferredType as MojoTy.Struct).declaration)
    }

    fun testImplicitSelfCarriesEnclosingStructType() {
        val file = myFixture.configureByText(
            MojoFileType,
            """
            struct Point:
                def move(mut self):
                    print(self)
            """.trimIndent(),
        )
        val struct = PsiTreeUtil.findChildOfType(file, MojoStructDeclaration::class.java)!!
        val parameter = PsiTreeUtil.findChildrenOfType(file, MojoParameterDeclaration::class.java)
            .single { it.nameIdentifier?.text == "self" }
        val self = reference(file, "self")

        assertSame(parameter.nameIdentifier, self.reference!!.resolve())
        assertEquals(struct, (self.inferredType as MojoTy.Struct).declaration)
        assertEquals("Point", self.inferredType?.displayName)
    }

    fun testSelfAndSelfTypeParameterResolveToEnclosingStruct() {
        val file = myFixture.configureByText(
            MojoFileType,
            """
            struct Interval[T]:
                var start: Self.T

                def identity(self, value: Self.T) -> Self:
                    return value
            """.trimIndent(),
        )
        val struct = PsiTreeUtil.findChildOfType(file, MojoStructDeclaration::class.java)!!
        val typeParameter = PsiTreeUtil.findChildrenOfType(file, MojoParameterDeclaration::class.java)
            .single { it.nameIdentifier?.text == "T" }
            .nameIdentifier!!

        val selfReferences = PsiTreeUtil.findChildrenOfType(file, MojoLiteralExpression::class.java)
            .filter { it.text == "Self" }
        assertEquals(3, selfReferences.size)
        selfReferences.forEach { assertSame(struct, it.reference!!.resolve()) }

        val typeParameterReferences = PsiTreeUtil.findChildrenOfType(file, MojoNameIdentifier::class.java)
            .filter { it.text == "T" && it != typeParameter }
        assertEquals(2, typeParameterReferences.size)
        typeParameterReferences.forEach { assertSame(typeParameter, it.reference!!.resolve()) }
        assertEquals("T", reference(file, "value").inferredType?.displayName)
    }

    fun testFieldsResolveOnReadAndAssignmentTargets() {
        val file = myFixture.configureByText(
            MojoFileType,
            """
            struct Point:
                var x: Int

                def set_x(mut self, value: Int):
                    self.x = value

                def get_x(self) -> Int:
                    return self.x

            def main():
                var point = Point()
                point.x = 1
            """.trimIndent(),
        )
        val field = PsiTreeUtil.findChildrenOfType(file, MojoAssignmentStatement::class.java)
            .first()
            .bindingIdentifiers()
            .single()
        val fieldReferences = PsiTreeUtil.findChildrenOfType(file, MojoNameIdentifier::class.java)
            .filter { it.text == "x" && it != field }

        assertEquals(3, fieldReferences.size)
        fieldReferences.forEach { identifier ->
            val resolved = (identifier.parent as? MojoMemberExpression)?.reference?.resolve()
                ?: identifier.reference?.resolve()
            assertSame(field, resolved)
        }
        val read = PsiTreeUtil.findChildrenOfType(file, MojoMemberExpression::class.java)
            .single { it.nameIdentifier.text == "x" }
        assertEquals("Int", read.inferredType?.displayName)
    }

    fun testGenericFieldAssignmentResolvesOutSelfAndSelfType() {
        val file = myFixture.configureByText(
            MojoFileType,
            """
            struct Interval[T]:
                var start: Self.T

                def __init__(out self, *, copy: Self):
                    self.start = copy.start
            """.trimIndent(),
        )
        val parameters = PsiTreeUtil.findChildrenOfType(file, MojoParameterDeclaration::class.java)
        val selfParameter = parameters.single { it.nameIdentifier?.text == "self" }.nameIdentifier!!
        val field = PsiTreeUtil.findChildrenOfType(file, MojoAssignmentStatement::class.java)
            .first()
            .bindingIdentifiers()
            .single()
        val assignment = PsiTreeUtil.findChildrenOfType(file, MojoAssignmentStatement::class.java).last()
        val leftSelf = PsiTreeUtil.findChildrenOfType(assignment.patternList, MojoNameIdentifier::class.java)
            .first { it.text == "self" }
        val leftField = PsiTreeUtil.findChildrenOfType(assignment.patternList, MojoNameIdentifier::class.java)
            .first { it.text == "start" }
        val rightField = PsiTreeUtil.findChildrenOfType(assignment, MojoMemberExpression::class.java)
            .single { it.nameIdentifier.text == "start" }

        assertEquals("Interval", reference(file, "copy").inferredType?.displayName)
        assertSame(selfParameter, leftSelf.reference!!.resolve())
        assertSame(field, leftField.reference!!.resolve())
        assertSame(field, rightField.reference!!.resolve())
        assertEquals("T", rightField.inferredType?.displayName)
    }

    fun testFieldsResolveThroughSelfTypedParameter() {
        val file = myFixture.configureByText(
            MojoFileType,
            """
            struct Interval[T]:
                var start: Self.T

                def overlaps(self, other: Self) -> Bool:
                    return other.start < self.start
            """.trimIndent(),
        )
        val field = PsiTreeUtil.findChildOfType(file, MojoAssignmentStatement::class.java)!!
            .bindingIdentifiers()
            .single()
        val accesses = PsiTreeUtil.findChildrenOfType(file, MojoMemberExpression::class.java)
            .filter { it.nameIdentifier.text == "start" }

        assertEquals("Interval", reference(file, "other").inferredType?.displayName)
        assertEquals(2, accesses.size)
        accesses.forEach { access ->
            assertSame(field, access.reference!!.resolve())
            assertEquals("T", access.inferredType?.displayName)
        }
    }

    fun testComptimeBindingCanHoldAValueOrAType() {
        val file = myFixture.configureByText(
            MojoFileType,
            """
            def render():
                comptime BLOCK = "▇"
                comptime Element = Int
                var value: Element = 1
                print(BLOCK)
            """.trimIndent(),
        )
        val function = PsiTreeUtil.findChildOfType(
            file,
            MojoFunctionDeclaration::class.java,
        )!!
        val declarations = PsiTreeUtil.findChildrenOfType(file, MojoComptimeStatement::class.java)
            .associateBy { it.name }
        val blockType = function.selfInferenceResult.getType(declarations.getValue("BLOCK"))
        val elementType = function.selfInferenceResult.getType(declarations.getValue("Element"))
        val valueDeclaration = PsiTreeUtil.findChildOfType(file, MojoAssignmentStatement::class.java)!!
            .bindingIdentifiers()
            .single()

        assertEquals("StringLiteral", blockType?.displayName)
        assertEquals("type[Int]", elementType?.displayName)
        assertEquals("Int", function.selfInferenceResult.getType(valueDeclaration)?.displayName)
        assertEquals("StringLiteral", reference(file, "BLOCK").inferredType?.displayName)
    }

    fun testSelfMembersResolveGenericTypeAndValueParameters() {
        val file = myFixture.configureByText(
            MojoFileType,
            """
            struct StridedRange[
                T: AnyType,
                dtype: DType = DType.int,
                forward: Bool = True,
            ]:
                comptime Element = Scalar[Self.dtype]
                comptime ReversedType = StridedRange[Self.T, Self.dtype, forward=not Self.forward]
                var element: Self.Element

                def inspect(self):
                    comptime if Self.forward:
                        var kind = Self.dtype
            """.trimIndent(),
        )
        val parameters = PsiTreeUtil.findChildrenOfType(file, MojoParameterDeclaration::class.java)
            .filter {
                PsiTreeUtil.getParentOfType(
                    it,
                    MojoTypeParameterList::class.java,
                    false,
                ) != null
            }
            .associateBy { it.nameIdentifier!!.text }
        val members = PsiTreeUtil.findChildrenOfType(file, MojoMemberExpression::class.java)
            .filter { it.nameIdentifier.text in parameters }

        assertTrue(members.isNotEmpty())
        members.forEach { member ->
            assertSame(parameters.getValue(member.nameIdentifier.text).nameIdentifier, member.reference!!.resolve())
        }
        val elementDeclaration = PsiTreeUtil.findChildrenOfType(file, MojoComptimeStatement::class.java)
            .single { it.name == "Element" }
        val elementTypeReference = PsiTreeUtil.findChildrenOfType(file, MojoNameIdentifier::class.java)
            .single { it.text == "Element" && it.parent !is MojoDeclarationName }
        assertSame(elementDeclaration, elementTypeReference.reference!!.resolve())
        val functionMembers = members.filter {
            PsiTreeUtil.getParentOfType(
                it,
                MojoFunctionDeclaration::class.java,
            ) != null
        }
        assertEquals("Bool", functionMembers.single { it.nameIdentifier.text == "forward" }.inferredType?.displayName)
        assertEquals("DType", functionMembers.single { it.nameIdentifier.text == "dtype" }.inferredType?.displayName)
    }

    private fun reference(file: com.intellij.psi.PsiFile, name: String): MojoReferenceExpression =
        PsiTreeUtil.findChildrenOfType(file, MojoReferenceExpression::class.java)
            .filter { it.nameIdentifier.text.removeSurrounding("`") == name }
            .maxBy { it.textOffset }
}
