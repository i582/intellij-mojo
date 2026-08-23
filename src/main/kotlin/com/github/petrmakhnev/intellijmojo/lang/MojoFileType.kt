package com.github.petrmakhnev.intellijmojo.lang

import com.github.petrmakhnev.intellijmojo.icons.MojoIcons
import com.intellij.openapi.fileTypes.LanguageFileType
import javax.swing.Icon

object MojoFileType : LanguageFileType(MojoLanguage) {
    override fun getName(): String = "Mojo"

    override fun getDescription(): String = "Mojo source file"

    override fun getDefaultExtension(): String = "mojo"

    override fun getIcon(): Icon = MojoIcons.FILE
}
