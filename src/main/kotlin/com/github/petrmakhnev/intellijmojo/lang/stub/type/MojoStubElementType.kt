package com.github.petrmakhnev.intellijmojo.lang.stub.type

import com.github.petrmakhnev.intellijmojo.lang.MojoLanguage
import com.intellij.psi.PsiElement
import com.intellij.psi.stubs.IStubElementType
import com.intellij.psi.stubs.IndexSink
import com.intellij.psi.stubs.StubBase

abstract class MojoStubElementType<S : StubBase<T>, T : PsiElement>(debugName: String) :
    IStubElementType<S, T>(debugName, MojoLanguage) {
    override fun getExternalId(): String = "mojo.${super.toString()}"

    override fun indexStub(stub: S, sink: IndexSink) = Unit
}
