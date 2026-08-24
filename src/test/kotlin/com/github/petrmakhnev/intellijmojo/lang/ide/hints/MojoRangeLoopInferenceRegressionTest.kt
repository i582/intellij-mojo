package com.github.petrmakhnev.intellijmojo.lang.ide.hints

import com.github.petrmakhnev.intellijmojo.lang.MojoFileType
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoCallExpression
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoForStatement
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoFunctionDeclaration
import com.github.petrmakhnev.intellijmojo.lang.resolve.bindingIdentifiers
import com.github.petrmakhnev.intellijmojo.lang.type.MojoGenericArg
import com.github.petrmakhnev.intellijmojo.lang.type.MojoTy
import com.github.petrmakhnev.intellijmojo.lang.type.displayName
import com.github.petrmakhnev.intellijmojo.lang.type.selfInferenceResult
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.utils.inlays.declarative.DeclarativeInlayHintsProviderTestCase

class MojoRangeLoopInferenceRegressionTest : DeclarativeInlayHintsProviderTestCase() {
    fun testRangeCallAndLoopBindingFollowIteratorProtocol() {
        val file = myFixture.configureByText(MojoFileType, RANGE_SOURCE)
        val step = PsiTreeUtil.findChildrenOfType(file, MojoFunctionDeclaration::class.java)
            .single { it.name == "step" }
        val inference = step.selfInferenceResult
        val rangeCall = PsiTreeUtil.findChildrenOfType(step, MojoCallExpression::class.java)
            .single { it.expression.text == "range" }

        assertEquals(listOf("range"), inference.getResolvedCall(rangeCall).map { it.name })
        val rangeType = inference.getType(rangeCall)
        assertInstanceOf(rangeType, MojoTy.Struct::class.java)
        assertEquals("_ZeroStartingRange[DType.int]", rangeType?.displayName)
        val rangeArguments = (rangeType as MojoTy.Struct).genericArguments
        assertEquals(1, rangeArguments.size)
        assertEquals("DType.int", (rangeArguments.single() as MojoGenericArg.Value).displayName)

        val loop = PsiTreeUtil.findChildOfType(step, MojoForStatement::class.java)!!
        val binding = loop.patternList!!.bindingIdentifiers().single()
        assertTrue(inference.isLocalDeclaration(binding))
        assertEquals("Int", inference.getType(binding)?.displayName)
    }

    fun testRangeLoopBindingIsEligibleForInlineIntHint() {
        doTestProvider(
            "range-loop-hints.mojo",
            RANGE_SOURCE.replace("for i in range", "for i/*<# : |Int #>*/ in range"),
            MojoTypeHintsProvider(),
            mapOf("hint.type.variable" to true),
        )
    }

    private companion object {
        val RANGE_SOURCE =
            """
            trait AnyType:
                pass

            trait Iterator:
                comptime Element: AnyType

                def __next__(mut self: Self) -> Self.Element:
                    ...

            trait Iterable:
                comptime IteratorType: Iterator

                def __iter__(ref self: Self) -> Self.IteratorType:
                    ...

            struct DType:
                comptime int = DType()

            struct SIMD[dtype: DType, length: Int]:
                def __sub__(self: Self, rhs: Self) -> Self:
                    return self

            comptime Scalar = SIMD[_, 1]
            comptime Int = Scalar[DType.int]

            struct _ZeroStartingRange[dtype: DType](Iterable, Iterator):
                comptime IteratorType = Self
                comptime Element = Scalar[Self.dtype]

                def __iter__(ref self: Self) -> Self.IteratorType:
                    return self

                def __next__(mut self: Self) -> Self.Element:
                    return 0

            def range[T: AnyType](end: T) -> _ZeroStartingRange[DType.int]:
                return _ZeroStartingRange(end)

            def range[dtype: DType](end: Scalar[dtype]) -> _ZeroStartingRange[dtype]:
                return _ZeroStartingRange(end)

            struct Random[rounds: Int = 10]:
                def step(self: Self):
                    comptime for i in range(Self.rounds - 1):
                        print(i)
            """.trimIndent()
    }
}
