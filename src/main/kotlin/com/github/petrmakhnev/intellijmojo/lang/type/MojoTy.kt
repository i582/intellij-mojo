package com.github.petrmakhnev.intellijmojo.lang.type

import com.github.petrmakhnev.intellijmojo.lang.psi.MojoFunctionDeclaration
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoParameterDeclaration
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoStructDeclaration
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoTraitDeclaration
import com.intellij.psi.PsiElement

sealed interface MojoTy {
    data object Unknown : MojoTy

    data object Never : MojoTy

    data object None : MojoTy

    data class Builtin(
        val name: String,
        val genericArguments: List<MojoGenericArg> = emptyList(),
    ) : MojoTy {
        val arguments: List<MojoTy>
            get() = genericArguments.map(MojoGenericArg::typeProjection)
    }

    data class Struct(
        val declaration: MojoStructDeclaration,
        val genericArguments: List<MojoGenericArg> = emptyList(),
    ) : MojoTy {
        val arguments: List<MojoTy>
            get() = genericArguments.map(MojoGenericArg::typeProjection)
    }

    data class Trait(
        val declaration: MojoTraitDeclaration,
        val genericArguments: List<MojoGenericArg> = emptyList(),
    ) : MojoTy {
        val arguments: List<MojoTy>
            get() = genericArguments.map(MojoGenericArg::typeProjection)
    }

    data class TypeParameter(val declaration: PsiElement) : MojoTy

    data class Tuple(val elements: List<MojoTy>) : MojoTy

    data class Union(val variants: List<MojoTy>) : MojoTy

    data class Function(
        val parameters: List<MojoCallableParameter>,
        val returnType: MojoTy,
        val declaration: MojoFunctionDeclaration? = null,
        val typeParameters: List<PsiElement> = emptyList(),
    ) : MojoTy

    data class Overload(val candidates: List<Function>) : MojoTy

    data class TypeValue(val instanceType: MojoTy) : MojoTy

    data class Module(val declaration: PsiElement) : MojoTy
}

sealed interface MojoGenericArg {
    data class Type(val type: MojoTy) : MojoGenericArg

    data class Value(
        val type: MojoTy,
        val displayName: String,
        val declaration: PsiElement? = null,
        val comptimeValue: MojoComptimeValue? = null,
    ) : MojoGenericArg {
        override fun equals(other: Any?): Boolean =
            this === other || other is Value && sameComptimeValue(other)

        override fun hashCode(): Int = semanticComptimeValue().hashCode()
    }

    data class Hole(val parameter: PsiElement? = null) : MojoGenericArg

    data object Unknown : MojoGenericArg
}

private val MojoGenericArg.typeProjection: MojoTy
    get() = when (this) {
        is MojoGenericArg.Type -> type
        is MojoGenericArg.Value -> type
        is MojoGenericArg.Hole, MojoGenericArg.Unknown -> MojoTy.Unknown
    }

data class MojoCallableParameter(
    val declaration: PsiElement,
    val name: String?,
    val type: MojoTy,
    val convention: MojoParameterConvention,
    val variadic: Boolean,
    val hasDefault: Boolean,
)

enum class MojoParameterConvention {
    DEFAULT,
    READ,
    OWNED,
    IMM,
    MUT,
    REF,
    VAR,
    OUT,
    DEINIT,
}

enum class MojoValueCategory {
    UNKNOWN,
    RVALUE,
    IMMUTABLE_LVALUE,
    MUTABLE_LVALUE,
    PARAMETER,
    TYPE,
    MODULE,
}

data class MojoValue(
    val type: MojoTy,
    val category: MojoValueCategory = MojoValueCategory.RVALUE,
) {
    companion object {
        val Unknown = MojoValue(MojoTy.Unknown, MojoValueCategory.UNKNOWN)
    }
}

val MojoTy.displayName: String
    get() = scalarAliasName() ?: when (this) {
        MojoTy.Unknown -> "<unknown>"
        MojoTy.Never -> "Never"
        MojoTy.None -> "NoneType"
        is MojoTy.Builtin -> parameterizedName(name, genericArguments)
        is MojoTy.Struct -> parameterizedName(declaration.name ?: "<anonymous struct>", genericArguments)
        is MojoTy.Trait -> parameterizedName(declaration.name ?: "<anonymous trait>", genericArguments)
        is MojoTy.TypeParameter -> declaration.typeParameterName()
        is MojoTy.Tuple -> elements.joinToString(prefix = "(", postfix = ")") { it.displayName }
        is MojoTy.Union -> variants.joinToString(" | ") { it.displayName }
        is MojoTy.Function -> parameters.joinToString(prefix = "def(", postfix = ") -> ${returnType.displayName}") {
            it.type.displayName
        }
        is MojoTy.Overload -> "<overload ${candidates.size}>"
        is MojoTy.TypeValue -> "type[${instanceType.displayName}]"
        is MojoTy.Module -> declaration.containingFile?.name ?: declaration.text
    }

