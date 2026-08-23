package com.github.petrmakhnev.intellijmojo.lang.editor

import com.intellij.codeInsight.generation.IndentedCommenter

class MojoCommenter : IndentedCommenter {
    override fun getLineCommentPrefix(): String = "# "

    override fun getBlockCommentPrefix(): String? = null

    override fun getBlockCommentSuffix(): String? = null

    override fun getCommentedBlockCommentPrefix(): String? = null

    override fun getCommentedBlockCommentSuffix(): String? = null

    override fun forceIndentedLineComment(): Boolean = true
}
