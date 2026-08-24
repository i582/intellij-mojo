package com.github.petrmakhnev.intellijmojo.lang.type

import com.intellij.psi.PsiElement
import java.util.LinkedHashMap

internal class MojoFlowContext(
    private val values: MutableMap<PsiElement, MojoValue> = LinkedHashMap(),
) {
    constructor(other: MojoFlowContext) : this(LinkedHashMap(other.values))

    operator fun get(symbol: PsiElement): MojoValue? = values[symbol]

    operator fun set(symbol: PsiElement, value: MojoValue) {
        values[symbol] = value
    }

    fun copy(): MojoFlowContext = MojoFlowContext(this)

    fun join(other: MojoFlowContext): MojoFlowContext {
        val result = MojoFlowContext(this)
        for ((symbol, otherValue) in other.values) {
            val current = result.values[symbol]
            result.values[symbol] = when {
                current == null -> otherValue
                current == otherValue -> current
                else -> MojoValue(current.type.join(otherValue.type), current.category)
            }
        }
        return result
    }
}

internal fun MojoTy.join(other: MojoTy): MojoTy {
    if (this == other) return this
    if (this == MojoTy.Unknown) return other
    if (other == MojoTy.Unknown) return this

    val variants = buildList {
        if (this@join is MojoTy.Union) addAll(this@join.variants) else add(this@join)
        if (other is MojoTy.Union) addAll(other.variants) else add(other)
    }.distinct()
    return MojoTy.Union(variants)
}
