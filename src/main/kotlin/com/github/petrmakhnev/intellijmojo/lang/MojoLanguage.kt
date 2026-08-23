package com.github.petrmakhnev.intellijmojo.lang

import com.intellij.lang.Language

object MojoLanguage : Language("Mojo") {
    private fun readResolve(): Any = MojoLanguage
}
