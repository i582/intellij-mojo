package com.github.petrmakhnev.intellijmojo.lang.psi

import com.github.petrmakhnev.intellijmojo.lang.MojoFileType
import com.github.petrmakhnev.intellijmojo.lang.MojoLanguage
import com.intellij.extapi.psi.PsiFileBase
import com.intellij.psi.FileViewProvider

class MojoFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, MojoLanguage) {
    override fun getFileType() = MojoFileType

    override fun toString(): String = "Mojo File"
}
