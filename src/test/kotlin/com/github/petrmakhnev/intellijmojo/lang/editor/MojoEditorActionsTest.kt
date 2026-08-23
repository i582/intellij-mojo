package com.github.petrmakhnev.intellijmojo.lang.editor

import com.github.petrmakhnev.intellijmojo.lang.MojoFileType
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class MojoEditorActionsTest : BasePlatformTestCase() {
    fun testToggleIndentedLineComment() {
        myFixture.configureByText(
            MojoFileType,
            """
            def main():
                <caret>print("Hello")
            """.trimIndent(),
        )

        myFixture.performEditorAction(IdeActions.ACTION_COMMENT_LINE)
        myFixture.checkResult(
            """
            def main():
                # print("Hello")
            """.trimIndent(),
        )

        myFixture.performEditorAction(IdeActions.ACTION_COMMENT_LINE)
        myFixture.checkResult(
            """
            def main():
                print("Hello")
            """.trimIndent(),
        )
    }

    fun testToggleCommentForSelectedLines() {
        myFixture.configureByText(
            MojoFileType,
            """
            def main():
                <selection>print("Hello")
                return</selection>
            """.trimIndent(),
        )

        myFixture.performEditorAction(IdeActions.ACTION_COMMENT_LINE)
        myFixture.checkResult(
            """
            def main():
                # print("Hello")
                # return
            """.trimIndent(),
        )
    }

    fun testAutoCloseDoubleQuoteAndSkipClosingQuote() {
        myFixture.configureByText(MojoFileType, "var value = <caret>")

        myFixture.type('"')
        myFixture.checkResult("var value = \"<caret>\"")

        myFixture.type('"')
        myFixture.checkResult("var value = \"\"<caret>")
    }

    fun testAutoCloseSingleQuote() {
        myFixture.configureByText(MojoFileType, "var value = <caret>")

        myFixture.type('\'')
        myFixture.checkResult("var value = '<caret>'")
    }

    fun testAutoCloseTripleDoubleQuote() {
        myFixture.configureByText(MojoFileType, "var value = <caret>")

        myFixture.type("\"\"\"")
        myFixture.checkResult("var value = " + "\"".repeat(3) + "<caret>" + "\"".repeat(3))

        myFixture.type("\"\"\"")
        myFixture.checkResult("var value = " + "\"".repeat(6) + "<caret>")
    }

    fun testAutoCloseTripleSingleQuote() {
        myFixture.configureByText(MojoFileType, "var value = <caret>")

        myFixture.type("'''")
        myFixture.checkResult("var value = '''<caret>'''")
    }

    fun testAutoClosePrefixedTripleQuote() {
        myFixture.configureByText(MojoFileType, "var value = r<caret>")

        myFixture.type("\"\"\"")
        myFixture.checkResult("var value = r" + "\"".repeat(3) + "<caret>" + "\"".repeat(3))
    }

    fun testAutoCloseBracePairs() {
        myFixture.configureByText(MojoFileType, "var value = <caret>")

        myFixture.type("([{")
        myFixture.checkResult("var value = ([{<caret>}])")
    }
}
