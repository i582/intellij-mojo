package com.github.petrmakhnev.intellijmojo.lang.stub

import com.github.petrmakhnev.intellijmojo.lang.psi.MojoComptimeStatement
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoFunctionDeclaration
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoMlirRegionDeclaration
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoStructDeclaration
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoTraitDeclaration
import com.intellij.psi.stubs.IStubElementType
import com.intellij.psi.stubs.StubElement
import com.intellij.util.io.StringRef

class MojoFunctionStub(
    parent: StubElement<*>,
    elementType: IStubElementType<*, *>,
    name: StringRef?,
) : MojoNamedStub<MojoFunctionDeclaration>(parent, elementType, name)

class MojoStructStub(
    parent: StubElement<*>,
    elementType: IStubElementType<*, *>,
    name: StringRef?,
) : MojoNamedStub<MojoStructDeclaration>(parent, elementType, name)

class MojoTraitStub(
    parent: StubElement<*>,
    elementType: IStubElementType<*, *>,
    name: StringRef?,
) : MojoNamedStub<MojoTraitDeclaration>(parent, elementType, name)

class MojoMlirRegionStub(
    parent: StubElement<*>,
    elementType: IStubElementType<*, *>,
    name: StringRef?,
) : MojoNamedStub<MojoMlirRegionDeclaration>(parent, elementType, name)

class MojoComptimeStub(
    parent: StubElement<*>,
    elementType: IStubElementType<*, *>,
    name: StringRef?,
    val isTypeAlias: Boolean,
) : MojoNamedStub<MojoComptimeStatement>(parent, elementType, name)
