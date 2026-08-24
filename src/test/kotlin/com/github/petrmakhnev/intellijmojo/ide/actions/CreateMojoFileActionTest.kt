package com.github.petrmakhnev.intellijmojo.ide.actions

import com.github.petrmakhnev.intellijmojo.lang.psi.MojoFile
import com.intellij.ide.fileTemplates.FileTemplateManager
import com.intellij.ide.fileTemplates.FileTemplateUtil
import com.intellij.ide.fileTemplates.InternalTemplateBean
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class CreateMojoFileActionTest : BasePlatformTestCase() {
    fun testActionIsRegistered() {
        val action = ActionManager.getInstance().getAction("Mojo.NewFile")

        assertInstanceOf(action, CreateMojoFileAction::class.java)
        assertTrue(
            InternalTemplateBean.EP_NAME.extensionList.any {
                it.name == CreateMojoFileAction.TEMPLATE_NAME
            },
        )
    }

    fun testTemplateCreatesMojoFileAndAddsExtension() {
        val template = FileTemplateManager.getInstance(project)
            .getInternalTemplate(CreateMojoFileAction.TEMPLATE_NAME)
        val directory = PsiManager.getInstance(project)
            .findDirectory(myFixture.tempDirFixture.findOrCreateDir("new-mojo-file"))!!
        lateinit var created: PsiElement

        WriteCommandAction.runWriteCommandAction(project) {
            created = FileTemplateUtil.createFromTemplate(
                template,
                "sample",
                null,
                directory,
            )
        }

        assertEquals("mojo", template.extension)
        assertEquals("sample.mojo", created.containingFile.name)
        assertInstanceOf(created.containingFile, MojoFile::class.java)
    }
}
