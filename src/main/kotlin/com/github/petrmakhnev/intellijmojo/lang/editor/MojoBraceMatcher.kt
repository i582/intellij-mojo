package com.github.petrmakhnev.intellijmojo.lang.editor

import com.github.petrmakhnev.intellijmojo.lang.psi.MojoTypes
import com.intellij.lang.BracePair
import com.intellij.lang.PairedBraceMatcher
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType

class MojoBraceMatcher : PairedBraceMatcher {
    override fun getPairs(): Array<BracePair> = PAIRS

    override fun isPairedBracesAllowedBeforeType(
        lbraceType: IElementType,
        contextType: IElementType?,
    ): Boolean = true

    override fun getCodeConstructStart(file: PsiFile, openingBraceOffset: Int): Int = openingBraceOffset

    companion object {
        private val PAIRS = arrayOf(
            BracePair(MojoTypes.LPAR, MojoTypes.RPAR, false),
            BracePair(MojoTypes.LBRACKET, MojoTypes.RBRACKET, false),
            BracePair(MojoTypes.LBRACE, MojoTypes.RBRACE, true),
        )
    }
}
