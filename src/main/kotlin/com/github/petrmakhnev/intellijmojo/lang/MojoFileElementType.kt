package com.github.petrmakhnev.intellijmojo.lang

import com.github.petrmakhnev.intellijmojo.lang.lexer.MojoTokenSets
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoFile
import com.github.petrmakhnev.intellijmojo.lang.stub.MojoFileStub
import com.github.petrmakhnev.intellijmojo.lang.stub.index.MojoModuleIndex
import com.intellij.lang.ASTNode
import com.intellij.psi.PsiFile
import com.intellij.psi.StubBuilder
import com.intellij.psi.stubs.DefaultStubBuilder
import com.intellij.psi.stubs.IndexSink
import com.intellij.psi.stubs.PsiFileStub
import com.intellij.psi.stubs.StubElement
import com.intellij.psi.stubs.StubInputStream
import com.intellij.psi.stubs.StubOutputStream
import com.intellij.psi.tree.IStubFileElementType

private const val STUB_VERSION = 3

object MojoFileElementType : IStubFileElementType<MojoFileStub>("MOJO_FILE", MojoLanguage) {
    override fun getStubVersion(): Int = STUB_VERSION

    override fun getBuilder(): StubBuilder = object : DefaultStubBuilder() {
        override fun createStubForFile(file: PsiFile): StubElement<*> =
            if (file is MojoFile) MojoFileStub(file, file.moduleName) else super.createStubForFile(file)

        override fun skipChildProcessingWhenBuildingStubs(parent: ASTNode, node: ASTNode): Boolean =
            node.elementType in MojoTokenSets.COMMENTS
    }

    override fun serialize(stub: MojoFileStub, dataStream: StubOutputStream) =
        dataStream.writeName(stub.moduleName)

    override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?): MojoFileStub =
        MojoFileStub(null, dataStream.readName()?.string.orEmpty())

    override fun indexStub(stub: PsiFileStub<*>, sink: IndexSink) {
        if (stub is MojoFileStub && stub.moduleName.isNotEmpty()) {
            sink.occurrence(MojoModuleIndex.KEY, stub.moduleName)
        }
    }

    override fun getExternalId(): String = "mojo.FILE"
}
