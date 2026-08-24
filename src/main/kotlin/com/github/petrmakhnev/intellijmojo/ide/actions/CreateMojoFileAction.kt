package com.github.petrmakhnev.intellijmojo.ide.actions

import com.github.petrmakhnev.intellijmojo.MojoBundle
import com.github.petrmakhnev.intellijmojo.icons.MojoIcons
import com.intellij.ide.actions.CreateFileFromTemplateAction
import com.intellij.ide.actions.CreateFileFromTemplateDialog
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDirectory

class CreateMojoFileAction : CreateFileFromTemplateAction(
    MojoBundle["action.create.mojo.file"],
    MojoBundle["action.create.mojo.file.description"],
    MojoIcons.FILE,
) {
    override fun buildDialog(
        project: Project,
        directory: PsiDirectory,
        builder: CreateFileFromTemplateDialog.Builder,
    ) {
        builder
            .setTitle(MojoBundle["dialog.create.mojo.file.title"])
            .addKind(MojoBundle["action.create.mojo.file"], MojoIcons.FILE, TEMPLATE_NAME)
    }

    override fun getActionName(
        directory: PsiDirectory,
        newName: String,
        templateName: String,
    ): String = MojoBundle["command.create.mojo.file", newName]

    companion object {
        const val TEMPLATE_NAME = "Mojo File"
    }
}