private fun MojoTy.scalarAliasName(): String? {
    val (name, arguments) = when (this) {
        is MojoTy.Builtin -> name to genericArguments
        is MojoTy.Struct -> declaration.name to genericArguments
        else -> return null
    }
    if (name != "SIMD" || arguments.size != 2) return null
    val dtype = (arguments[0] as? MojoGenericArg.Value)?.displayName ?: return null
    val length = (arguments[1] as? MojoGenericArg.Value)?.displayName ?: return null
    if (length != "1") return null
    return SCALAR_TYPE_ALIASES[dtype]
}

private val SCALAR_TYPE_ALIASES = mapOf(
    "DType.int" to "Int",
    "DType.int8" to "Int8",
    "DType.uint8" to "UInt8",
    "DType.int16" to "Int16",
    "DType.uint16" to "UInt16",
    "DType.int32" to "Int32",
    "DType.uint32" to "UInt32",
    "DType.int64" to "Int64",
    "DType.uint64" to "UInt64",
    "DType.int128" to "Int128",
    "DType.uint128" to "UInt128",
    "DType.int256" to "Int256",
    "DType.uint256" to "UInt256",
    "DType.float4_e2m1fn" to "Float4_e2m1fn",
    "DType.float8_e5m2" to "Float8_e5m2",
    "DType.float8_e5m2fnuz" to "Float8_e5m2fnuz",
    "DType.float8_e4m3fn" to "Float8_e4m3fn",
    "DType.float8_e4m3fnuz" to "Float8_e4m3fnuz",
    "DType.float8_e8m0fnu" to "Float8_e8m0fnu",
    "DType.bfloat16" to "BFloat16",
    "DType.float16" to "Float16",
    "DType.float32" to "Float32",
    "DType.float64" to "Float64",
    "DType.uint" to "UInt",
)

private fun parameterizedName(name: String, arguments: List<MojoGenericArg>): String =
    if (arguments.isEmpty()) name else arguments.joinToString(prefix = "$name[", postfix = "]") {
        when (it) {
            is MojoGenericArg.Type -> it.type.displayName
            is MojoGenericArg.Value -> it.displayName
            is MojoGenericArg.Hole -> "_"
            MojoGenericArg.Unknown -> "<unknown>"
        }
    }

internal fun MojoTy.substitute(substitution: Map<PsiElement, MojoGenericArg>): MojoTy =
    substitute(substitution, emptySet())

private fun MojoTy.substitute(
    substitution: Map<PsiElement, MojoGenericArg>,
    visiting: Set<PsiElement>,
): MojoTy = when (this) {
    is MojoTy.TypeParameter -> {
        val replacement = substitution[declaration]
        when {
            replacement == null || declaration in visiting -> this
            replacement is MojoGenericArg.Type -> replacement.type.substitute(substitution, visiting + declaration)
            else -> replacement.typeProjection.substitute(substitution, visiting + declaration)
        }
    }
    is MojoTy.Builtin -> copy(
        genericArguments = genericArguments.map { it.substitute(substitution, visiting) },
    )
    is MojoTy.Struct -> copy(
        genericArguments = genericArguments.map { it.substitute(substitution, visiting) },
    )
    is MojoTy.Trait -> copy(
        genericArguments = genericArguments.map { it.substitute(substitution, visiting) },
    )
    is MojoTy.Tuple -> copy(elements = elements.map { it.substitute(substitution, visiting) })
    is MojoTy.Union -> copy(variants = variants.map { it.substitute(substitution, visiting) })
    is MojoTy.Function -> copy(
        parameters = parameters.map { it.copy(type = it.type.substitute(substitution, visiting)) },
        returnType = returnType.substitute(substitution, visiting),
    )
    is MojoTy.Overload -> copy(
        candidates = candidates.map { it.substitute(substitution, visiting) as MojoTy.Function },
    )
    is MojoTy.TypeValue -> copy(instanceType = instanceType.substitute(substitution, visiting))
    else -> this
}

