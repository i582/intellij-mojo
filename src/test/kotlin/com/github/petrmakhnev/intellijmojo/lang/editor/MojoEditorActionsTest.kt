package com.github.petrmakhnev.intellijmojo.lang.editor

import com.github.petrmakhnev.intellijmojo.lang.MojoFileType
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class MojoEditorActionsTest : BasePlatformTestCase() {
    fun testEnterPreservesTabIndent() {
        myFixture.configureByText(MojoFileType, "\tprint(\"Hello\")<caret>")

        myFixture.performEditorAction(IdeActions.ACTION_EDITOR_ENTER)

        myFixture.checkResult("\tprint(\"Hello\")\n\t<caret>")
    }

    fun testEnterAddsTabIndentAfterSuiteColon() {
        myFixture.configureByText(MojoFileType, "\tif True:<caret>")

        myFixture.performEditorAction(IdeActions.ACTION_EDITOR_ENTER)

        myFixture.checkResult("\tif True:\n\t\t<caret>")
    }

    fun testEnterAddsSpaceIndentAfterSuiteColon() {
        myFixture.configureByText(MojoFileType, "def main():<caret>")

        myFixture.performEditorAction(IdeActions.ACTION_EDITOR_ENTER)

        myFixture.checkResult("def main():\n    <caret>")
    }

    fun testCommentColonDoesNotAddIndent() {
        myFixture.configureByText(MojoFileType, "\t# Note:<caret>")

        myFixture.performEditorAction(IdeActions.ACTION_EDITOR_ENTER)

        myFixture.checkResult("\t# Note:\n\t<caret>")
    }

    fun testEnterDedentsAfterPassLikePython() {
        myFixture.configureByText(
            MojoFileType,
            "def _():\n    if True:\n        pass<caret>",
        )

        myFixture.performEditorAction(IdeActions.ACTION_EDITOR_ENTER)

        myFixture.checkResult("def _():\n    if True:\n        pass\n    <caret>")
    }

    fun testEnterDedentsTabIndentAfterPass() {
        myFixture.configureByText(
            MojoFileType,
            "def _():\n\tif True:\n\t\tpass<caret>",
        )

        myFixture.performEditorAction(IdeActions.ACTION_EDITOR_ENTER)

        myFixture.checkResult("def _():\n\tif True:\n\t\tpass\n\t<caret>")
    }

    fun testEnterDedentsAfterPythonTerminalStatements() {
        for (statement in listOf("return", "raise Error()", "...")) {
            myFixture.configureByText(
                MojoFileType,
                "def _():\n    if True:\n        $statement<caret>",
            )

            myFixture.performEditorAction(IdeActions.ACTION_EDITOR_ENTER)

            myFixture.checkResult("def _():\n    if True:\n        $statement\n    <caret>")
        }
    }

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
