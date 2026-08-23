package com.github.petrmakhnev.intellijmojo.lang.psi

import com.github.petrmakhnev.intellijmojo.lang.stub.type.MojoComptimeStubElementType
import com.github.petrmakhnev.intellijmojo.lang.stub.type.MojoFunctionStubElementType
import com.github.petrmakhnev.intellijmojo.lang.stub.type.MojoMlirRegionStubElementType
import com.github.petrmakhnev.intellijmojo.lang.stub.type.MojoStructStubElementType
import com.github.petrmakhnev.intellijmojo.lang.stub.type.MojoTraitStubElementType
import com.intellij.psi.stubs.IStubElementType

object MojoElementTypeFactory {
    @JvmStatic
    fun stubFactory(name: String): IStubElementType<*, *> = when (name) {
        "COMPTIME_STATEMENT" -> MojoComptimeStubElementType(name)
        "FUNCTION_DECLARATION" -> MojoFunctionStubElementType(name)
        "STRUCT_DECLARATION" -> MojoStructStubElementType(name)
        "TRAIT_DECLARATION" -> MojoTraitStubElementType(name)
        "MLIR_REGION_DECLARATION" -> MojoMlirRegionStubElementType(name)
        else -> error("Unknown stub element type: $name")
    }
}
