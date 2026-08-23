@file:Suppress("DEPRECATION")

package com.github.petrmakhnev.intellijmojo.lang.psi.impl

import com.intellij.extapi.psi.StubBasedPsiElementBase
import com.intellij.lang.ASTNode
import com.intellij.psi.stubs.IStubElementType
import com.intellij.psi.stubs.StubBase

abstract class MojoStubbedElementImpl<S : StubBase<*>> : StubBasedPsiElementBase<S> {
    constructor(stub: S, nodeType: IStubElementType<*, *>) : super(stub, nodeType)
    constructor(node: ASTNode) : super(node)

    override fun toString(): String = (stub?.stubType ?: node.elementType).toString()
}
