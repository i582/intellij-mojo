package com.github.petrmakhnev.intellijmojo.lang.stub.type

import com.github.petrmakhnev.intellijmojo.lang.psi.MojoNamedElement
import com.github.petrmakhnev.intellijmojo.lang.stub.MojoNamedStub
import com.github.petrmakhnev.intellijmojo.lang.stub.index.MojoNamedElementIndex
import com.intellij.lang.ASTNode
import com.intellij.psi.stubs.IndexSink

abstract class MojoNamedStubElementType<S : MojoNamedStub<T>, T : MojoNamedElement>(debugName: String) :
    MojoStubElementType<S, T>(debugName) {
    override fun shouldCreateStub(node: ASTNode?): Boolean {
        if (!super.shouldCreateStub(node)) return false
        return (node?.psi as? MojoNamedElement)?.name?.isNotEmpty() == true
    }

    final override fun indexStub(stub: S, sink: IndexSink) {
        val name = stub.name?.removeSurrounding("`")
        if (name.isNullOrEmpty()) return

        sink.occurrence(MojoNamedElementIndex.KEY, name)
        indexAdditional(stub, name, sink)
    }

    protected open fun indexAdditional(stub: S, name: String, sink: IndexSink) = Unit
}
