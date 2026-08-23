package com.github.petrmakhnev.intellijmojo.lang.psi

import com.github.petrmakhnev.intellijmojo.lang.MojoFileType
import com.github.petrmakhnev.intellijmojo.lang.MojoLanguage
import com.intellij.extapi.psi.PsiFileBase
import com.intellij.psi.FileViewProvider

class MojoFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, MojoLanguage) {
    override fun getFileType() = MojoFileType

    val moduleName: String
        get() = if (name == PACKAGE_INITIALIZER) {
            virtualFile?.parent?.name.orEmpty()
        } else {
            name.removeSuffix(".${MojoFileType.defaultExtension}")
        }

    override fun toString(): String = "Mojo File"

    companion object {
        const val PACKAGE_INITIALIZER = "__init__.mojo"
    }
}
