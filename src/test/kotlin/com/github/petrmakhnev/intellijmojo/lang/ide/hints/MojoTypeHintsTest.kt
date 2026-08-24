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

    fun testShowsLocalComptimeValueAndTypeTypes() {
        doTestProvider(
            "comptime-hints.mojo",
            """
            def scales(source):
                comptime SCALE/*<# : |Float32 #>*/ = Float32(2.3283064e-10)
                comptime SCALE_HALF/*<# : |Float32 #>*/ = SCALE * Float32(0.5)
                comptime Element/*<# : |type[Int] #>*/ = Int
                comptime EXPLICIT: Float32 = Float32(1.0)
                comptime UNKNOWN = source
            """.trimIndent(),
            MojoTypeHintsProvider(),
            mapOf("hint.type.variable" to true),
        )
    }

    fun testShowsScalarAliasForPartiallySpecializedSimdType() {
        doTestProvider(
            "scalar-alias-hints.mojo",
            """
            struct DType:
                comptime float32 = DType()
                comptime uint64 = DType()

            struct SIMD[dtype: DType, length: Int]:
                def __init__[T](out self/*<# : |SIMD #>*/, value: T):
                    pass

            comptime Scalar = SIMD[
                _, length=__mlir_attr[`#lit.struct<{_mlir_value = 1}> : `, SIMDLength]
            ]
            comptime Float32 = Scalar[DType.float32]
            comptime UInt64 = Scalar[DType.uint64]

            def scales(pi: Float32):
                comptime SCALE/*<# : |Float32 #>*/ = Float32(1.0) / Float32(UInt64(1) << 32)
                comptime SCALE_2PI/*<# : |Float32 #>*/ = SCALE * Float32(2.0) * Float32(pi)
                comptime SCALE_HALF/*<# : |Float32 #>*/ = SCALE * Float32(0.5)
                comptime SCALE_2PI_HALF/*<# : |Float32 #>*/ = SCALE_2PI * Float32(0.5)
            """.trimIndent(),
            MojoTypeHintsProvider(),
            mapOf("hint.type.variable" to true),
        )
    }

    fun testSkipsIncompleteComptimeInitializer() {
        doTestProvider(
            "incomplete-comptime-hints.mojo",
            """
            def incomplete(source):
                comptime VALID/*<# : |Float32 #>*/ = Float32(1.0)
                comptime VALUE = source +
            """.trimIndent(),
            MojoTypeHintsProvider(),
            mapOf("hint.type.variable" to true),
        )
    }
}
