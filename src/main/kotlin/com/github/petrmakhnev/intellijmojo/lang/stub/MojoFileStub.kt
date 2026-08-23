package com.github.petrmakhnev.intellijmojo.lang.stub

import com.github.petrmakhnev.intellijmojo.lang.MojoFileElementType
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoFile
import com.intellij.psi.stubs.PsiFileStubImpl

class MojoFileStub(
    file: MojoFile?,
    val moduleName: String,
) : PsiFileStubImpl<MojoFile>(file) {
    override fun getType() = MojoFileElementType
}
