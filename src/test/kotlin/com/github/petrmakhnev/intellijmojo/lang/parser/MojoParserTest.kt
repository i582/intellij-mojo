package com.github.petrmakhnev.intellijmojo.lang.parser

import com.github.petrmakhnev.intellijmojo.lang.MojoFileType
import com.github.petrmakhnev.intellijmojo.lang.lexer.MojoIndentingLexer
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoCallExpression
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoExpression
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoFile
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoFunctionDeclaration
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoLiteralExpression
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoReferenceExpression
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoStructDeclaration
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoSubscriptExpression
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class MojoParserTest : BasePlatformTestCase() {
    fun testFunctionUsesGeneratedPsi() {
        val file = myFixture.configureByText(
            MojoFileType,
            """
            def main():
                print("Hello World")
            """.trimIndent(),
        )

        assertInstanceOf(file, MojoFile::class.java)
        assertNotNull(PsiTreeUtil.findChildOfType(file, MojoFunctionDeclaration::class.java))
        assertNoParserErrors(file)
    }

    fun testTypedFunctionSignatures() {
        val signatures = listOf(
            "def f(value: Int):",
            "def f(values: List[Int]):",
            "def f(values: List[List[Int]]):",
            "def f(rows: Int, cols: Int, grid: List[List[Int]]):",
            "def f(rows: Int, cols: Int, grid: List[List[Int]]) -> String:",
        )

        for (signature in signatures) {
            val file = myFixture.configureByText(MojoFileType, "$signature\n    pass\n")
            try {
                assertNoParserErrors(file)
            } catch (error: AssertionError) {
                throw AssertionError("Failed signature: $signature\n${error.message}", error)
            }
        }
    }

    fun testSelectiveImportWithUnparenthesizedTargets() {
        val file = myFixture.configureByText(
            MojoFileType,
            "from std.collections import Array, List\n",
        )

        assertNoParserErrors(file)
    }

    fun testVariableAssignment() {
        val file = myFixture.configureByText(MojoFileType, "var str = String()\n")
        assertNoParserErrors(file)
    }

    fun testVariableAssignmentInFunction() {
        val file = myFixture.configureByText(MojoFileType, "def f():\n    var str = String()\n")
        assertNoParserErrors(file)
    }

    fun testVariableAssignmentAfterReturnType() {
        val file = myFixture.configureByText(
            MojoFileType,
            "def grid_str() -> String:\n    var str = String()\n    return str\n",
        )
        assertNoParserErrors(file)
    }

    fun testCallHasConcreteExpressionNode() {
        val file = myFixture.configureByText(MojoFileType, "foo()\n")

        assertNoParserErrors(file)
        val calls = PsiTreeUtil.findChildrenOfType(file, MojoCallExpression::class.java)
        assertSize(1, calls)
        assertEquals("foo()", calls.single().text)
    }

    fun testLiteralDoesNotCreatePrecedenceWrappers() {
        val file = myFixture.configureByText(MojoFileType, "\"hello\"\n")

        assertNoParserErrors(file)
        val expressions = PsiTreeUtil.findChildrenOfType(file, MojoExpression::class.java)
        assertSize(1, expressions)
        assertInstanceOf(expressions.single(), MojoLiteralExpression::class.java)
    }

    fun testParametricCallPsiShapeMatchesCompilerAst() {
        val file = myFixture.configureByText(
            MojoFileType,
            "external_call[\"clock_gettime\", Int32](Int32(clockid), Pointer(to=ts))\n",
        )

        assertNoParserErrors(file)
        val calls = PsiTreeUtil.findChildrenOfType(file, MojoCallExpression::class.java)
        val outerCall = calls.single { it.text.startsWith("external_call") }
        val subscript = assertInstanceOf(outerCall.expression, MojoSubscriptExpression::class.java)
        val reference = assertInstanceOf(subscript.expression, MojoReferenceExpression::class.java)
        assertEquals("external_call", reference.text)
    }

    fun testCommentBeforeFirstSuiteStatement() {
        val file = myFixture.configureByText(
            MojoFileType,
            "def f():\n    # comment\n\n    var str = String()\n",
        )
        assertNoParserErrors(file)
    }

    fun testGpuPerformanceCounterFunction() {
        val file = myFixture.configureByText(
            MojoFileType,
            """
            @always_inline
            def global_perf_counter_ns() -> SIMD[DType.uint64, 1]:
                ""${'"'}Returns the current value in the global nanosecond resolution timer. This value
                is common across all SM's.

                Returns:
                    The current time in ns.
                ""${'"'}

                comptime if is_nvidia_gpu():
                    return llvm_intrinsic[
                        "llvm.nvvm.read.ptx.sreg.globaltimer",
                        UInt64,
                        has_side_effect=True,
                    ]()
                elif is_amd_gpu():
                    var ticks = _amd_gpu_realtime()
                    return (ticks * 1_000_000_000) // _AMD_GPU_REALTIME_FREQ_HZ

                return UInt64(perf_counter_ns())
            """.trimIndent(),
        )
        assertNoParserErrors(file)
    }

    fun testGpuPerformanceCounterSyntaxParts() {
        val cases = mapOf(
            "decorator" to "@always_inline\ndef f():\n    pass\n",
            "return type" to "def f() -> SIMD[DType.uint64, 1]:\n    pass\n",
            "docstring" to "def f():\n    \"\"\"first line\n    second line\n    \"\"\"\n    pass\n",
            "comptime if" to "def f():\n    comptime if is_nvidia_gpu():\n        pass\n    elif is_amd_gpu():\n        pass\n",
            "generic call" to "def f():\n    return llvm_intrinsic[\"name\", UInt64, has_side_effect=True,]()\n",
        )
        for ((name, source) in cases) {
            val file = myFixture.configureByText(MojoFileType, source)
            try {
                assertNoParserErrors(file)
            } catch (error: AssertionError) {
                throw AssertionError("Failed syntax part: $name\n${error.message}", error)
            }
        }
    }

    fun testTryWithExceptAndFinally() {
        val file = myFixture.configureByText(
            MojoFileType,
            """
            def guarded(value):
                try:
                    with open("file.txt") as file:
                        return {"value": value, **file.read()}
                except Error as error:
                    raise error from value
                finally:
                    print("done")
            """.trimIndent(),
        )
        assertNoParserErrors(file)
    }

    fun testTryStatementVariants() {
        val bodies = listOf(
            "try:\n        pass\n    finally:\n        pass",
            "try:\n        pass\n    except:\n        pass",
            "try:\n        with open(\"x\") as file:\n            pass\n    finally:\n        pass",
            "try:\n        return {\"value\": value, **file.read()}\n    finally:\n        pass",
        )
        for (body in bodies) {
            val file = myFixture.configureByText(MojoFileType, "def f():\n    $body\n")
            try {
                assertNoParserErrors(file)
            } catch (error: AssertionError) {
                throw AssertionError("Failed body:\n$body\n${error.message}", error)
            }
        }
    }

    fun testFullMojoCorpus() {
        val source = requireNotNull(javaClass.getResource("/parser/full.mojo")).readText()
        val file = myFixture.configureByText(MojoFileType, source)

        assertNoParserErrors(file)
        assertTrue(PsiTreeUtil.findChildrenOfType(file, MojoFunctionDeclaration::class.java).isNotEmpty())
        assertTrue(PsiTreeUtil.findChildrenOfType(file, MojoStructDeclaration::class.java).isNotEmpty())
    }

    fun testUpstreamTreeSitterCorpus() {
        val failures = mutableListOf<String>()
        for (resourcePath in UPSTREAM_CORPUS) {
            val corpus = requireNotNull(javaClass.getResource(resourcePath)).readText()
            for (section in CORPUS_SECTION.findAll(corpus)) {
                val title = section.groupValues[1].trim()
                val source = section.groupValues[2].trimEnd() + "\n"
                val file = PsiFileFactory.getInstance(project).createFileFromText(
                    "${title.replace(NON_FILE_NAME, "_")}.mojo",
                    MojoFileType,
                    source,
                )
                val errors = PsiTreeUtil.findChildrenOfType(file, PsiErrorElement::class.java)
                if (errors.isNotEmpty()) {
                    failures += buildString {
                        append(resourcePath).append(" :: ").append(title)
                        errors.take(3).forEach { error ->
                            append("\n  ").append(error.errorDescription)
                                .append(" at ").append(error.textOffset)
                        }
                    }
                }
            }
        }

        assertTrue(failures.joinToString(prefix = "Upstream corpus failures:\n", separator = "\n"), failures.isEmpty())
    }

    fun testPastingNestedBlocksIntoOpenEditor() {
        val source = requireNotNull(javaClass.getResource("/parser/grid_str.mojo")).readText()
        val file = myFixture.configureByText(MojoFileType, "def main():\n    pass\n")

        WriteCommandAction.runWriteCommandAction(project) {
            myFixture.editor.document.insertString(myFixture.editor.document.textLength, source)
        }
        PsiDocumentManager.getInstance(project).commitDocument(myFixture.editor.document)

        assertNoParserErrors(file)
    }

    private fun assertNoParserErrors(file: com.intellij.psi.PsiFile) {
        val errors = PsiTreeUtil.findChildrenOfType(file, PsiErrorElement::class.java)
        assertTrue(
            errors.joinToString(prefix = "Parser errors:\n", separator = "\n") { error ->
                "${error.errorDescription} at ${error.textRange}: `${error.parent.text.take(120)}`\n" +
                    tokenContext(file.text, error.textOffset)
            },
            errors.isEmpty(),
        )
    }

    private fun tokenContext(source: String, errorOffset: Int): String {
        val lexer = MojoIndentingLexer()
        lexer.start(source)
        val tokens = buildList {
            while (lexer.tokenType != null) {
                if (lexer.tokenEnd >= errorOffset - 12 && lexer.tokenStart <= errorOffset + 80) {
                    add("${lexer.tokenType}@${lexer.tokenStart}:${lexer.tokenEnd}")
                }
                lexer.advance()
            }
        }
        return tokens.joinToString(prefix = "tokens: ")
    }

    companion object {
        private val CORPUS_SECTION = Regex(
            "(?ms)^={20,}\\R(.+?)\\R={20,}\\R\\R(.*?)\\R\\R-{20,}\\R",
        )
        private val NON_FILE_NAME = Regex("[^A-Za-z0-9_.-]")
        private val UPSTREAM_CORPUS = listOf(
            "/upstream/tree-sitter-mojo/mojo/MLIR.txt",
            "/upstream/tree-sitter-mojo/mojo/expressions.txt",
            "/upstream/tree-sitter-mojo/mojo/statements.txt",
            "/upstream/tree-sitter-mojo/python/expressions.txt",
            "/upstream/tree-sitter-mojo/python/literals.txt",
            "/upstream/tree-sitter-mojo/python/pattern_matching.txt",
            "/upstream/tree-sitter-mojo/python/statements.txt",
        )
    }
}
