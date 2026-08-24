package com.github.petrmakhnev.intellijmojo.lang.ide.hints

import com.intellij.testFramework.utils.inlays.declarative.DeclarativeInlayHintsProviderTestCase

class MojoTypeHintsTest : DeclarativeInlayHintsProviderTestCase() {
    fun testShowsKnownLocalTypesAndSkipsExplicitOrUnknownTypes() {
        doTestProvider(
            "hints.mojo",
            """
            struct Point:
                def reset(mut self/*<# : |Point #>*/):
                    print(self)

            def main(source):
                var text/*<# : |String #>*/ = String()
                var numbers/*<# : |List[Int] #>*/ = [1, 2]
                var explicit: Int = 1
                var unknown = source
                text += "!"
                for number/*<# : |Int #>*/ in numbers:
                    print(number)
            """.trimIndent(),
            MojoTypeHintsProvider(),
            mapOf("hint.type.variable" to true),
        )
    }
}
