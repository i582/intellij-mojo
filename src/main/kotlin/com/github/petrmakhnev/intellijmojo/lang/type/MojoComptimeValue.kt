package com.github.petrmakhnev.intellijmojo.lang.type

import com.intellij.psi.PsiElement
import java.math.BigInteger

sealed interface MojoComptimeValue {
    data class Integer(val value: BigInteger) : MojoComptimeValue

    class ParameterRef(
        val declaration: PsiElement,
        val sourceName: String,
    ) : MojoComptimeValue {
        override fun equals(other: Any?): Boolean =
            other is ParameterRef && declaration == other.declaration

        override fun hashCode(): Int = declaration.hashCode()

        override fun toString(): String = "ParameterRef($sourceName)"
    }

    data class Unary(
        val operator: String,
        val operand: MojoComptimeValue,
    ) : MojoComptimeValue

    data class Binary(
        val left: MojoComptimeValue,
        val operator: String,
        val right: MojoComptimeValue,
    ) : MojoComptimeValue

    data class Conversion(
        val name: String,
        val operand: MojoComptimeValue,
    ) : MojoComptimeValue

    data class Opaque(val source: String) : MojoComptimeValue
}

internal fun MojoComptimeValue.rebind(substitution: Map<PsiElement, MojoGenericArg>): MojoComptimeValue =
    rebind(substitution, emptySet())

internal fun MojoComptimeValue.normalized(): MojoComptimeValue = rebind(emptyMap())

private fun MojoComptimeValue.rebind(
    substitution: Map<PsiElement, MojoGenericArg>,
    visiting: Set<PsiElement>,
): MojoComptimeValue =
    when (this) {
        is MojoComptimeValue.Integer, is MojoComptimeValue.Opaque -> this
        is MojoComptimeValue.ParameterRef -> {
            if (declaration in visiting) {
                this
            } else {
                substitution[declaration]
                    ?.asComptimeValue()
                    ?.rebind(substitution, visiting + declaration)
                    ?: this
            }
        }
        is MojoComptimeValue.Unary -> foldUnary(operator, operand.rebind(substitution, visiting))
        is MojoComptimeValue.Binary -> foldBinary(
            left.rebind(substitution, visiting),
            operator,
            right.rebind(substitution, visiting),
        )
        is MojoComptimeValue.Conversion -> foldConversion(name, operand.rebind(substitution, visiting))
    }

internal fun MojoComptimeValue.render(): String = render(0)

internal fun parseComptimeInteger(text: String): MojoComptimeValue.Integer? {
    val normalized = text.replace("_", "")
    val sign = when {
        normalized.startsWith("-") -> -1
        normalized.startsWith("+") -> 1
        else -> 1
    }
    val unsigned = normalized.removePrefix("-").removePrefix("+")
    val (radix, digits) = when {
        unsigned.startsWith("0x", ignoreCase = true) -> 16 to unsigned.drop(2)
        unsigned.startsWith("0b", ignoreCase = true) -> 2 to unsigned.drop(2)
        unsigned.startsWith("0o", ignoreCase = true) -> 8 to unsigned.drop(2)
        else -> 10 to unsigned
    }
    if (digits.isEmpty()) return null
    return runCatching { BigInteger(digits, radix).multiply(BigInteger.valueOf(sign.toLong())) }
        .getOrNull()
        ?.let { MojoComptimeValue.Integer(it) }
}

private fun MojoGenericArg.asComptimeValue(): MojoComptimeValue? = when (this) {
    is MojoGenericArg.Value -> semanticComptimeValue()
    is MojoGenericArg.Type -> MojoComptimeValue.Opaque(type.displayName)
    is MojoGenericArg.Hole, MojoGenericArg.Unknown -> null
}

internal fun MojoGenericArg.Value.semanticComptimeValue(): MojoComptimeValue =
    comptimeValue
        ?: declaration?.let { MojoComptimeValue.ParameterRef(it, displayName) }
        ?: parseComptimeInteger(displayName)
        ?: MojoComptimeValue.Opaque(displayName)

internal fun MojoGenericArg.Value.sameComptimeValue(other: MojoGenericArg.Value): Boolean =
    semanticComptimeValue() == other.semanticComptimeValue()

internal fun MojoGenericArg.Value.isComptimeInteger(value: Long): Boolean =
    (semanticComptimeValue() as? MojoComptimeValue.Integer)?.value == BigInteger.valueOf(value)

private fun foldUnary(operator: String, operand: MojoComptimeValue): MojoComptimeValue {
    val integer = (operand as? MojoComptimeValue.Integer)?.value
        ?: return MojoComptimeValue.Unary(operator, operand)
    val value = when (operator) {
        "+" -> integer
        "-" -> integer.negate()
        "~" -> integer.not()
        else -> return MojoComptimeValue.Unary(operator, operand)
    }
    return MojoComptimeValue.Integer(value)
}

