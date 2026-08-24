package com.github.petrmakhnev.intellijmojo.lang.parser

import com.github.petrmakhnev.intellijmojo.lang.MojoFileType
import com.github.petrmakhnev.intellijmojo.lang.lexer.MojoIndentingLexer
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoCallExpression
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoExpression
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoFile
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoFunctionDeclaration
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoLiteralExpression
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoMlirAttribute
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoMlirTypeValue
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

    fun testParameterizedWritableStruct() {
        val file = myFixture.configureByText(
            MojoFileType,
            """
            struct _Indent[W: Writable, origin: ImmOrigin](Writable):
                ""${'"'}Indents the given writable by the given level.""${'"'}

                comptime IndentStr = "  "

                var writable: Pointer[Self.W, Self.origin]
                var level: Int

                def __init__(out self, ref[Self.origin] w: Self.W, *, level: Int):
                    self.writable = Pointer(to=w)
                    self.level = level

                def write_to(self, mut writer: Some[Writer]):
                    for _ in range(self.level):
                        writer.write(Self.IndentStr)
                    writer.write(self.writable[])
            """.trimIndent(),
        )

        assertNoParserErrors(file)
    }

    fun testConventionParameters() {
        val signatures = listOf(
            "def f(out self):",
            "def f(ref[Int] w: Int):",
            "def f(ref[Self] w: Self):",
            "def f(ref[Self.origin] w: Self.W):",
            "def f(*, level: Int):",
            "def f(out self, ref[Self.origin] w: Self.W):",
            "def f(out self, ref[Self.origin] w: Self.W, *, level: Int):",
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

    fun testVariadicTypeParameter() {
        val file = myFixture.configureByText(
            MojoFileType,
            """
            def _all_types_unique[*Ts: AnyType]() -> Bool:
                ""${'"'}Check if all types in the variadic pack are unique.

                Returns True if no type appears more than once, False otherwise.
                ""${'"'}

                comptime for i in range(Ts.length):
                    comptime for j in range(i + 1, Ts.length):
                        if Ts[i] == Ts[j]:
                            return False
                return True
            """.trimIndent(),
        )

        assertNoParserErrors(file)
    }

    fun testInferOnlyMarkerInStructParameters() {
        val file = myFixture.configureByText(
            MojoFileType,
            """
            @fieldwise_init
            struct _SpanIter[
                mut: Bool,
                //,
                T: Copyable,
                origin: Origin[mut=mut],
                forward: Bool = True,
            ](ImplicitlyCopyable, Iterable, IterableOwned, Iterator, Sized):
                pass
            """.trimIndent(),
        )

        assertNoParserErrors(file)
    }

    fun testStructWithDefaultTypeParameterAndMultilineConformance() {
        val file = myFixture.configureByText(
            MojoFileType,
            """
            struct CompilationTarget[value: _TargetType = _current_target()](
                TrivialRegisterPassable
            ):
                ${'"'}${'"'}${'"'}A struct that provides information about a target architecture.${'"'}${'"'}${'"'}

                def __init__(out self):
                    ${'"'}${'"'}${'"'}Initialize a `CompilationTarget` with the default target.${'"'}${'"'}${'"'}
                    pass
            """.trimIndent(),
        )

        assertNoParserErrors(file)
    }

    fun testStructWithInferOnlyAndKeywordOnlyTypeParameters() {
        val file = myFixture.configureByText(
            MojoFileType,
            """
            @stable(since="1.0")
            struct Pointer[
                mut: Bool,
                //,
                T: AnyType,
                origin: Origin[mut=mut],
                *,
                address_space: AddressSpace = AddressSpace.GENERIC,
            ](
                Comparable,
                DevicePassable,
                ImplicitlyCopyable,
                Intable,
                TrivialRegisterPassable,
                UnsafeCustomNicheStorage,
                UnsafeSingleNicheable,
                Writable,
            ):
                pass
            """.trimIndent(),
        )

        assertNoParserErrors(file)
    }

    fun testConditionalConformances() {
        val file = myFixture.configureByText(
            MojoFileType,
            """
            @stable(since="1.0")
            struct Span[
                mut: Bool,
                //,
                T: AnyType,
                origin: Origin[mut=mut],
                *,
                address_space: AddressSpace = AddressSpace.GENERIC,
            ](
                Boolable,
                Defaultable,
                DevicePassable where address_space == AddressSpace.GENERIC,
                Hashable where (
                    conforms_to(T, Hashable) and address_space == AddressSpace.GENERIC
                ),
                ImplicitlyCopyable,
                Iterable where address_space == AddressSpace.GENERIC,
                IterableOwned where (
                    conforms_to(T, Copyable) and address_space == AddressSpace.GENERIC
                ),
                Sized,
                TrivialRegisterPassable,
                Writable where (
                    conforms_to(T, Writable) and address_space == AddressSpace.GENERIC
                ),
            ):
                pass
            """.trimIndent(),
        )

        assertNoParserErrors(file)
    }

    fun testFunctionTypeWithUnnamedParameterTypes() {
        val file = myFixture.configureByText(
            MojoFileType,
            """
            def write[
                callback: def(Self.T, mut Some[Writer]) thin,
                predicate: def(Int, Int, /) thin -> Bool,
                update: def(value: Optional[Self.T]) raises -> Self.T,
            ]():
                pass
            """.trimIndent(),
        )

        assertNoParserErrors(file)
    }

    fun testMacosVersionSyntax() {
        val file = myFixture.configureByText(
            MojoFileType,
            """
            @always_inline
            def _macos_version() raises -> Tuple[Int, Int, Int]:
                ${'"'}${'"'}${'"'}Gets the macOS version.

                Returns:
                    The version triple of macOS.
                ${'"'}${'"'}${'"'}

                comptime assert (
                    CompilationTarget.is_macos()
                ), "the operating system must be macOS"

                comptime INITIAL_CAPACITY = 32

                var buf_len = Int(INITIAL_CAPACITY)
                var osver = String(unsafe_uninit_length=buf_len)

                var err = external_call["sysctlbyname", Int32](
                    "kern.osproductversion".as_c_string_slice(),
                    osver.unsafe_as_bytes_mut().unsafe_ptr(),
                    Pointer(to=buf_len),
                    Optional[Pointer[NoneType, MutAnyOrigin]](),
                    Int(0),
                )
                if err:
                    raise "Unable to query macOS version"

                osver.resize(buf_len)

                var major = 0
                var minor = 0
                var patch = 0

                if "." in osver:
                    major = Int(osver[byte = : osver.find(".")])
                    var rest_major = String(osver[byte = osver.find(".") + 1 :])
                    osver = rest_major^

                if "." in osver:
                    minor = Int(osver[byte = : osver.find(".")])
                    var rest_minor = String(osver[byte = osver.find(".") + 1 :])
                    osver = rest_minor^

                if "." in osver:
                    patch = Int(osver[byte = : osver.find(".")])

                return (major, minor, patch)
            """.trimIndent(),
        )

        assertNoParserErrors(file)
    }

    fun testGpuPerformanceCounterSyntaxParts() {
        val cases = mapOf(
            "decorator" to "@always_inline\ndef f():\n    pass\n",
            "raises return type" to "def f() raises -> Tuple[Int, Int, Int]:\n    pass\n",
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

    fun testMacosVersionSyntaxParts() {
        val cases = mapOf(
            "comptime assert" to "def f():\n    comptime assert (Target.is_macos()), \"macOS required\"\n",
            "comptime binding" to "def f():\n    comptime INITIAL_CAPACITY = 32\n",
            "nested generic call" to "def f():\n    return Optional[Pointer[NoneType, MutAnyOrigin]]()\n",
            "named slice from start" to "def f(osver):\n    return osver[byte = : osver.find(\".\")]\n",
            "named slice to end" to "def f(osver):\n    return osver[byte = osver.find(\".\") + 1 :]\n",
            "transfer" to "def f(rest):\n    value = rest^\n",
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

    fun testMlirKeywordCanBeAFunctionName() {
        val file = myFixture.configureByText(
            MojoFileType,
            """
            struct DType:
                @always_inline("nodebug")
                def __mlir_type(self) -> __mlir_type.`!kgen.deferred`:
                    if self == DType.bool:
                        return __mlir_attr.i1
                    abort("invalid dtype")

            struct Atomic:
                def __mlir_attr(self) -> __mlir_type.`!kgen.deferred`:
                    return __mlir_attr.i64
            """.trimIndent(),
        )

        assertNoParserErrors(file)
        val names = PsiTreeUtil.findChildrenOfType(file, MojoFunctionDeclaration::class.java)
            .mapNotNull { it.name }
        assertEquals(listOf("__mlir_type", "__mlir_attr"), names)
        assertSize(2, PsiTreeUtil.findChildrenOfType(file, MojoMlirTypeValue::class.java))
        assertSize(2, PsiTreeUtil.findChildrenOfType(file, MojoMlirAttribute::class.java))
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
