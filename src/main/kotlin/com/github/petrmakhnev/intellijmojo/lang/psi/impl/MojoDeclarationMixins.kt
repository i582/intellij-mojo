package com.github.petrmakhnev.intellijmojo.lang.psi.impl

import com.github.petrmakhnev.intellijmojo.lang.psi.MojoComptimeStatement
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoFunctionDeclaration
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoMlirRegionDeclaration
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoStructDeclaration
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoTraitDeclaration
import com.github.petrmakhnev.intellijmojo.lang.stub.MojoComptimeStub
import com.github.petrmakhnev.intellijmojo.lang.stub.MojoFunctionStub
import com.github.petrmakhnev.intellijmojo.lang.stub.MojoMlirRegionStub
import com.github.petrmakhnev.intellijmojo.lang.stub.MojoStructStub
import com.github.petrmakhnev.intellijmojo.lang.stub.MojoTraitStub
import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement
import com.intellij.psi.stubs.IStubElementType

abstract class MojoFunctionDeclarationMixin :
    MojoNamedElementImpl<MojoFunctionStub>,
    MojoFunctionDeclaration {
    constructor(node: ASTNode) : super(node)
    constructor(stub: MojoFunctionStub, stubType: IStubElementType<*, *>) : super(stub, stubType)

    override val identifier: PsiElement?
        get() = functionSignature.declarationName?.nameIdentifier
}

abstract class MojoStructDeclarationMixin :
    MojoNamedElementImpl<MojoStructStub>,
    MojoStructDeclaration {
    constructor(node: ASTNode) : super(node)
    constructor(stub: MojoStructStub, stubType: IStubElementType<*, *>) : super(stub, stubType)

    override val identifier: PsiElement?
        get() = structHeader.declarationName?.nameIdentifier
}

abstract class MojoTraitDeclarationMixin :
    MojoNamedElementImpl<MojoTraitStub>,
    MojoTraitDeclaration {
    constructor(node: ASTNode) : super(node)
    constructor(stub: MojoTraitStub, stubType: IStubElementType<*, *>) : super(stub, stubType)

    override val identifier: PsiElement?
        get() = declarationName?.nameIdentifier
}

abstract class MojoMlirRegionDeclarationMixin :
    MojoNamedElementImpl<MojoMlirRegionStub>,
    MojoMlirRegionDeclaration {
    constructor(node: ASTNode) : super(node)
    constructor(stub: MojoMlirRegionStub, stubType: IStubElementType<*, *>) : super(stub, stubType)

    override val identifier: PsiElement?
        get() = declarationName?.nameIdentifier
}

abstract class MojoComptimeStatementMixin :
    MojoNamedElementImpl<MojoComptimeStub>,
    MojoComptimeStatement {
    constructor(node: ASTNode) : super(node)
    constructor(stub: MojoComptimeStub, stubType: IStubElementType<*, *>) : super(stub, stubType)

    override val identifier: PsiElement?
        get() = declarationName?.nameIdentifier
}
