package com.github.petrmakhnev.intellijmojo.lang.lexer

import com.github.petrmakhnev.intellijmojo.lang.MojoLanguage
import com.intellij.psi.tree.IElementType

class MojoTokenType(debugName: String) : IElementType(debugName, MojoLanguage)

