package com.github.petrmakhnev.intellijmojo.lang.type

import com.github.petrmakhnev.intellijmojo.lang.psi.MojoFunctionDeclaration
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoStructDeclaration
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoTraitDeclaration
import com.intellij.psi.PsiElement

sealed interface MojoTy {
    data object Unknown : MojoTy

    data object Never : MojoTy

    data object None : MojoTy

    data class Builtin(
        val name: String,
        val arguments: List<MojoTy> = emptyList(),
    ) : MojoTy

    data class Struct(
        val declaration: MojoStructDeclaration,
        val arguments: List<MojoTy> = emptyList(),
    ) : MojoTy

    data class Trait(
        val declaration: MojoTraitDeclaration,
        val arguments: List<MojoTy> = emptyList(),
    ) : MojoTy

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
    get() = when (this) {
        MojoTy.Unknown -> "<unknown>"
        MojoTy.Never -> "Never"
        MojoTy.None -> "NoneType"
        is MojoTy.Builtin -> parameterizedName(name, arguments)
        is MojoTy.Struct -> parameterizedName(declaration.name ?: "<anonymous struct>", arguments)
        is MojoTy.Trait -> parameterizedName(declaration.name ?: "<anonymous trait>", arguments)
        is MojoTy.TypeParameter -> declaration.text.removeSurrounding("`")
        is MojoTy.Tuple -> elements.joinToString(prefix = "(", postfix = ")") { it.displayName }
        is MojoTy.Union -> variants.joinToString(" | ") { it.displayName }
        is MojoTy.Function -> parameters.joinToString(prefix = "def(", postfix = ") -> ${returnType.displayName}") {
            it.type.displayName
        }
        is MojoTy.Overload -> "<overload ${candidates.size}>"
        is MojoTy.TypeValue -> "type[${instanceType.displayName}]"
        is MojoTy.Module -> declaration.containingFile?.name ?: declaration.text
    }

private fun parameterizedName(name: String, arguments: List<MojoTy>): String =
    if (arguments.isEmpty()) name else arguments.joinToString(prefix = "$name[", postfix = "]") { it.displayName }

internal fun MojoTy.substitute(substitution: Map<PsiElement, MojoTy>): MojoTy = when (this) {
    is MojoTy.TypeParameter -> substitution[declaration] ?: this
    is MojoTy.Builtin -> copy(arguments = arguments.map { it.substitute(substitution) })
    is MojoTy.Struct -> copy(arguments = arguments.map { it.substitute(substitution) })
    is MojoTy.Trait -> copy(arguments = arguments.map { it.substitute(substitution) })
    is MojoTy.Tuple -> copy(elements = elements.map { it.substitute(substitution) })
    is MojoTy.Union -> copy(variants = variants.map { it.substitute(substitution) })
    is MojoTy.Function -> copy(
        parameters = parameters.map { it.copy(type = it.type.substitute(substitution)) },
        returnType = returnType.substitute(substitution),
    )
    is MojoTy.Overload -> copy(candidates = candidates.map { it.substitute(substitution) as MojoTy.Function })
    is MojoTy.TypeValue -> copy(instanceType = instanceType.substitute(substitution))
    else -> this
}

internal fun MojoTy.materialized(): MojoTy = when (this) {
    is MojoTy.Builtin -> when (name) {
        "IntLiteral" -> MojoTy.Builtin("Int")
        "FloatLiteral" -> MojoTy.Builtin("Float64")
        "StringLiteral" -> MojoTy.Builtin("String")
        else -> copy(arguments = arguments.map { it.materialized() })
    }
    is MojoTy.Struct -> copy(arguments = arguments.map { it.materialized() })
    is MojoTy.Trait -> copy(arguments = arguments.map { it.materialized() })
    is MojoTy.Tuple -> copy(elements = elements.map { it.materialized() })
    is MojoTy.Union -> copy(variants = variants.map { it.materialized() })
    else -> this
}

internal fun MutableMap<PsiElement, MojoTy>.deduce(expected: MojoTy, actual: MojoTy) {
    when (expected) {
        is MojoTy.TypeParameter -> putIfAbsent(expected.declaration, actual)
        is MojoTy.Builtin -> deduceArguments(expected.arguments, (actual as? MojoTy.Builtin)?.arguments)
        is MojoTy.Struct -> deduceArguments(expected.arguments, (actual as? MojoTy.Struct)?.arguments)
        is MojoTy.Trait -> deduceArguments(expected.arguments, (actual as? MojoTy.Trait)?.arguments)
        is MojoTy.Tuple -> deduceArguments(expected.elements, (actual as? MojoTy.Tuple)?.elements)
        is MojoTy.Function -> {
            val actualFunction = actual as? MojoTy.Function ?: return
            deduceArguments(expected.parameters.map { it.type }, actualFunction.parameters.map { it.type })
            deduce(expected.returnType, actualFunction.returnType)
        }
        else -> Unit
    }
}

private fun MutableMap<PsiElement, MojoTy>.deduceArguments(
    expected: List<MojoTy>,
    actual: List<MojoTy>?,
) {
    if (actual == null || expected.size != actual.size) return
    expected.indices.forEach { index -> deduce(expected[index], actual[index]) }
}
