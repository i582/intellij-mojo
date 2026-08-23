@file:Suppress("DEPRECATION")

package com.github.petrmakhnev.intellijmojo.lang.psi.impl

import com.github.petrmakhnev.intellijmojo.lang.MojoFileType
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoNameIdentifier
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoNamedElement
import com.github.petrmakhnev.intellijmojo.lang.stub.MojoNamedStub
import com.intellij.lang.ASTNode
import com.intellij.navigation.ItemPresentation
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.stubs.IStubElementType
import com.intellij.psi.util.PsiTreeUtil

abstract class MojoNamedElementImpl<S : MojoNamedStub<*>> : MojoStubbedElementImpl<S>, MojoNamedElement {
    constructor(stub: S, nodeType: IStubElementType<*, *>) : super(stub, nodeType)
    constructor(node: ASTNode) : super(node)

    override fun getName(): String? = stub?.name ?: identifier?.text?.removeSurrounding("`")

    override fun getNameIdentifier(): PsiElement? = identifier

    override fun getTextOffset(): Int = identifier?.textOffset ?: super.getTextOffset()

    override fun setName(name: String): PsiElement {
        val replacement = PsiFileFactory.getInstance(project)
            .createFileFromText("rename.mojo", MojoFileType, "def $name():\n    pass\n")
            .let { PsiTreeUtil.findChildOfType(it, MojoNameIdentifier::class.java) }
        if (replacement != null) identifier?.replace(replacement)
        return this
    }

    override fun getPresentation(): ItemPresentation = object : ItemPresentation {
        override fun getPresentableText(): String? = name

        override fun getLocationString(): String? = containingFile?.name

        override fun getIcon(unused: Boolean) = null
    }

    override fun toString(): String = "${stub?.stubType ?: node.elementType}:$name"
}
