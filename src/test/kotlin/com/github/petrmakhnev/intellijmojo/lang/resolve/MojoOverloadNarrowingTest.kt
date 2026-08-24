package com.github.petrmakhnev.intellijmojo.lang.resolve

import com.github.petrmakhnev.intellijmojo.lang.MojoFileType
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoCallExpression
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoFunctionDeclaration
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoReferenceExpression
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class MojoOverloadNarrowingTest : BasePlatformTestCase() {
    fun testLiteralArgumentTypesSelectOneTopLevelOverload() {
        val file = myFixture.configureByText(
            MojoFileType,
            """
            def select(value: Int) -> Int:
                return value

            def select(value: String) -> String:
                return value

            def use_overloads():
                var number = select(1)
                var text = select("mojo")
            """.trimIndent(),
        )
        val declarations = PsiTreeUtil.findChildrenOfType(file, MojoFunctionDeclaration::class.java)
            .filter { it.name == "select" }
            .sortedBy { it.textOffset }
        val calls = PsiTreeUtil.findChildrenOfType(file, MojoCallExpression::class.java)
            .filter { it.expression.text == "select" }
            .sortedBy { it.textOffset }

        assertSize(2, declarations)
        assertSize(2, calls)
        assertNarrowed(calls[0], selected = declarations[0], rejected = declarations[1])
        assertNarrowed(calls[1], selected = declarations[1], rejected = declarations[0])
    }

    private fun assertNarrowed(
        call: MojoCallExpression,
        selected: MojoFunctionDeclaration,
        rejected: MojoFunctionDeclaration,
    ) {
        val callee = call.expression as MojoReferenceExpression
        val reference = callee.reference as PsiPolyVariantReference
        val resolved = reference.multiResolve(false).mapNotNull { it.element }

        assertEquals(listOf(selected), resolved)
        assertSame(selected, reference.resolve())
        assertTrue(reference.isReferenceTo(selected))
        assertFalse(reference.isReferenceTo(rejected))
    }
}
