package com.github.petrmakhnev.intellijmojo.lang.stub

import com.github.petrmakhnev.intellijmojo.lang.psi.MojoNamedElement
import com.intellij.psi.stubs.IStubElementType
import com.intellij.psi.stubs.NamedStubBase
import com.intellij.psi.stubs.StubElement
import com.intellij.util.io.StringRef

abstract class MojoNamedStub<T : MojoNamedElement>(
    parent: StubElement<*>,
    elementType: IStubElementType<*, *>,
    name: StringRef?,
) : NamedStubBase<T>(parent, elementType, name)
