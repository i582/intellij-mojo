package com.github.petrmakhnev.intellijmojo.lang.editor

import com.github.petrmakhnev.intellijmojo.lang.MojoFileType
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class MojoFoldingBuilderTest : BasePlatformTestCase() {
    fun testBuildsNestedSuiteAndDocstringRegions() {
        val source = """
            struct Counter:
                ""${'"'}Counter documentation.
                It spans multiple lines.
                ""${'"'}

                def value(self) -> Int:
                    if True:
                        return 1
                    else:
                        return 0
        """.trimIndent()
        val file = myFixture.configureByText(MojoFileType, source)
        val builder = MojoFoldingBuilder()
        val descriptors = builder.buildFoldRegions(file.node, myFixture.editor.document)
        val foldedText = descriptors.map { source.substring(it.range.startOffset, it.range.endOffset) }

        assertEquals(5, descriptors.size)
        assertTrue(descriptors.all { builder.getPlaceholderText(it.element, it.range) == "..." })
        assertTrue(foldedText.any { it.contains("def value") })
        assertTrue(foldedText.any { it.contains("if True") })
        assertTrue(foldedText.any { it.contains("return 1") && !it.contains("else:") })
        assertTrue(foldedText.any { it.contains("return 0") })
        assertTrue(foldedText.any { it.contains("Counter documentation") })
    }

    fun testFoldsConsecutiveImportsAsOneRegion() {
        val source = """
            import std
            from std.collections import List
            from std.math import sqrt

            def main():
                pass
        """.trimIndent()
        val file = myFixture.configureByText(MojoFileType, source)
        val builder = MojoFoldingBuilder()
        val descriptors = builder.buildFoldRegions(file.node, myFixture.editor.document)
        val importRegion = descriptors.single { descriptor ->
            source.substring(descriptor.range.startOffset, descriptor.range.endOffset).startsWith("import std")
        }

        assertEquals("import ...", builder.getPlaceholderText(importRegion.element, importRegion.range))
        assertEquals(
            "import std\nfrom std.collections import List\nfrom std.math import sqrt",
            source.substring(importRegion.range.startOffset, importRegion.range.endOffset),
        )
    }

    fun testSuiteFoldKeepsNextMethodOnItsOwnLine() {
        val source = """
            struct Interval:
                def first(self):
                    pass

                def second(self):
                    pass
        """.trimIndent()
        val file = myFixture.configureByText(MojoFileType, source)
        val descriptors = MojoFoldingBuilder().buildFoldRegions(file.node, myFixture.editor.document)
        val firstBodyStart = source.indexOf(':', source.indexOf("def first")) + 1
        val firstMethodRegion = descriptors.single { it.range.startOffset == firstBodyStart }

        assertEquals("\n\n    def second(self):\n        pass", source.substring(firstMethodRegion.range.endOffset))
        assertEquals("\n        pass", source.substring(firstMethodRegion.range.startOffset, firstMethodRegion.range.endOffset))
    }

    fun testDoesNotFoldSingleLineSuitesOrStrings() {
        val source = """
            def compact(): pass
            var text = "short"
        """.trimIndent()
        val file = myFixture.configureByText(MojoFileType, source)

        assertEmpty(MojoFoldingBuilder().buildFoldRegions(file.node, myFixture.editor.document))
    }

    fun testFoldsCollectionsAndSequentialComments() {
        val source = """
            # First line
            # Second line
            var values = [
                1,
                2,
            ]
        """.trimIndent()
        val file = myFixture.configureByText(MojoFileType, source)
        val descriptors = MojoFoldingBuilder().buildFoldRegions(file.node, myFixture.editor.document)
        val foldedText = descriptors.map { source.substring(it.range.startOffset, it.range.endOffset) }

        assertEquals(2, descriptors.size)
        assertTrue(foldedText.any { it == "First line\n# Second line" })
        assertTrue(foldedText.any { it.contains("1") && it.contains("2") })
    }
}
