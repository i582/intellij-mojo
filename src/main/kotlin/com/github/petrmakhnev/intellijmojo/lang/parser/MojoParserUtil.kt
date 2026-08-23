package com.github.petrmakhnev.intellijmojo.lang.parser

import com.intellij.lang.PsiBuilder
import com.intellij.lang.parser.GeneratedParserUtilBase

object MojoParserUtil : GeneratedParserUtilBase() {
    // Precedence 3 keeps a comprehension's following `if` outside its range.
    @JvmStatic
    fun parseComprehensionExpression(builder: PsiBuilder, level: Int): Boolean =
        MojoParser.Expression(builder, level, 3)
}
