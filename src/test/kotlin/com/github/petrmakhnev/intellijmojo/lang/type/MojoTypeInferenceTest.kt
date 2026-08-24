package com.github.petrmakhnev.intellijmojo.lang.type

import com.github.petrmakhnev.intellijmojo.lang.MojoFileType
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoMemberExpression
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoLiteralExpression
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoNameIdentifier
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoAssignmentStatement
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoBinExpression
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoCallExpression
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoComptimeStatement
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoDeclarationName
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoFunctionDeclaration
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoParameterDeclaration
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoReferenceExpression
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoStructDeclaration
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoTypeParameterList
import com.github.petrmakhnev.intellijmojo.lang.resolve.bindingIdentifiers
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.math.BigInteger

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

    fun testGenericFieldTypeIsSpecializedFromReceiver() {
        val file = myFixture.configureByText(
            MojoFileType,
            """
            struct Box[T]:
                var value: T

            def read(box: Box[Int]):
                var result = box.value
            """.trimIndent(),
        )
        val function = PsiTreeUtil.findChildrenOfType(file, MojoFunctionDeclaration::class.java)
            .single { it.name == "read" }
        val fieldRead = PsiTreeUtil.findChildOfType(function, MojoMemberExpression::class.java)!!
        val result = PsiTreeUtil.findChildOfType(function, MojoAssignmentStatement::class.java)!!
            .bindingIdentifiers()
            .single()
        val inference = function.selfInferenceResult

        assertEquals("Int", fieldRead.inferredType?.displayName)
        assertEquals("Int", inference.getType(result)?.displayName)
    }

    fun testConstructorAppliesDefaultGenericArguments() {
        val file = myFixture.configureByText(
            MojoFileType,
            """
            struct DefaultBox[T: AnyType = Int]:
                var value: T

                def __init__(out self):
                    pass

            def make():
                var box = DefaultBox()
                var value = box.value
            """.trimIndent(),
        )
        val function = PsiTreeUtil.findChildrenOfType(file, MojoFunctionDeclaration::class.java)
            .single { it.name == "make" }
        val bindings = PsiTreeUtil.findChildrenOfType(function, MojoAssignmentStatement::class.java)
            .associate { assignment ->
                val identifier = assignment.bindingIdentifiers().single()
                identifier.text to identifier
            }
        val inference = function.selfInferenceResult

        assertEquals("DefaultBox[Int]", inference.getType(bindings.getValue("box"))?.displayName)
        assertEquals("Int", inference.getType(bindings.getValue("value"))?.displayName)
    }

    fun testComptimeBindingCanHoldAValueOrAType() {
        val file = myFixture.configureByText(
            MojoFileType,
            """
            def render(source):
                comptime BLOCK = "▇"
                comptime Element = Int
                comptime ExplicitType: AnyType = source
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
        val explicitType = function.selfInferenceResult.getType(declarations.getValue("ExplicitType"))
        val valueDeclaration = PsiTreeUtil.findChildOfType(file, MojoAssignmentStatement::class.java)!!
            .bindingIdentifiers()
            .single()

        assertEquals("StringLiteral", blockType?.displayName)
        assertEquals("type[Int]", elementType?.displayName)
        assertTrue(explicitType is MojoTy.TypeValue)
        assertTrue(declarations.getValue("ExplicitType").isTypeValuedComptime())
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

    fun testPartiallySpecializedComptimeAliasesPreserveValueArguments() {
        val file = myFixture.configureByText(
            MojoFileType,
            """
            struct DType:
                comptime float32 = DType()
                comptime uint64 = DType()

            struct SIMD[dtype: DType, length: Int]:
                def __init__[T](out self, value: T):
                    pass

            comptime Scalar = SIMD[
                _, length=__mlir_attr[`#lit.struct<{_mlir_value = 1}> : `, SIMDLength]
            ]
            comptime Float32 = Scalar[DType.float32]
            comptime UInt64 = Scalar[DType.uint64]

            def scales(pi: Float32):
                comptime SCALE = Float32(1.0) / Float32(UInt64(1) << 32)
                comptime SCALE_2PI = SCALE * Float32(2.0) * Float32(pi)
                comptime SCALE_HALF = SCALE * Float32(0.5)
                comptime SCALE_2PI_HALF = SCALE_2PI * Float32(0.5)
            """.trimIndent(),
        )
        val function = PsiTreeUtil.findChildrenOfType(file, MojoFunctionDeclaration::class.java)
            .single { it.name == "scales" }
        val inference = function.selfInferenceResult
        val scales = PsiTreeUtil.findChildrenOfType(function, MojoComptimeStatement::class.java)
            .associateBy { it.name }

        fun assertScalar(type: MojoTy?, alias: String, dtype: String) {
            assertEquals(alias, type?.displayName)
            assertTrue(type is MojoTy.Struct)
            val scalar = type as MojoTy.Struct
            assertEquals("SIMD", scalar.declaration.name)
            assertEquals(2, scalar.genericArguments.size)
            assertEquals("DType.$dtype", (scalar.genericArguments[0] as MojoGenericArg.Value).displayName)
            assertEquals("1", (scalar.genericArguments[1] as MojoGenericArg.Value).displayName)
        }

        listOf("SCALE", "SCALE_2PI", "SCALE_HALF", "SCALE_2PI_HALF").forEach { name ->
            assertScalar(inference.getType(scales.getValue(name)), "Float32", "float32")
        }
        PsiTreeUtil.findChildrenOfType(function, MojoCallExpression::class.java)
            .filter { it.expression.text == "Float32" }
            .also { assertEquals(6, it.size) }
            .forEach { call -> assertScalar(inference.getType(call), "Float32", "float32") }
        val uint64Call = PsiTreeUtil.findChildrenOfType(function, MojoCallExpression::class.java)
            .single { it.expression.text == "UInt64" }
        assertScalar(inference.getType(uint64Call), "UInt64", "uint64")
        PsiTreeUtil.findChildrenOfType(function, MojoBinExpression::class.java)
            .filter { it.binaryOp.text == "*" || it.binaryOp.text == "/" }
            .also { assertEquals(5, it.size) }
            .forEach { binary -> assertScalar(inference.getType(binary), "Float32", "float32") }
    }

    fun testMemberReturnTypeEvaluatesDependentReceiverLength() {
        val file = myFixture.configureByText(
            MojoFileType,
            """
            struct DType:
                comptime float32 = DType()

            struct Vec[dtype: DType, length: Int]:
                def join(self, other: Self) -> Vec[Self.dtype, 2 * Int(Self.length)]:
                    return self

                def half(self) -> Vec[Self.dtype, Self.length // 2]:
                    return self

            def combine(left: Vec[DType.float32, 4], right: Vec[DType.float32, 4]):
                var joined = left.join(right)
                var halved = left.half()
            """.trimIndent(),
        )
        val function = PsiTreeUtil.findChildrenOfType(file, MojoFunctionDeclaration::class.java)
            .single { it.name == "combine" }
        val joinCall = PsiTreeUtil.findChildrenOfType(function, MojoCallExpression::class.java)
            .single { it.text == "left.join(right)" }
        val bindings = PsiTreeUtil.findChildrenOfType(function, MojoAssignmentStatement::class.java)
            .associate { assignment ->
                val identifier = assignment.bindingIdentifiers().single()
                identifier.text to identifier
            }
        val inference = function.selfInferenceResult

        assertEquals("Vec[DType.float32, 8]", inference.getType(joinCall)?.displayName)
        assertEquals("Vec[DType.float32, 8]", inference.getType(bindings.getValue("joined"))?.displayName)
        assertEquals("Vec[DType.float32, 2]", inference.getType(bindings.getValue("halved"))?.displayName)
    }

    fun testComptimeValuesNormalizeAndRebindTransitively() {
        val file = myFixture.configureByText(
            MojoFileType,
            "struct Shape[M: Int, N: Int]:\n    pass\n",
        )
        val parameters = PsiTreeUtil.findChildrenOfType(file, MojoParameterDeclaration::class.java)
            .associateBy { it.nameIdentifier!!.text }
        val m = parameters.getValue("M")
        val n = parameters.getValue("N")
        val intType = MojoTy.Builtin("Int")
        val expression = MojoComptimeValue.Binary(
            MojoComptimeValue.Integer(BigInteger.TWO),
            "*",
            MojoComptimeValue.ParameterRef(n, "Self.N"),
        )
        val type = MojoTy.Builtin(
            "Vec",
            listOf(MojoGenericArg.Value(intType, expression.render(), comptimeValue = expression)),
        )
        val substitution: Map<PsiElement, MojoGenericArg> = mapOf(
            n to MojoGenericArg.Value(
                intType,
                "M",
                m,
                MojoComptimeValue.ParameterRef(m, "M"),
            ),
            m to MojoGenericArg.Value(
                intType,
                "4",
                comptimeValue = MojoComptimeValue.Integer(BigInteger.valueOf(4)),
            ),
        )

        assertEquals("Vec[8]", type.substitute(substitution).displayName)
        assertEquals(
            MojoComptimeValue.Integer(BigInteger.valueOf(4)),
            MojoComptimeValue.Binary(
                MojoComptimeValue.Integer(BigInteger.TWO),
                "*",
                MojoComptimeValue.Integer(BigInteger.TWO),
            ).normalized(),
        )
        assertEquals(
            MojoComptimeValue.Integer(BigInteger.valueOf(-2)),
            MojoComptimeValue.Binary(
                MojoComptimeValue.Integer(BigInteger.valueOf(-3)),
                "//",
                MojoComptimeValue.Integer(BigInteger.TWO),
            ).normalized(),
        )
        assertEquals(
            MojoComptimeValue.Integer(BigInteger.ONE),
            MojoComptimeValue.Binary(
                MojoComptimeValue.Integer(BigInteger.valueOf(-3)),
                "%",
                MojoComptimeValue.Integer(BigInteger.TWO),
            ).normalized(),
        )

        val directN = MojoGenericArg.Value(
            intType,
            "N",
            n,
            MojoComptimeValue.ParameterRef(n, "N"),
        )
        val qualifiedN = MojoGenericArg.Value(
            intType,
            "Self.N",
            n,
            MojoComptimeValue.ParameterRef(n, "Self.N"),
        )
        val directType = MojoTy.Builtin("Vec", listOf(directN))
        val qualifiedType = MojoTy.Builtin("Vec", listOf(qualifiedN))
        assertEquals(directN, qualifiedN)
        assertEquals(directType, directType.join(qualifiedType))

        val hugeShift = MojoComptimeValue.Binary(
            MojoComptimeValue.Integer(BigInteger.ONE),
            "<<",
            MojoComptimeValue.Integer(BigInteger.valueOf(2_000_000_000)),
        ).normalized()
        assertInstanceOf(hugeShift, MojoComptimeValue.Binary::class.java)
    }

    private fun reference(file: com.intellij.psi.PsiFile, name: String): MojoReferenceExpression =
        PsiTreeUtil.findChildrenOfType(file, MojoReferenceExpression::class.java)
            .filter { it.nameIdentifier.text.removeSurrounding("`") == name }
            .maxBy { it.textOffset }
}
