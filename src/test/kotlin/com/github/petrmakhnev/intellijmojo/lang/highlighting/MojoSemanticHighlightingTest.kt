package com.github.petrmakhnev.intellijmojo.lang.highlighting

import com.github.petrmakhnev.intellijmojo.ide.colors.MojoColor
import com.github.petrmakhnev.intellijmojo.ide.colors.MojoColorSettingsPage
import com.github.petrmakhnev.intellijmojo.lang.MojoFileType
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoTypes
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class MojoSemanticHighlightingTest : BasePlatformTestCase() {
    fun testSyntacticSemanticRoles() {
        val source = """
            struct Point[T]:
                var x: T

                fn move(self, var dx: Int):
                    var result = Point()
                    result.x += dx
                    print(value=result)

            def outer(value: Int):
                def normalize(item: Int) -> Int:
                    return item
                external_call["clock_gettime", Int32](Int32(value))
                return normalize(value)

            type Index = Int
            comptime DEFAULT_SIZE = 16
            __mlir_region lower_point():
                pass
        """.trimIndent()
        myFixture.configureByText(MojoFileType, source)
        val highlights = myFixture.doHighlighting()

        assertColor(source, highlights, "Point", 0, MojoColor.STRUCT)
        assertColor(source, highlights, "T", 0, MojoColor.TYPE_PARAMETER)
        assertColor(source, highlights, "x", 0, MojoColor.FIELD)
        assertColor(source, highlights, "move", 0, MojoColor.METHOD)
        assertColor(source, highlights, "self", 0, MojoColor.SELF_PARAMETER)
        assertColor(source, highlights, "dx", 0, MojoColor.MUTABLE_PARAMETER)
        assertColor(source, highlights, "Int", 0, MojoColor.BUILTIN_TYPE)
        assertColor(source, highlights, "result", 0, MojoColor.MUTABLE_VARIABLE)
        assertColor(source, highlights, "result", 1, MojoColor.MUTABLE_VARIABLE)
        assertColor(source, highlights, "x", 1, MojoColor.FIELD)
        assertColor(source, highlights, "print", 0, MojoColor.FUNCTION_CALL)
        assertColor(source, highlights, "value", 0, MojoColor.KEYWORD_ARGUMENT)
        assertColor(source, highlights, "outer", 0, MojoColor.FUNCTION)
        assertColor(source, highlights, "normalize", 0, MojoColor.NESTED_FUNCTION)
        assertColor(source, highlights, "external_call", 0, MojoColor.FUNCTION_CALL)
        assertColor(source, highlights, "normalize", 1, MojoColor.FUNCTION_CALL)
        assertColor(source, highlights, "Index", 0, MojoColor.TYPE_ALIAS)
        assertColor(source, highlights, "DEFAULT_SIZE", 0, MojoColor.CONSTANT)
        assertColor(source, highlights, "lower_point", 0, MojoColor.MLIR_SYMBOL)
    }

    fun testColorSettingsPageExposesAllColorsAndDemo() {
        val page = MojoColorSettingsPage()

        assertEquals(MojoColor.entries.size, page.attributeDescriptors.size)
        assertEquals(MojoColor.entries.size, page.additionalHighlightingTagToDescriptorMap.size)
        assertTrue(page.demoText.contains("<FUNCTION>make_point</FUNCTION>"))

        val source = page.demoText.replace(Regex("</?[A-Z_]+>"), "")
        val file = myFixture.configureByText(MojoFileType, source)
        val errors = PsiTreeUtil.findChildrenOfType(file, PsiErrorElement::class.java)
        assertTrue(errors.joinToString { "${it.errorDescription} at ${it.textOffset}" }, errors.isEmpty())
    }

    fun testDecoratorSigilUsesDecoratorColor() {
        assertEquals(
            MojoColor.DECORATOR.textAttributesKey,
            MojoSyntaxHighlighter().getTokenHighlights(MojoTypes.AT).single(),
        )
    }

    fun testCallColorsUseVisibleTolkFallbacks() {
        assertSame(
            DefaultLanguageHighlighterColors.FUNCTION_DECLARATION,
            MojoColor.FUNCTION_CALL.textAttributesKey.fallbackAttributeKey,
        )
        assertSame(
            DefaultLanguageHighlighterColors.INSTANCE_METHOD,
            MojoColor.METHOD_CALL.textAttributesKey.fallbackAttributeKey,
        )
    }

    fun testPlainAndParametricCallsAreHighlighted() {
        val source = """
            foo()
            Float64(_monotonic_nanoseconds()) / _NSEC_PER_SEC
            llvm_intrinsic["llvm.amdgcn.s.memrealtime", UInt64]()
            value.method()
            value.parametric_method[Int]()
        """.trimIndent()
        val file = myFixture.configureByText(MojoFileType, source)
        val errors = PsiTreeUtil.findChildrenOfType(file, PsiErrorElement::class.java)
        assertTrue(errors.joinToString { "${it.errorDescription} at ${it.textOffset}" }, errors.isEmpty())

        val highlights = myFixture.doHighlighting()
        assertColor(source, highlights, "foo", 0, MojoColor.FUNCTION_CALL)
        assertColor(source, highlights, "Float64", 0, MojoColor.BUILTIN_TYPE)
        assertColor(source, highlights, "_monotonic_nanoseconds", 0, MojoColor.FUNCTION_CALL)
        assertColor(source, highlights, "llvm_intrinsic", 0, MojoColor.FUNCTION_CALL)
        assertColor(source, highlights, "method", 0, MojoColor.METHOD_CALL)
        assertColor(source, highlights, "parametric_method", 0, MojoColor.METHOD_CALL)
    }

    fun testParametricConstructorKeepsStructColor() {
        val source = """
            struct Span[T, origin]:
                def __init__(out self, *, length: Int):
                    pass

            def make(dtype, origin):
                return Span[Scalar[dtype], origin](length=1)
        """.trimIndent()
        val file = myFixture.configureByText(MojoFileType, source)
        val errors = PsiTreeUtil.findChildrenOfType(file, PsiErrorElement::class.java)
        assertTrue(errors.joinToString { "${it.errorDescription} at ${it.textOffset}" }, errors.isEmpty())

        val highlights = myFixture.doHighlighting()
        assertColor(source, highlights, "Span", 0, MojoColor.STRUCT)
        assertColor(source, highlights, "Span", 1, MojoColor.STRUCT)
    }

    fun testBuiltinTypesUseKeywordColor() {
        val source = """
            def convert(value: Int32, text: String, custom: UserType) -> SIMD[DType.float64, 1]:
                return Float64(value)
        """.trimIndent()
        val file = myFixture.configureByText(MojoFileType, source)
        val errors = PsiTreeUtil.findChildrenOfType(file, PsiErrorElement::class.java)
        assertTrue(errors.joinToString { "${it.errorDescription} at ${it.textOffset}" }, errors.isEmpty())

        val highlights = myFixture.doHighlighting()
        assertColor(source, highlights, "Int32", 0, MojoColor.BUILTIN_TYPE)
        assertColor(source, highlights, "String", 0, MojoColor.BUILTIN_TYPE)
        assertColor(source, highlights, "SIMD", 0, MojoColor.BUILTIN_TYPE)
        assertColor(source, highlights, "DType", 0, MojoColor.BUILTIN_TYPE)
        assertColor(source, highlights, "Float64", 0, MojoColor.BUILTIN_TYPE)
        assertColor(source, highlights, "UserType", 0, MojoColor.TYPE_REFERENCE)
    }

    fun testResolvedImportsDriveReferenceColors() {
        myFixture.addFileToProject(
            "library.mojo",
            """
            def `fn`():
                pass

            struct Model:
                def __init__(out self):
                    pass

            comptime ANSWER = 42
            comptime Identifier = Int
            """.trimIndent(),
        )
        val source = """
            import library
            from library import `fn`, Model, ANSWER, Identifier
            from library import Model as ImportedModel

            library.`fn`()
            `fn`()
            var model: Model
            var imported: ImportedModel
            Model()
            var answer = ANSWER
            var identifier: Identifier
        """.trimIndent()
        val file = myFixture.configureByText(MojoFileType, source)
        val errors = PsiTreeUtil.findChildrenOfType(file, PsiErrorElement::class.java)
        assertTrue(errors.joinToString { "${it.errorDescription} at ${it.textOffset}" }, errors.isEmpty())

        val highlights = myFixture.doHighlighting()
        assertColor(source, highlights, "library", 3, MojoColor.MODULE)
        assertColor(source, highlights, "`fn`", 0, MojoColor.FUNCTION)
        assertColor(source, highlights, "`fn`", 1, MojoColor.FUNCTION_CALL)
        assertColor(source, highlights, "`fn`", 2, MojoColor.FUNCTION_CALL)
        assertColor(source, highlights, "Model", 0, MojoColor.STRUCT)
        assertColor(source, highlights, "Model", 1, MojoColor.STRUCT)
        assertColor(source, highlights, "Model", 2, MojoColor.STRUCT)
        assertColor(source, highlights, "Model", 3, MojoColor.STRUCT)
        assertColor(source, highlights, "ImportedModel", 0, MojoColor.STRUCT)
        assertColor(source, highlights, "ImportedModel", 1, MojoColor.STRUCT)
        assertColor(source, highlights, "ANSWER", 0, MojoColor.CONSTANT)
        assertColor(source, highlights, "ANSWER", 1, MojoColor.CONSTANT)
        assertColor(source, highlights, "Identifier", 0, MojoColor.TYPE_ALIAS)
        assertColor(source, highlights, "Identifier", 1, MojoColor.TYPE_REFERENCE)
    }

    fun testComptimeColorFollowsInferredValueCategory() {
        val source = """
            struct Progress:
                def advance(mut self):
                    comptime BLOCK = "▇"
                    comptime Element = Int
                    var value: Element = 1
                    print(BLOCK * value)
        """.trimIndent()
        myFixture.configureByText(MojoFileType, source)

        val highlights = myFixture.doHighlighting()
        assertColor(source, highlights, "BLOCK", 0, MojoColor.CONSTANT)
        assertColor(source, highlights, "BLOCK", 1, MojoColor.CONSTANT)
        assertColor(source, highlights, "Element", 0, MojoColor.TYPE_ALIAS)
        assertColor(source, highlights, "Element", 1, MojoColor.TYPE_REFERENCE)
    }

    fun testAssociatedComptimeTypeAndStructParametersUseSemanticColors() {
        val source = """
            struct StridedRange[dtype: DType = DType.int]:
                comptime Element = Scalar[Self.dtype]
                var element: Self.Element
        """.trimIndent()
        myFixture.configureByText(MojoFileType, source)

        val highlights = myFixture.doHighlighting()
        assertColor(source, highlights, "dtype", 0, MojoColor.TYPE_PARAMETER)
        assertColor(source, highlights, "dtype", 1, MojoColor.TYPE_PARAMETER)
        assertColor(source, highlights, "Element", 0, MojoColor.TYPE_ALIAS)
        assertColor(source, highlights, "Element", 1, MojoColor.TYPE_REFERENCE)
    }

    fun testNamedTypeAndCallableArgumentsUseKeywordArgumentColor() {
        val source = """
            def intrinsic[has_side_effect: Bool = True](value: Int, flag: Bool = False):
                pass

            def main(value: Int):
                intrinsic[has_side_effect=False](value, flag=False)
        """.trimIndent()
        myFixture.configureByText(MojoFileType, source)

        val highlights = myFixture.doHighlighting()
        assertColor(source, highlights, "has_side_effect", 1, MojoColor.KEYWORD_ARGUMENT)
        assertColor(source, highlights, "flag", 1, MojoColor.KEYWORD_ARGUMENT)
    }

    private fun assertColor(
        source: String,
        highlights: List<HighlightInfo>,
        text: String,
        occurrence: Int,
        expected: MojoColor,
    ) {
        val offset = occurrenceOffset(source, text, occurrence)
        assertTrue(
            "Expected $expected on `$text` at $offset, got: " +
                highlights.filter { it.startOffset == offset }.joinToString { it.forcedTextAttributesKey?.externalName ?: it.type.toString() },
            highlights.any {
                it.startOffset == offset &&
                    it.endOffset == offset + text.length &&
                    it.forcedTextAttributesKey == expected.textAttributesKey
            },
        )
    }

    private fun occurrenceOffset(source: String, text: String, occurrence: Int): Int {
        if (text.firstOrNull()?.isLetterOrDigit() == true && text.lastOrNull()?.isLetterOrDigit() == true) {
            val matches = Regex("\\b${Regex.escape(text)}\\b").findAll(source).toList()
            return matches.getOrNull(occurrence)?.range?.first
                ?: error("Cannot find occurrence $occurrence of `$text`")
        }

        var offset = -1
        repeat(occurrence + 1) {
            offset = source.indexOf(text, offset + 1)
            if (offset < 0) error("Cannot find occurrence $occurrence of `$text`")
        }
        return offset
    }
}
