package com.github.petrmakhnev.intellijmojo.lang.stub.type

import com.github.petrmakhnev.intellijmojo.lang.psi.MojoComptimeStatement
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoFunctionDeclaration
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoMlirRegionDeclaration
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoStructDeclaration
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoTraitDeclaration
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoTypes
import com.github.petrmakhnev.intellijmojo.lang.psi.impl.MojoComptimeStatementImpl
import com.github.petrmakhnev.intellijmojo.lang.psi.impl.MojoFunctionDeclarationImpl
import com.github.petrmakhnev.intellijmojo.lang.psi.impl.MojoMlirRegionDeclarationImpl
import com.github.petrmakhnev.intellijmojo.lang.psi.impl.MojoStructDeclarationImpl
import com.github.petrmakhnev.intellijmojo.lang.psi.impl.MojoTraitDeclarationImpl
import com.github.petrmakhnev.intellijmojo.lang.stub.MojoFunctionStub
import com.github.petrmakhnev.intellijmojo.lang.stub.MojoMlirRegionStub
import com.github.petrmakhnev.intellijmojo.lang.stub.MojoStructStub
import com.github.petrmakhnev.intellijmojo.lang.stub.MojoTraitStub
import com.github.petrmakhnev.intellijmojo.lang.stub.MojoComptimeStub
import com.github.petrmakhnev.intellijmojo.lang.stub.index.MojoFunctionIndex
import com.github.petrmakhnev.intellijmojo.lang.stub.index.MojoStructIndex
import com.github.petrmakhnev.intellijmojo.lang.stub.index.MojoTraitIndex
import com.github.petrmakhnev.intellijmojo.lang.stub.index.MojoTypeIndex
import com.intellij.psi.PsiElement
import com.intellij.psi.stubs.IndexSink
import com.intellij.psi.stubs.StubElement
import com.intellij.psi.stubs.StubInputStream
import com.intellij.psi.stubs.StubOutputStream
import com.intellij.util.io.StringRef

class MojoComptimeStubElementType(debugName: String) :
    MojoNamedStubElementType<MojoComptimeStub, MojoComptimeStatement>(debugName) {
    override fun serialize(stub: MojoComptimeStub, dataStream: StubOutputStream) {
        dataStream.writeName(stub.name)
        dataStream.writeBoolean(stub.isTypeAlias)
    }

    override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>): MojoComptimeStub =
        MojoComptimeStub(parentStub, this, dataStream.readName(), dataStream.readBoolean())

    override fun createStub(psi: MojoComptimeStatement, parentStub: StubElement<out PsiElement>): MojoComptimeStub =
        MojoComptimeStub(
            parentStub,
            this,
            StringRef.fromString(psi.name),
            psi.node.findChildByType(MojoTypes.TYPE) != null,
        )

    override fun createPsi(stub: MojoComptimeStub): MojoComptimeStatement = MojoComptimeStatementImpl(stub, this)

    override fun indexAdditional(stub: MojoComptimeStub, name: String, sink: IndexSink) {
        if (stub.isTypeAlias) sink.occurrence(MojoTypeIndex.KEY, name)
    }
}

class MojoFunctionStubElementType(debugName: String) :
    MojoNamedStubElementType<MojoFunctionStub, MojoFunctionDeclaration>(debugName) {
    override fun serialize(stub: MojoFunctionStub, dataStream: StubOutputStream) = dataStream.writeName(stub.name)

    override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>): MojoFunctionStub =
        MojoFunctionStub(parentStub, this, dataStream.readName())

    override fun createStub(psi: MojoFunctionDeclaration, parentStub: StubElement<out PsiElement>): MojoFunctionStub =
        MojoFunctionStub(parentStub, this, StringRef.fromString(psi.name))

    override fun createPsi(stub: MojoFunctionStub): MojoFunctionDeclaration = MojoFunctionDeclarationImpl(stub, this)

    override fun indexAdditional(stub: MojoFunctionStub, name: String, sink: IndexSink) =
        sink.occurrence(MojoFunctionIndex.KEY, name)
}

class MojoStructStubElementType(debugName: String) :
    MojoNamedStubElementType<MojoStructStub, MojoStructDeclaration>(debugName) {
    override fun serialize(stub: MojoStructStub, dataStream: StubOutputStream) = dataStream.writeName(stub.name)

    override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>): MojoStructStub =
        MojoStructStub(parentStub, this, dataStream.readName())

    override fun createStub(psi: MojoStructDeclaration, parentStub: StubElement<out PsiElement>): MojoStructStub =
        MojoStructStub(parentStub, this, StringRef.fromString(psi.name))

    override fun createPsi(stub: MojoStructStub): MojoStructDeclaration = MojoStructDeclarationImpl(stub, this)

    override fun indexAdditional(stub: MojoStructStub, name: String, sink: IndexSink) {
        sink.occurrence(MojoStructIndex.KEY, name)
        sink.occurrence(MojoTypeIndex.KEY, name)
    }
}

class MojoTraitStubElementType(debugName: String) :
    MojoNamedStubElementType<MojoTraitStub, MojoTraitDeclaration>(debugName) {
    override fun serialize(stub: MojoTraitStub, dataStream: StubOutputStream) = dataStream.writeName(stub.name)

    override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>): MojoTraitStub =
        MojoTraitStub(parentStub, this, dataStream.readName())

    override fun createStub(psi: MojoTraitDeclaration, parentStub: StubElement<out PsiElement>): MojoTraitStub =
        MojoTraitStub(parentStub, this, StringRef.fromString(psi.name))

    override fun createPsi(stub: MojoTraitStub): MojoTraitDeclaration = MojoTraitDeclarationImpl(stub, this)

    override fun indexAdditional(stub: MojoTraitStub, name: String, sink: IndexSink) {
        sink.occurrence(MojoTraitIndex.KEY, name)
        sink.occurrence(MojoTypeIndex.KEY, name)
    }
}

class MojoMlirRegionStubElementType(debugName: String) :
    MojoNamedStubElementType<MojoMlirRegionStub, MojoMlirRegionDeclaration>(debugName) {
    override fun serialize(stub: MojoMlirRegionStub, dataStream: StubOutputStream) = dataStream.writeName(stub.name)

    override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>): MojoMlirRegionStub =
        MojoMlirRegionStub(parentStub, this, dataStream.readName())

    override fun createStub(
        psi: MojoMlirRegionDeclaration,
        parentStub: StubElement<out PsiElement>,
    ): MojoMlirRegionStub = MojoMlirRegionStub(parentStub, this, StringRef.fromString(psi.name))

    override fun createPsi(stub: MojoMlirRegionStub): MojoMlirRegionDeclaration =
        MojoMlirRegionDeclarationImpl(stub, this)
}
