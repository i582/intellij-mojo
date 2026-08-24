package com.github.petrmakhnev.intellijmojo.lang.ide.hints

import com.github.petrmakhnev.intellijmojo.lang.MojoFileType
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoExpression
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class MojoTypeProviderTest : BasePlatformTestCase() {
    private val provider = MojoTypeProvider()

    fun testSelectsCallResultInsteadOfCallee() {
        val expressions = expressionsAtCaret(
            """
            def make_value() -> Int:
                return 1

            def main():
                var value = make_<caret>value()
            """.trimIndent(),
        )

        assertEquals(listOf("make_value()"), expressions.map { it.text })
        assertEquals("Int", provider.getInformationHint(expressions.single()))
    }

    fun testOffersNestedExpressionsFromInnerToOuter() {
        val expressions = expressionsAtCaret(
            """
            def main():
                var value = 1 + <caret>2
            """.trimIndent(),
        )

        assertEquals(listOf("2", "1 + 2"), expressions.map { it.text })
        assertEquals(listOf("IntLiteral", "IntLiteral"), expressions.map(provider::getInformationHint))
    }

    fun testEscapesFunctionTypeForHintMarkup() {
        val expressions = expressionsAtCaret(
            """
            def identity(value: Int) -> Int:
                return value

            def main():
                var callback = iden<caret>tity
            """.trimIndent(),
        )

        assertEquals(listOf("identity"), expressions.map { it.text })
        assertEquals("def(Int) -&gt; Int", provider.getInformationHint(expressions.single()))
    }

    private fun expressionsAtCaret(source: String): List<MojoExpression> {
        val file = myFixture.configureByText(MojoFileType, source)
        val element = requireNotNull(file.findElementAt(myFixture.caretOffset))
        return provider.getExpressionsAt(element)
    }
}