private fun foldBinary(
    left: MojoComptimeValue,
    operator: String,
    right: MojoComptimeValue,
): MojoComptimeValue {
    val leftInteger = (left as? MojoComptimeValue.Integer)?.value
        ?: return MojoComptimeValue.Binary(left, operator, right)
    val rightInteger = (right as? MojoComptimeValue.Integer)?.value
        ?: return MojoComptimeValue.Binary(left, operator, right)
    val value = when (operator) {
        "+" -> leftInteger + rightInteger
        "-" -> leftInteger - rightInteger
        "*" -> leftInteger * rightInteger
        "/" -> if (rightInteger == BigInteger.ZERO) null else leftInteger / rightInteger
        "//" -> if (rightInteger == BigInteger.ZERO) null else floorDivMod(leftInteger, rightInteger).first
        "%" -> if (rightInteger == BigInteger.ZERO) null else floorDivMod(leftInteger, rightInteger).second
        "<<" -> rightInteger.toShiftCount(leftInteger.bitLength())?.let(leftInteger::shiftLeft)
        ">>" -> rightInteger.toShiftCount(0)?.let(leftInteger::shiftRight)
        "&" -> leftInteger.and(rightInteger)
        "|" -> leftInteger.or(rightInteger)
        "^" -> leftInteger.xor(rightInteger)
        "**" -> rightInteger.toExponent(leftInteger.bitLength())?.let(leftInteger::pow)
        else -> null
    } ?: return MojoComptimeValue.Binary(left, operator, right)
    if (value.bitLength() > MAX_FOLDED_INTEGER_BITS) {
        return MojoComptimeValue.Binary(left, operator, right)
    }
    return MojoComptimeValue.Integer(value)
}

private fun floorDivMod(left: BigInteger, right: BigInteger): Pair<BigInteger, BigInteger> {
    val (quotient, remainder) = left.divideAndRemainder(right)
    return if (remainder != BigInteger.ZERO && left.signum() != right.signum()) {
        quotient - BigInteger.ONE to remainder + right
    } else {
        quotient to remainder
    }
}

private fun foldConversion(name: String, operand: MojoComptimeValue): MojoComptimeValue =
    if (name in MOJO_COMPTIME_INTEGER_CONVERSIONS && operand is MojoComptimeValue.Integer) {
        operand
    } else {
        MojoComptimeValue.Conversion(name, operand)
    }

private fun BigInteger.toShiftCount(baseBitLength: Int): Int? =
    takeIf { signum() >= 0 && bitLength() < Int.SIZE_BITS }
        ?.toInt()
        ?.takeIf { it <= MAX_FOLDED_SHIFT && baseBitLength.toLong() + it <= MAX_FOLDED_INTEGER_BITS }

private fun BigInteger.toExponent(baseBitLength: Int): Int? =
    takeIf { signum() >= 0 && bitLength() < Int.SIZE_BITS }
        ?.toInt()
        ?.takeIf {
            it <= MAX_FOLDED_EXPONENT && baseBitLength.toLong().coerceAtLeast(1) * it <= MAX_FOLDED_INTEGER_BITS
        }

private fun MojoComptimeValue.render(parentPrecedence: Int): String = when (this) {
    is MojoComptimeValue.Integer -> value.toString()
    is MojoComptimeValue.ParameterRef -> sourceName
    is MojoComptimeValue.Opaque -> source
    is MojoComptimeValue.Conversion -> "$name(${operand.render()})"
    is MojoComptimeValue.Unary -> {
        val precedence = 80
        val rendered = "$operator${operand.render(precedence)}"
        rendered.parenthesizeIf(precedence < parentPrecedence)
    }
    is MojoComptimeValue.Binary -> {
        val precedence = BINARY_PRECEDENCE[operator] ?: 0
        val rendered = "${left.render(precedence)} $operator ${right.render(precedence + 1)}"
        rendered.parenthesizeIf(precedence < parentPrecedence)
    }
}

private fun String.parenthesizeIf(condition: Boolean): String = if (condition) "($this)" else this

internal val MOJO_COMPTIME_INTEGER_CONVERSIONS = setOf("Int", "UInt", "SIMDLength")

private const val MAX_FOLDED_SHIFT = 65_536
private const val MAX_FOLDED_EXPONENT = 4_096
private const val MAX_FOLDED_INTEGER_BITS = 65_536

private val BINARY_PRECEDENCE = mapOf(
    "|" to 10,
    "^" to 20,
    "&" to 30,
    "<<" to 40,
    ">>" to 40,
    "+" to 50,
    "-" to 50,
    "*" to 60,
    "/" to 60,
    "//" to 60,
    "%" to 60,
    "**" to 70,
)