private fun MojoGenericArg.substitute(
    substitution: Map<PsiElement, MojoGenericArg>,
    visiting: Set<PsiElement>,
): MojoGenericArg = when (this) {
    is MojoGenericArg.Type -> copy(type = type.substitute(substitution, visiting))
    is MojoGenericArg.Value -> {
        val replacement = declaration?.takeUnless { it in visiting }?.let(substitution::get)
        if (replacement != null && replacement != this) {
            replacement.substitute(substitution, visiting + declaration!!)
        } else {
            val reboundValue = comptimeValue?.rebind(substitution)
            copy(
                type = type.substitute(substitution, visiting),
                displayName = reboundValue?.render() ?: displayName,
                comptimeValue = reboundValue,
            )
        }
    }
    is MojoGenericArg.Hole -> {
        val replacement = parameter?.takeUnless { it in visiting }?.let(substitution::get)
        if (replacement != null && replacement != this) {
            replacement.substitute(substitution, visiting + parameter!!)
        } else {
            this
        }
    }
    MojoGenericArg.Unknown -> this
}

private fun PsiElement.typeParameterName(): String =
    ((this as? MojoParameterDeclaration)?.nameIdentifier?.text ?: text).removeSurrounding("`")

internal fun MojoTy.materialized(): MojoTy = when (this) {
    is MojoTy.Builtin -> when (name) {
        "IntLiteral" -> MojoTy.Builtin("Int")
        "FloatLiteral" -> MojoTy.Builtin("Float64")
        "StringLiteral" -> MojoTy.Builtin("String")
        else -> copy(genericArguments = genericArguments.map { it.materialized() })
    }
    is MojoTy.Struct -> copy(genericArguments = genericArguments.map { it.materialized() })
    is MojoTy.Trait -> copy(genericArguments = genericArguments.map { it.materialized() })
    is MojoTy.Tuple -> copy(elements = elements.map { it.materialized() })
    is MojoTy.Union -> copy(variants = variants.map { it.materialized() })
    else -> this
}

private fun MojoGenericArg.materialized(): MojoGenericArg = when (this) {
    is MojoGenericArg.Type -> copy(type = type.materialized())
    is MojoGenericArg.Value -> copy(type = type.materialized())
    is MojoGenericArg.Hole, MojoGenericArg.Unknown -> this
}

internal fun MutableMap<PsiElement, MojoGenericArg>.deduce(expected: MojoTy, actual: MojoTy) {
    when (expected) {
        is MojoTy.TypeParameter -> putIfAbsent(expected.declaration, MojoGenericArg.Type(actual))
        is MojoTy.Builtin -> deduceGenericArguments(
            expected.genericArguments,
            (actual as? MojoTy.Builtin)?.genericArguments,
        )
        is MojoTy.Struct -> deduceGenericArguments(
            expected.genericArguments,
            (actual as? MojoTy.Struct)?.genericArguments,
        )
        is MojoTy.Trait -> deduceGenericArguments(
            expected.genericArguments,
            (actual as? MojoTy.Trait)?.genericArguments,
        )
        is MojoTy.Tuple -> deduceArguments(expected.elements, (actual as? MojoTy.Tuple)?.elements)
        is MojoTy.Function -> {
            val actualFunction = actual as? MojoTy.Function ?: return
            deduceArguments(expected.parameters.map { it.type }, actualFunction.parameters.map { it.type })
            deduce(expected.returnType, actualFunction.returnType)
        }
        else -> Unit
    }
}

private fun MutableMap<PsiElement, MojoGenericArg>.deduceArguments(
    expected: List<MojoTy>,
    actual: List<MojoTy>?,
) {
    if (actual == null || expected.size != actual.size) return
    expected.indices.forEach { index -> deduce(expected[index], actual[index]) }
}

private fun MutableMap<PsiElement, MojoGenericArg>.deduceGenericArguments(
    expected: List<MojoGenericArg>,
    actual: List<MojoGenericArg>?,
) {
    if (actual == null || expected.size != actual.size) return
    expected.indices.forEach { index ->
        val expectedArgument = expected[index]
        val actualArgument = actual[index]
        when (expectedArgument) {
            is MojoGenericArg.Type -> deduce(expectedArgument.type, actualArgument.typeProjection)
            is MojoGenericArg.Value -> {
                (expectedArgument.declaration as? MojoParameterDeclaration)?.let {
                    putIfAbsent(it, actualArgument)
                }
            }
            is MojoGenericArg.Hole -> expectedArgument.parameter?.let { putIfAbsent(it, actualArgument) }
            MojoGenericArg.Unknown -> Unit
        }
    }
}
